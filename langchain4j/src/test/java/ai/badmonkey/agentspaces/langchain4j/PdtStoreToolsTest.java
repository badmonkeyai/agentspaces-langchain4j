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
import ai.badmonkey.agentspaces.langchain4j.tools.FleetCapabilityTools;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.PropertiesLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store tools of PDT 2 phase 7, P2-C-04 (srad/agentspaces-pdt2.md §3.2): one runtime is a content
 * store ({@code agentspaces.store.enabled}); the other writes a map ref through {@code pdt_assoc}, asks
 * the stores to hold its root through {@code pdt_pin}, and reads the store's receipt through
 * {@code pdt_store_status}, over real sockets. A pin of a root this peer does not hold, and arguments out
 * of range, answer errors the model can read.
 */
class PdtStoreToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static FleetRuntime start(int port, int seedPort, boolean store) {
        Map<String, Object> props = Fleets.fleet(port, seedPort);
        props.put("agentspaces.content.enabled", "true");
        props.put("agentspaces.content.store.backends[0]", "memory");
        props.put("agentspaces.pdt.enabled", "true");
        props.put("agentspaces.pdt.store.kind", "memory");
        if (store) {
            props.put("agentspaces.store.enabled", "true");
            props.put("agentspaces.store.name", "site-a");
            props.put("agentspaces.store.capacity", "64MiB");
            props.put("agentspaces.store.accept-requesters", "open");
        }
        return FleetRuntime.start(PropertiesLoader.flat(props));
    }

    @Test
    @Timeout(180)
    void aModelPinsARefsRootOnAStoreAndReadsTheReceipt() throws Exception {
        int seedPort = Fleets.freePort();
        try (FleetRuntime store = start(seedPort, 0, true); FleetRuntime member = start(Fleets.freePort(), seedPort, false)) {
            String storePeer = store.identity().peerId().value();
            LangChain4jFleet fleet = LangChain4jFleet.builder(member)
                    .capabilityTools(FleetCapabilityTools.Settings.defaults().withPdt(true)).build();
            FleetCapabilityTools tools = fleet.capabilityTools();
            assertThat(FleetCapabilityTools.PDT_TOOLS).contains("pdt_pin", "pdt_store_status");
            assertThat(fleet.tools().provideTools(new ToolProviderRequest("m", UserMessage.from("x"))).aiServiceTools())
                    .extracting(t -> t.toolSpecification().name())
                    .containsExactlyInAnyOrderElementsOf(FleetCapabilityTools.PDT_TOOLS);

            String root = JSON.readTree(tools.pdtAssoc("invoices", "inv-1", "\"paid\"")).get("root").asText();
            assertThat(tools.pdtStoreStatus(root)).contains("\"copies\":0");

            String[] pinned = new String[1];
            assertThat(Fleets.await(Duration.ofSeconds(60), () -> !(pinned[0] = tools.pdtPin(root, 1, 2)).contains("error")))
                    .as("the store's card reaches the member: %s", pinned[0]).isTrue();
            JsonNode pin = JSON.readTree(pinned[0]);
            assertThat(pin.get("root").asText()).isEqualTo(root);
            assertThat(pin.get("kind").asText()).isEqualTo("tree");
            assertThat(pin.get("copies").asInt()).isEqualTo(1);
            assertThat(pin.get("leaseSeconds").asLong()).isEqualTo(7200);
            assertThat(pin.get("stores").get(0).asText()).isEqualTo(storePeer);

            assertThat(Fleets.await(Duration.ofSeconds(60), () -> tools.pdtStoreStatus(root).contains("\"copies\":1")))
                    .as(tools.pdtStoreStatus(root)).isTrue();
            JsonNode status = JSON.readTree(tools.pdtStoreStatus(root));
            JsonNode holder = status.get("holders").get(0);
            assertThat(holder.get("store").asText()).isEqualTo(storePeer);
            assertThat(holder.get("bytes").asLong()).isPositive();
            assertThat(holder.get("until").asText()).endsWith("Z");
            assertThat(holder.get("blind").asBoolean()).isFalse();
            assertThat(status.get("unplaced").asBoolean()).isFalse();

            assertThat(tools.pdtPin("bafyreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku", 1, 1))
                    .contains("error").contains("not complete");
            assertThat(tools.pdtPin(root, 0, 1)).contains("error").contains("copies");
            assertThat(tools.pdtPin(root, 1, 0)).contains("error").contains("hours");
            assertThat(tools.pdtStoreStatus("")).contains("error");
        }
    }
}
