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

import ai.badmonkey.agentspaces.connect.ConnectorRuntime;
import ai.badmonkey.agentspaces.connect.TableAssetProvider;
import ai.badmonkey.agentspaces.langchain4j.support.Fleets;
import ai.badmonkey.agentspaces.langchain4j.support.Summarizer;
import ai.badmonkey.agentspaces.langchain4j.tools.FleetDiscoveryTools;
import ai.badmonkey.agentspaces.runtime.FleetContext;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.tools.ToolSettings;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryToolsTest {

    @Test
    @Timeout(180)
    void theDiscoveryToolsFindAgentsAndAssetsAndReadDataAcrossTheFleet() {
        int seedPort = Fleets.freePort();
        try (FleetRuntime worker = Fleets.start(seedPort, 0);
             FleetRuntime analyst = Fleets.start(Fleets.freePort(), seedPort)) {
            worker.bind(new Summarizer());
            FleetContext w = worker.context();
            ConnectorRuntime connector = new ConnectorRuntime(w.group().space("data"), w.group().discovery(),
                    w.identity(), "orders-connector",
                    new TableAssetProvider("orders", "mem://orders", "Customer orders by sku and quantity", "table",
                            Duration.ofMinutes(5), List.of(Map.of("sku", "widget", "qty", "3"))),
                    w.group().id(), w.group().clock(), Duration.ofMinutes(1))
                    .authorizer(w.authorizer());
            connector.start();
            try {
                LangChain4jFleet fleet = LangChain4jFleet.builder(analyst)
                        .tools(ToolSettings.builder().refreshInterval(Duration.ofMillis(500)).build())
                        .discoveryTools(LangChain4jSettings.DiscoveryTools.all().withTimeout(Duration.ofSeconds(5)))
                        .build();
                assertThat(Fleets.await(Duration.ofSeconds(30), () -> !fleet.catalog().current().isEmpty())).isTrue();
                FleetDiscoveryTools tools = fleet.discoveryTools();
                assertThat(Fleets.await(Duration.ofSeconds(30), () ->
                        tools.findFleetAgents("summarize a topic", 5).contains("summarizer"))).isTrue();
                assertThat(tools.findFleetAgents("summarize a topic", 5))
                        .contains("\"tools\":[\"fleet_summarizer_Brief\"]").contains("\"consumes\":[\"Brief\"]");
                assertThat(Fleets.await(Duration.ofSeconds(30), () ->
                        tools.listFleetAssets("customer orders", 5).contains("\"asset\":\"orders\""))).isTrue();
                assertThat(tools.fetchFleetData("orders", Map.of())).contains("\"sku\":\"widget\"").contains("\"fromCache\":false");
                // All three ride along in the provider's result.
                var provided = fleet.tools().provideTools(new ToolProviderRequest("m", UserMessage.from("x")));
                assertThat(provided.aiServiceTools()).extracting(t -> t.toolSpecification().name())
                        .contains("fleet_summarizer_Brief", "find_fleet_agents", "list_fleet_assets", "fetch_fleet_data");
            } finally {
                connector.close();
            }
        }
    }
}
