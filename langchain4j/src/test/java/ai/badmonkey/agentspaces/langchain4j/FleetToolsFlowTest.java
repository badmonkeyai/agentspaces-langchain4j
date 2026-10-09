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

import ai.badmonkey.agentspaces.langchain4j.support.Fleets;
import ai.badmonkey.agentspaces.langchain4j.support.Orchestrator;
import ai.badmonkey.agentspaces.langchain4j.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.langchain4j.support.Scripts;
import ai.badmonkey.agentspaces.langchain4j.support.Summarizer;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.tools.ToolSettings;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FleetToolsFlowTest {

    @Test
    @Timeout(180)
    void anAiServiceCallsAWorkerOnAnotherPeerAsATool() {
        int seedPort = Fleets.freePort();
        try (FleetRuntime worker = Fleets.start(seedPort, 0);
             FleetRuntime orchestrator = Fleets.start(Fleets.freePort(), seedPort)) {
            worker.bind(new Summarizer());
            LangChain4jFleet fleet = LangChain4jFleet.builder(orchestrator)
                    .tools(ToolSettings.builder().refreshInterval(Duration.ofMillis(500)).build())
                    .build();
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> !fleet.catalog().current().isEmpty())).isTrue();
            assertThat(fleet.catalog().current()).extracting("name").containsExactly("fleet_summarizer_Brief");
            assertThat(fleet.tools().specifications()).singleElement().satisfies(spec -> {
                assertThat(spec.description()).contains("Summarizes a topic").contains("Brief");
                assertThat(spec.parameters().properties()).containsKeys("topic", "sentences");
                assertThat(spec.parameters().required()).containsExactly("topic", "sentences");
            });

            ScriptedChatModel model = Scripts.orchestrator();
            Orchestrator service = AiServices.builder(Orchestrator.class)
                    .chatModel(fleet.listening(model))
                    .toolProvider(fleet.tools())
                    .build();
            String plan = service.plan("Plan the offsite");
            assertThat(plan).contains("summary of offsite in 2 sentences");
            assertThat(model.requests()).hasSize(2);
            // The model saw the fleet tool on its first round-trip and the tool's result on its second.
            assertThat(model.requests().get(0).toolSpecifications()).extracting("name").contains("fleet_summarizer_Brief");

        }
    }

    @Test
    @Timeout(60)
    void aServiceWithoutTheProviderSeesNoFleetTools() {
        try (FleetRuntime orchestrator = Fleets.start(Fleets.freePort(), 0)) {
            LangChain4jFleet fleet = LangChain4jFleet.builder(orchestrator).build();
            assertThatThrownBy(fleet::tools).hasMessageContaining("builder.tools");
            Orchestrator service = AiServices.builder(Orchestrator.class).chatModel(Scripts.orchestrator()).build();
            assertThat(service.plan("anything")).isEqualTo("no fleet tools offered");
        }
    }
}
