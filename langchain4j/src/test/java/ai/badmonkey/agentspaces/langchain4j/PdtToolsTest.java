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
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The persistent-tree tools (srad/agentspaces-pdt.md PD-C-09): one peer puts values into a
 * map ref through {@code pdt_assoc}, the other reads them by path through {@code pdt_read}
 * and lists the versions through {@code pdt_history}; a peer without the trees answers an
 * error naming the property.
 */
class PdtToolsTest {

    private static FleetRuntime start(int port, int seedPort, boolean pdt) {
        Map<String, Object> props = Fleets.fleet(port, seedPort);
        if (pdt) {
            props.put("agentspaces.content.enabled", "true");
            props.put("agentspaces.content.store.backends[0]", "memory");
            props.put("agentspaces.pdt.enabled", "true");
            props.put("agentspaces.pdt.store.kind", "memory");
        }
        return FleetRuntime.start(PropertiesLoader.flat(props));
    }

    @Test
    @Timeout(120)
    void aModelAssocsReadsByPathAndListsTheHistory() throws Exception {
        int seedPort = Fleets.freePort();
        try (FleetRuntime a = start(seedPort, 0, true); FleetRuntime b = start(Fleets.freePort(), seedPort, true)) {
            FleetCapabilityTools.Settings settings = FleetCapabilityTools.Settings.defaults().withPdt(true);
            LangChain4jFleet fa = LangChain4jFleet.builder(a).capabilityTools(settings).build();
            LangChain4jFleet fb = LangChain4jFleet.builder(b).capabilityTools(settings).build();
            assertThat(fb.tools().provideTools(new ToolProviderRequest("m", UserMessage.from("x"))).aiServiceTools())
                    .extracting(t -> t.toolSpecification().name())
                    .containsExactlyInAnyOrderElementsOf(FleetCapabilityTools.PDT_TOOLS);
            assertThat(FleetCapabilityTools.pathOf("a/b/2/c")).containsExactly("a", "b", 2, "c");
            assertThat(FleetCapabilityTools.pathOf("")).isEmpty();

            String first = fa.capabilityTools().pdtAssoc("facts", "total", "3");
            assertThat(first).contains("\"ref\":\"facts\"").contains("\"value\":3").contains("\"count\":1");
            String nested = fa.capabilityTools().pdtAssoc("facts", "owner/name", "\"ana\"");
            assertThat(nested).contains("\"count\":2");
            String text = fa.capabilityTools().pdtAssoc("facts", "note", "plain words, not json");
            assertThat(text).contains("\"value\":\"plain words, not json\"");
            String list = fa.capabilityTools().pdtAssoc("facts", "tags", "[\"x\",\"y\"]");
            assertThat(list).contains("\"root\":\"bafyrei");

            assertThat(Fleets.await(Duration.ofSeconds(60),
                    () -> fb.capabilityTools().pdtRead("facts", "owner/name").contains("\"value\":\"ana\""))).isTrue();
            assertThat(fb.capabilityTools().pdtRead("facts", "total")).contains("\"found\":true").contains("\"value\":3");
            assertThat(fb.capabilityTools().pdtRead("facts", "tags/1")).contains("\"value\":\"y\"");
            assertThat(fb.capabilityTools().pdtRead("facts", "nothing/here")).contains("\"found\":false");
            String whole = fb.capabilityTools().pdtRead("facts", "");
            assertThat(whole).contains("\"count\":4").contains("\"truncated\":false").contains("\"total\":3");

            String written = fb.capabilityTools().pdtAssoc("facts", "owner/name", "\"bo\"");
            assertThat(written).contains("\"root\":\"bafyrei");
            assertThat(Fleets.await(Duration.ofSeconds(60),
                    () -> fa.capabilityTools().pdtRead("facts", "owner/name").contains("\"value\":\"bo\""))).isTrue();
            String history = fa.capabilityTools().pdtHistory("facts", 10);
            assertThat(history).contains("\"heads\":1").contains("\"head\":true").contains("\"head\":false")
                    .contains("\"seq\":5");
            assertThat(fa.capabilityTools().pdtAssoc("facts", "", "1")).contains("error");
            assertThat(fa.capabilityTools().pdtRead("", "x")).contains("error");
        }
    }

    @Test
    @Timeout(60)
    void aPeerWithoutTheTreesNamesTheProperty() {
        try (FleetRuntime plain = start(Fleets.freePort(), 0, false)) {
            LangChain4jFleet fleet = LangChain4jFleet.builder(plain)
                    .capabilityTools(FleetCapabilityTools.Settings.defaults().withPdt(true)).build();
            assertThat(fleet.capabilityTools().pdtRead("facts", "")).contains("error").contains("agentspaces.pdt.enabled");
            assertThat(FleetCapabilityTools.Settings.all().pdt()).isTrue();
            assertThat(new FleetCapabilityTools.Settings(false, false, Duration.ofHours(1), Duration.ofSeconds(1)).any()).isFalse();
            assertThat(List.of("pdt_read", "pdt_assoc", "pdt_history")).isEqualTo(FleetCapabilityTools.PDT_TOOLS);
        }
    }
}
