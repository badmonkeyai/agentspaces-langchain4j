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
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.langchain4j.support.Fleets;
import ai.badmonkey.agentspaces.langchain4j.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interface-worker path with a second verb: an {@code AiServices}
 * interface whose method carries {@code @SpaceNotify} beside
 * {@code @UserMessage} binds as a reaction and its model's answer lands in
 * the space, with the watched entry left in place.
 */
class LangChain4jNotifyServiceTest {

    public record Brief(String topic, int sentences) {
    }

    public record Label(String topic, String label) {
    }

    @AgentSpec(name = "labeller", description = "Labels briefs with a model", goals = {"label"})
    public interface Labeller {
        @UserMessage("Label this brief: {{it}}")
        @SpaceNotify(space = "tasks")
        Label label(Brief brief);
    }

    @Test
    @Timeout(60)
    void anAiServiceWithSpaceNotifyBindsAndReacts() {
        try (FleetRuntime runtime = Fleets.start(Fleets.freePort(), 0)) {
            LangChain4jFleet fleet = LangChain4jFleet.builder(runtime).build();
            ScriptedChatModel model = new ScriptedChatModel("scripted")
                    .then(request -> AiMessage.from("{\"topic\":\"offsite\",\"label\":\"planning\"}"));
            Labeller labeller = AiServices.builder(Labeller.class).chatModel(fleet.listening(model)).build();
            var bound = runtime.bind(labeller);
            assertThat(bound).hasSize(1);
            assertThat(bound.get(0).card().consumes()).contains(Brief.class.getName() + "#v1");

            Space tasks = runtime.context().group().space("tasks");
            tasks.write(new Brief("offsite", 2), Lease.of(Duration.ofMinutes(5)));
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> !tasks.readAll(Template.of(Label.class), 10).isEmpty())).isTrue();
            assertThat(tasks.readAll(Template.of(Label.class), 10)).containsExactly(new Label("offsite", "planning"));
            assertThat(tasks.readAll(Template.of(Brief.class), 10)).as("a notify leaves the entry").hasSize(1);
            assertThat(model.requests()).singleElement()
                    .satisfies(r -> assertThat(r.messages().toString()).contains("Label this brief"));
        }
    }
}
