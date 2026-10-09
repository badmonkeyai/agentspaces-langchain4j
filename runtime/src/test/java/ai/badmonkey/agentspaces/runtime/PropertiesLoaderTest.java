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
package ai.badmonkey.agentspaces.runtime;

import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesLoaderTest {

    @Test
    void bindsTheStarterPropertySetFromFlatKeys() {
        AgentSpacesProperties p = PropertiesLoader.flat(Map.of(
                "agentspaces.bind", "127.0.0.1:7500",
                "agentspaces.tick-millis", "200",
                "agentspaces.groups[0].name", "it-fleet",
                "agentspaces.groups[0].founding", "it-fleet-v1",
                "agentspaces.groups[0].seeds[0]", "hq.example:7500",
                "agentspaces.groups[0].spaces[0].name", "tasks",
                "agentspaces.groups[0].spaces[1].name", "findings",
                "agentspaces.groups[0].spaces[1].settle-window-millis", "100",
                "agentspaces.security.profile", "dev-local",
                "agentspaces.security.grants.raft-voter[0]", "peer:abc"));
        assertThat(p.getBind()).isEqualTo("127.0.0.1:7500");
        assertThat(p.getTickMillis()).isEqualTo(200);
        AgentSpacesProperties.Group group = p.getGroups().get(0);
        assertThat(group.getName()).isEqualTo("it-fleet");
        assertThat(group.getFounding()).isEqualTo("it-fleet-v1");
        assertThat(group.getSeeds()).containsExactly("hq.example:7500");
        assertThat(group.getSpaces()).extracting(AgentSpacesProperties.SpaceDef::getName)
                .containsExactly("tasks", "findings");
        assertThat(group.getSpaces().get(1).getSettleWindowMillis()).isEqualTo(100);
        assertThat(group.getSpaces().get(0).getSettleWindowMillis()).isEqualTo(200);
        assertThat(p.getSecurity().getProfile()).isEqualTo("dev-local");
        assertThat(p.getSecurity().getGrants().getRaftVoter()).containsExactly("peer:abc");
        assertThat(p.getCardRefreshMillis()).isEqualTo(300_000);
        assertThat(p.getCapabilities().isVote()).isTrue();
    }

    @Test
    void bindsDurationsInIsoAndShortForms() {
        AgentSpacesProperties p = PropertiesLoader.map(Map.of(
                "identity", Map.of("agent-certificate-ttl", "PT2H"),
                "transport", Map.of("tls", Map.of("crl-refresh", "90s")),
                "groups", List.of(Map.of("name", "g",
                        "content-key-rotation", Map.of("rotate-every", "1d", "writer-grace", "250ms")))));
        assertThat(p.getIdentity().getAgentCertificateTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(p.getTransport().getTls().getCrlRefresh()).isEqualTo(Duration.ofSeconds(90));
        assertThat(p.getGroups().get(0).getContentKeyRotation().getRotateEvery()).isEqualTo(Duration.ofDays(1));
        assertThat(p.getGroups().get(0).getContentKeyRotation().getWriterGrace()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void rejectsUnknownKeysSoTyposSurfaceAtStartup() {
        assertThatThrownBy(() -> PropertiesLoader.map(Map.of("tick-milis", "5"))).hasMessageContaining("tick-milis");
    }

    @Test
    void flatKeysWithGapsOrValueSectionConflictsFailNamingTheKey() {
        assertThatThrownBy(() -> PropertiesLoader.flat(Map.of("agentspaces.groups[1].name", "x")))
                .hasMessageContaining("groups[0]").hasMessageContaining("contiguous");
        assertThatThrownBy(() -> PropertiesLoader.nest(Map.of("a", "1", "a.b", "2")))
                .hasMessageContaining("'a.b'").hasMessageContaining("section");
        assertThatThrownBy(() -> PropertiesLoader.nest(Map.of("a.b", "2", "a[0]", "x")))
                .hasMessageContaining("'a[0]'");
        Props p = Props.of(Map.of("names", "summarizer, pricer,"), "x");
        assertThat(p.strings("names")).containsExactly("summarizer", "pricer");
    }

    @Test
    void anEmptySubtreeIsTheDefaults() {
        AgentSpacesProperties p = PropertiesLoader.map(Map.of());
        assertThat(p.getGroups()).isEmpty();
        assertThat(p.getSecurity().getProfile()).isEqualTo("mtls");
    }

    @Test
    void readsAYamlDocumentAndLeavesThisProjectsSectionsToTheirOwnReaders() {
        String yaml = """
                agentspaces:
                  bind: 127.0.0.1:7600
                  tick-millis: 250
                  groups:
                    - name: research
                      founding: research-v1
                      spaces:
                        - name: tasks
                        - name: conversations
                          settle-window-millis: 100
                  tools:
                    prefix: team_
                    timeout: 20s
                  langchain4j:
                    memory:
                      enabled: true
                """;
        Map<String, Object> tree = PropertiesLoader.tree(new StringReader(yaml));
        Map<String, Object> agentspaces = PropertiesLoader.agentspacesSubtree(tree);
        AgentSpacesProperties p = PropertiesLoader.map(agentspaces);
        assertThat(p.getBind()).isEqualTo("127.0.0.1:7600");
        assertThat(p.getTickMillis()).isEqualTo(250);
        assertThat(p.getGroups().get(0).getSpaces()).extracting(AgentSpacesProperties.SpaceDef::getName)
                .containsExactly("tasks", "conversations");
        assertThat(agentspaces).containsKeys("tools", "langchain4j"); // left for ToolSettings.from and LangChain4jSettings.from
    }

    @Test
    void thePropertyReaderConvertsAndRejectsUnknownKeys() {
        Props p = Props.of(Map.of("timeout", "2m", "max", 3, "on", "true", "names", List.of("a", "b"), "mode", "take"), "x");
        assertThat(p.duration("timeout", Duration.ZERO)).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.integer("max", 0)).isEqualTo(3);
        assertThat(p.bool("on", false)).isTrue();
        assertThat(p.strings("names")).containsExactly("a", "b");
        assertThat(p.enumeration("mode", Mode.class, Mode.AGENT)).isEqualTo(Mode.TAKE);
        assertThat(p.duration("absent", Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
        p.rejectUnknown();
        Props typo = Props.of(Map.of("timout", "2m"), "x");
        typo.duration("timeout", Duration.ZERO);
        assertThatThrownBy(typo::rejectUnknown).hasMessageContaining("timout");
        assertThat(Props.parseDuration("PT5M")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Props.parseDuration("1500")).isEqualTo(Duration.ofMillis(1500));
    }

    enum Mode { TAKE, AGENT }
}
