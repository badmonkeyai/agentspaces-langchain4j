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
package ai.badmonkey.agentspaces.langchain4j.tools;

import ai.badmonkey.agentspaces.agent.capability.AggregateClient;
import ai.badmonkey.agentspaces.agent.capability.VoteClient;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.runtime.FleetContext;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Layer 4 for a model (L3L4-COVERAGE.md §6.3): {@code @Tool} methods a
 * LangChain4j service calls to open a vote, cast a ballot, read a tally or
 * a decision, contribute to a push-sum epoch, and read its estimate, each
 * switched on separately by {@link Settings}. The model acts as this peer's
 * agent through the typed clients the fleet resolves, so a ballot is that
 * agent's attested record and a contribution joins the group's epoch.
 */
public final class FleetCapabilityTools {

    /** Which tools are on; both off by default. */
    public record Settings(boolean vote, boolean aggregate, Duration ballotLease, Duration settleTimeout) {
        public Settings {
            Objects.requireNonNull(ballotLease, "ballotLease");
            Objects.requireNonNull(settleTimeout, "settleTimeout");
        }

        public static Settings defaults() {
            return new Settings(false, false, Duration.ofHours(1), Duration.ofSeconds(10));
        }

        /** Both on. */
        public static Settings all() {
            return new Settings(true, true, Duration.ofHours(1), Duration.ofSeconds(10));
        }

        public Settings withVote(boolean on) {
            return new Settings(on, aggregate, ballotLease, settleTimeout);
        }

        public Settings withAggregate(boolean on) {
            return new Settings(vote, on, ballotLease, settleTimeout);
        }

        public boolean any() {
            return vote || aggregate;
        }
    }

    private final Supplier<VoteClient> vote;
    private final Supplier<AggregateClient> aggregate;
    private final Settings settings;
    private final Set<String> enabled;
    private final ObjectMapper json = new ObjectMapper();

    public FleetCapabilityTools(FleetContext fleet, Settings settings) {
        this(() -> fleet.group().capability(VoteClient.class), () -> fleet.group().capability(AggregateClient.class),
                settings);
    }

    public FleetCapabilityTools(Supplier<VoteClient> vote, Supplier<AggregateClient> aggregate, Settings settings) {
        this.vote = vote;
        this.aggregate = aggregate;
        this.settings = Objects.requireNonNull(settings, "settings");
        Set<String> names = new LinkedHashSet<>();
        if (settings.vote()) {
            names.addAll(List.of("propose_vote", "cast_ballot", "read_tally", "read_decision"));
        }
        if (settings.aggregate()) {
            names.addAll(List.of("contribute", "read_estimate"));
        }
        this.enabled = Set.copyOf(names);
    }

    /** Whether a tool is switched on. */
    public boolean enabled(String toolName) {
        return enabled.contains(toolName);
    }

    /** The executor of one of this object's {@code @Tool} methods. */
    public ToolExecutor executor(String toolName) {
        for (Method method : getClass().getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && toolName.equals(tool.name())) {
                return new DefaultToolExecutor(this, method);
            }
        }
        throw new IllegalArgumentException("no capability tool named " + toolName);
    }

    @Tool(name = "propose_vote", value = "Opens a vote in the fleet: a proposal with an id, a question, the options"
            + " to choose from, and the number of distinct voters that closes it. Idempotent on the id.")
    public String proposeVote(@P("A short unique id for the proposal") String id,
                              @P("The question the fleet votes on") String question,
                              @P("The options, at least two") List<String> options,
                              @P("How many distinct voters close the vote") int quorum) {
        VoteClient client = vote.get();
        if (client.proposal(id).isEmpty()) {
            client.propose(id, question, options, quorum, Lease.of(settings.ballotLease()));
        }
        return render(proposal(client.proposal(id).orElseThrow()));
    }

    @Tool(name = "cast_ballot", value = "Casts this agent's ballot on an open proposal; one ballot per proposal counts.")
    public String castBallot(@P("The proposal's id") String id, @P("The option, exactly as proposed") String option) {
        VoteClient client = vote.get();
        Optional<VoteCapability.Proposal> proposal = client.proposal(id);
        if (proposal.isEmpty()) {
            return render(Map.of("error", "no proposal '" + id + "' is open here"));
        }
        if (!proposal.get().options().contains(option)) {
            return render(Map.of("error", "'" + option + "' is not one of " + proposal.get().options()));
        }
        client.castBallot(id, option, Lease.of(settings.ballotLease()));
        return render(Map.of("proposalId", id, "cast", option));
    }

    @Tool(name = "read_tally", value = "The votes per option on a proposal so far.")
    public String readTally(@P("The proposal's id") String id) {
        return render(Map.of("proposalId", id, "tally", vote.get().tally(id)));
    }

    @Tool(name = "read_decision", value = "The decision of a proposal once its quorum closed it: the winner and"
            + " the tally; or that it is still open.")
    public String readDecision(@P("The proposal's id") String id) {
        Optional<VoteCapability.Decision> decision = vote.get().decision(id);
        if (decision.isEmpty()) {
            return render(Map.of("proposalId", id, "open", true));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", id);
        out.put("winner", decision.get().winner());
        out.put("tally", decision.get().tally());
        return render(out);
    }

    @Tool(name = "contribute", value = "Contributes this peer's value to a fleet-wide average under an epoch id;"
            + " every peer that contributes under the same id is averaged together.")
    public String contribute(@P("The epoch id, shared by every contributor") String epoch,
                             @P("This peer's value") double value) {
        aggregate.get().start(epoch, value);
        return render(Map.of("epoch", epoch, "contributed", value));
    }

    @Tool(name = "read_estimate", value = "The fleet-wide average under an epoch id, waiting briefly for it to"
            + " settle; or that nothing is known yet.")
    public String readEstimate(@P("The epoch id") String epoch) {
        AggregateClient client = aggregate.get();
        OptionalDouble settled = client.awaitSettled(epoch, PushSumAggregate.Settle.after(2, 0.01),
                settings.settleTimeout());
        OptionalDouble value = settled.isPresent() ? settled : client.estimate(epoch);
        if (value.isEmpty()) {
            return render(Map.of("epoch", epoch, "known", false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("epoch", epoch);
        out.put("estimate", value.getAsDouble());
        out.put("settled", settled.isPresent());
        return render(out);
    }

    private static Map<String, Object> proposal(VoteCapability.Proposal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", p.proposalId());
        out.put("question", p.question());
        out.put("options", p.options());
        out.put("quorum", p.quorum());
        return out;
    }

    private String render(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
