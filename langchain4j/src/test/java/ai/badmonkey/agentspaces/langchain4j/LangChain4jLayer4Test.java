/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.langchain4j;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.Ballot;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.agent.capability.VoteClient;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.langchain4j.support.Fleets;
import ai.badmonkey.agentspaces.langchain4j.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.langchain4j.tools.FleetCapabilityTools;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 4 the LangChain4j way (L3L4-COVERAGE.md §6.3): an {@code AiServices}
 * interface whose methods carry the fleet annotations binds as a worker and
 * a voter, with no loop written, because the binder reads the annotations
 * through the proxy's interface; and the capability tools let a model open
 * a vote, cast, and read the decision, and contribute to and read a
 * push-sum.
 */
class LangChain4jLayer4Test {

    public record Brief(String topic, int sentences) {
    }

    public record Summary(String topic, String text) {
    }

    /** The service is the worker: the model summarizes what the space hands it, and judges proposals. */
    @AgentSpec(name = "reviewer", description = "Summarizes briefs and judges proposals with a model", goals = {"review"})
    public interface Reviewer {
        @SystemMessage("You summarize briefs for a fleet.")
        @dev.langchain4j.service.UserMessage("Summarize: {{it}}")
        @SpaceTake(space = "tasks", pollTimeout = "PT0.2S")
        Summary summarize(Brief brief);

        @dev.langchain4j.service.UserMessage("Approve or deny: {{it}}")
        @Ballot(space = "votes")
        String judge(VoteCapability.Proposal proposal);
    }

    private static VoteCapability vote(FleetRuntime runtime) {
        Space votes = runtime.context().group().space("votes");
        VoteCapability vote = new VoteCapability(votes, runtime.identity().agent("app"), runtime.identity().peerId(),
                runtime.clock());
        runtime.context().group().provide(vote);
        return vote;
    }

    @Test
    @Timeout(120)
    void anAiServiceWithFleetAnnotationsBindsAsAWorkerAndAVoter() {
        int seedPort = Fleets.freePort();
        try (FleetRuntime worker = Fleets.start(seedPort, 0, "votes");
             FleetRuntime client = Fleets.start(Fleets.freePort(), seedPort, "votes")) {
            VoteCapability workerVote = vote(worker);
            VoteCapability clientVote = vote(client);
            LangChain4jFleet fleet = LangChain4jFleet.builder(worker).build();
            // The model answers the summary as JSON for the record return type, and a ballot as text.
            ScriptedChatModel model = new ScriptedChatModel("scripted")
                    .then(request -> request.messages().stream().anyMatch(m -> m.toString().contains("Approve or deny"))
                            ? AiMessage.from("approve")
                            : AiMessage.from("{\"topic\":\"offsite\",\"text\":\"a tidy summary by the service\"}"));
            Reviewer reviewer = AiServices.builder(Reviewer.class).chatModel(fleet.listening(model)).build();
            var bound = worker.bind(reviewer);
            assertThat(bound).hasSize(1);
            assertThat(bound.get(0).card().agent().localName()).isEqualTo("reviewer");
            assertThat(bound.get(0).card().consumes()).contains(Brief.class.getName() + "#v1");

            Space tasks = client.context().group().space("tasks");
            tasks.write(new Brief("offsite", 2), Lease.of(Duration.ofMinutes(5)));
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> !tasks.readAll(Template.of(Summary.class), 10).isEmpty())).isTrue();
            assertThat(tasks.readAll(Template.of(Summary.class), 10)).singleElement()
                    .satisfies(s -> assertThat(s.text()).isEqualTo("a tidy summary by the service"));

            clientVote.propose("ship", "Ship 1.0?", List.of("approve", "deny"), 1, Lease.of(Duration.ofHours(1)));
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> workerVote.decision("ship").isPresent())).isTrue();
            assertThat(workerVote.decision("ship").orElseThrow().winner()).isEqualTo("approve");
        }
    }

    @Test
    @Timeout(120)
    void theCapabilityToolsLetAModelVoteAndContribute() {
        int seedPort = Fleets.freePort();
        try (FleetRuntime a = Fleets.start(seedPort, 0, "votes");
             FleetRuntime b = Fleets.start(Fleets.freePort(), seedPort, "votes")) {
            vote(a);
            vote(b);
            LangChain4jFleet fa = LangChain4jFleet.builder(a).capabilityTools(FleetCapabilityTools.Settings.all()).build();
            LangChain4jFleet fb = LangChain4jFleet.builder(b).capabilityTools(FleetCapabilityTools.Settings.all()).build();
            assertThat(fa.tools().provideTools(new ToolProviderRequest("m", UserMessage.from("x"))).aiServiceTools())
                    .extracting(t -> t.toolSpecification().name())
                    .contains("propose_vote", "cast_ballot", "read_tally", "read_decision", "contribute", "read_estimate");

            FleetCapabilityTools toolsA = fa.capabilityTools();
            FleetCapabilityTools toolsB = fb.capabilityTools();
            assertThat(toolsA.proposeVote("plan", "Adopt the plan?", List.of("yes", "no"), 2)).contains("\"quorum\":2");
            assertThat(toolsA.castBallot("plan", "yes")).contains("\"cast\":\"yes\"");
            assertThat(toolsA.castBallot("plan", "maybe")).contains("error");
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> b.context().group().capability(VoteClient.class)
                    .proposal("plan").isPresent())).isTrue();
            assertThat(toolsB.castBallot("plan", "yes")).contains("\"cast\":\"yes\"");
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> toolsA.readDecision("plan").contains("winner"))).isTrue();
            assertThat(toolsA.readDecision("plan")).contains("\"winner\":\"yes\"");
            assertThat(toolsA.readTally("plan")).contains("\"yes\":2");

            // A scripted service drives the same tools through the provider.
            ScriptedChatModel model = new ScriptedChatModel("scripted")
                    .then(r -> AiMessage.from(ToolExecutionRequest.builder().id("c1").name("read_decision")
                            .arguments("{\"id\":\"plan\"}").build()))
                    .then(r -> AiMessage.from("decided: " + r.messages().stream()
                            .filter(ToolExecutionResultMessage.class::isInstance)
                            .map(m -> ((ToolExecutionResultMessage) m).text()).toList()));
            Assistant assistant = AiServices.builder(Assistant.class).chatModel(model).toolProvider(fa.tools()).build();
            assertThat(assistant.ask("What was decided?")).contains("\"winner\":\"yes\"");

            assertThat(toolsA.contribute("load", 10.0)).contains("10.0");
            assertThat(toolsB.contribute("load", 30.0)).contains("30.0");
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> toolsA.readEstimate("load").contains("\"estimate\""))).isTrue();
            String estimate = toolsA.readEstimate("load");
            double value = Double.parseDouble(estimate.replaceAll(".*\"estimate\":([0-9.]+).*", "$1"));
            assertThat(value).isBetween(19.0, 21.0);
        }
    }

    public interface Assistant {
        String ask(String question);
    }
}
