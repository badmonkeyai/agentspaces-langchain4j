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
package ai.badmonkey.agentspaces.tools;

import ai.badmonkey.agentspaces.common.id.AgentId;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.runtime.FleetScheduler;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FleetToolCatalogTest {

    public record Brief(String topic, int sentences, Optional<String> tone, List<String> tags, Level level, Inner inner) {
    }

    public enum Level { LOW, HIGH }

    public record Inner(boolean flag, double score) {
    }

    public record Summary(String text) {
    }

    /** A settable clock, so the refresh interval is deterministic. */
    static final class TickingClock extends Clock {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static FleetAction action(String name, String agent, boolean attested, Object result) {
        PeerId peer = PeerIdentity.generate().peerId();
        AgentId agentId = new AgentId(peer, agent);
        return new FleetAction() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Summarizes a topic";
            }

            @Override
            public List<String> goals() {
                return List.of("summarize");
            }

            @Override
            public AgentId agent() {
                return agentId;
            }

            @Override
            public PeerId issuer() {
                return peer;
            }

            @Override
            public boolean attested() {
                return attested;
            }

            @Override
            public Class<?> inputType() {
                return Brief.class;
            }

            @Override
            public Class<?> outputType() {
                return Summary.class;
            }

            @Override
            public Optional<Object> invoke(Object input, Duration timeout) {
                return Optional.ofNullable(result);
            }
        };
    }

    @Test
    void namesFiltersDeduplicatesCapsAndTellsListeners() {
        List<FleetAction> actions = new ArrayList<>(List.of(
                action("summarizer_Brief", "summarizer", true, new Summary("ok")),
                action("summarizer_Brief", "summarizer-2", true, new Summary("ok")),
                action("excluded_Brief", "excluded", true, new Summary("ok")),
                action("bare_Brief", "bare", false, new Summary("ok"))));
        ToolSettings settings = ToolSettings.builder().excludeAgents(List.of("excluded")).requireAttested(true)
                .refreshInterval(Duration.ofSeconds(5)).max(2).build();
        TickingClock clock = new TickingClock();
        FleetToolCatalog catalog = new FleetToolCatalog(() -> actions, settings, settings.filter(), settings.naming(),
                null, clock);
        List<FleetToolsChangedEvent> events = new ArrayList<>();
        catalog.onChange(e -> {
            throw new IllegalStateException("a failing listener must not stop the others");
        });
        FleetToolCatalog.Handle handle = catalog.onChange(events::add);

        assertThat(catalog.current()).extracting(FleetTool::name)
                .containsExactly("fleet_summarizer_Brief", "fleet_summarizer_Brief_2");
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.added()).containsExactlyInAnyOrder("fleet_summarizer_Brief", "fleet_summarizer_Brief_2");
            assertThat(e.removed()).isEmpty();
        });
        FleetTool first = catalog.current().get(0);
        assertThat(first.description()).contains("Summarizes a topic").contains("Goals: summarize")
                .contains("Takes a Brief and returns a Summary");

        // Within the interval the snapshot stands; after it, the change shows.
        actions.remove(1);
        assertThat(catalog.current()).hasSize(2);
        clock.now = clock.now.plusSeconds(6);
        assertThat(catalog.current()).extracting(FleetTool::name).containsExactly("fleet_summarizer_Brief");
        assertThat(events).hasSize(2);
        assertThat(events.get(1).removed()).containsExactly("fleet_summarizer_Brief_2");

        handle.close();
        clock.now = clock.now.plusSeconds(6);
        actions.clear();
        catalog.current();
        assertThat(events).hasSize(2);
    }

    @Test
    void theSchemaCoversRecordsEnumsListsOptionalsAndNesting() {
        Map<String, Object> schema = RecordSchemas.of(Brief.class);
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("topic", "sentences", "tone", "tags", "level", "inner");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("topic", "sentences", "level", "inner").doesNotContain("tone");
        assertThat(properties.get("level").toString()).contains("LOW").contains("HIGH");
        assertThat(properties.get("tags").toString()).contains("array");
        assertThat(properties.get("inner").toString()).contains("flag").contains("score");
    }

    @Test
    void theInvocationParsesStrictlyAndRendersTimeouts() {
        ToolInvocation invocation = new ToolInvocation(Duration.ofSeconds(1), ToolInvocation.OnTimeout.RESULT);
        FleetAction ok = action("summarizer_Brief", "summarizer", true, new Summary("done"));
        ToolInvocation.Outcome outcome = invocation.call(ok,
                "{\"topic\":\"offsite\",\"sentences\":2,\"tags\":[],\"level\":\"LOW\",\"inner\":{\"flag\":true,\"score\":0.5}}");
        assertThat(outcome.error()).isFalse();
        assertThat(outcome.text()).contains("done");

        assertThatThrownBy(() -> invocation.call(ok, "{\"topic\":\"x\"}"))
                .isInstanceOf(ToolInvocation.ToolInvocationException.class).hasMessageContaining("missing field(s)");
        assertThatThrownBy(() -> invocation.call(ok, "{\"topic\":\"x\",\"sentences\":1,\"tags\":[],\"level\":\"LOW\","
                + "\"inner\":{\"flag\":true,\"score\":0.5},\"extra\":1}"))
                .isInstanceOf(ToolInvocation.ToolInvocationException.class).hasMessageContaining("extra");

        FleetAction silent = action("summarizer_Brief", "summarizer", true, null);
        ToolInvocation.Outcome timeout = invocation.call(silent,
                "{\"topic\":\"x\",\"sentences\":1,\"tags\":[],\"level\":\"LOW\",\"inner\":{\"flag\":true,\"score\":0.5}}");
        assertThat(timeout.error()).isTrue();
        assertThat(timeout.text()).contains("no Summary");
        ToolInvocation throwing = new ToolInvocation(Duration.ofSeconds(1), ToolInvocation.OnTimeout.THROW);
        assertThatThrownBy(() -> throwing.call(silent,
                "{\"topic\":\"x\",\"sentences\":1,\"tags\":[],\"level\":\"LOW\",\"inner\":{\"flag\":true,\"score\":0.5}}"))
                .isInstanceOf(ToolInvocation.ToolInvocationException.class);
    }

    @Test
    void settingsBindFromTheSharedKeys() {
        ToolSettings settings = ToolSettings.from(Map.of("prefix", "team_", "include-agents", List.of("a"),
                "require-attested", "true", "timeout", "20s", "on-timeout", "throw", "refresh-interval", "2s", "max", 8));
        assertThat(settings.prefix()).isEqualTo("team_");
        assertThat(settings.includeAgents()).containsExactly("a");
        assertThat(settings.requireAttested()).isTrue();
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(settings.onTimeout()).isEqualTo(ToolInvocation.OnTimeout.THROW);
        assertThat(settings.max()).isEqualTo(8);
        assertThatThrownBy(() -> ToolSettings.from(Map.of("prefx", "x"))).hasMessageContaining("prefx");
        assertThat(ToolSettings.defaults().naming().toolName(action("summarizer_Brief", "s", true, null)))
                .isEqualTo("fleet_summarizer_Brief");
    }

    @Test
    void theSchedulerClosesOnlyWhatItOwns() throws Exception {
        FleetScheduler owned = FleetScheduler.virtual("t-");
        AtomicReference<String> ran = new AtomicReference<>();
        owned.schedule(Duration.ofMillis(10), () -> ran.set(Thread.currentThread().getName())).get();
        assertThat(ran.get()).startsWith("t-");
        owned.close();
        assertThat(owned.executor().isShutdown()).isTrue();
        var mine = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        FleetScheduler over = FleetScheduler.over(mine);
        over.close();
        assertThat(mine.isShutdown()).isFalse();
        mine.shutdownNow();
    }
}
