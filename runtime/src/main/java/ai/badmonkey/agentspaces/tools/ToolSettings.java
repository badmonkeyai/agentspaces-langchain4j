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

import ai.badmonkey.agentspaces.runtime.Props;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How the tool catalogue is built: which cards become tools, their names,
 * the invocation timeout and what a timeout does, the refresh cadence, and
 * the most tools offered. Bound from the {@code agentspaces.tools} subtree
 * with the Micronaut library's key names, or built in code.
 *
 * @param prefix          prepended to every tool name
 * @param includeAgents   agents (local or encoded names) whose cards become tools; all when empty
 * @param excludeAgents   agents never offered
 * @param requireAttested only cards carrying the agent's own certified key
 * @param timeout         how long an invocation awaits its result
 * @param onTimeout       a result text the model can act on, or a failure
 * @param refreshInterval how often the catalogue is rebuilt
 * @param max             the most tools offered per request
 */
public record ToolSettings(String prefix, List<String> includeAgents, List<String> excludeAgents,
                           boolean requireAttested, Duration timeout, ToolInvocation.OnTimeout onTimeout,
                           Duration refreshInterval, int max) {

    public ToolSettings {
        prefix = prefix == null ? "" : prefix;
        includeAgents = List.copyOf(includeAgents);
        excludeAgents = List.copyOf(excludeAgents);
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(onTimeout, "onTimeout");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        if (max < 1) {
            throw new IllegalArgumentException("max must be positive");
        }
    }

    public static ToolSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** From the {@code agentspaces.tools} subtree; unknown keys fail. */
    public static ToolSettings from(Map<String, ?> tree) {
        Props p = Props.of(tree, "agentspaces.tools");
        ToolSettings settings = builder()
                .prefix(p.string("prefix", "fleet_"))
                .includeAgents(p.strings("include-agents"))
                .excludeAgents(p.strings("exclude-agents"))
                .requireAttested(p.bool("require-attested", false))
                .timeout(p.duration("timeout", Duration.ofSeconds(30)))
                .onTimeout(p.enumeration("on-timeout", ToolInvocation.OnTimeout.class, ToolInvocation.OnTimeout.RESULT))
                .refreshInterval(p.duration("refresh-interval", Duration.ofSeconds(5)))
                .max(p.integer("max", 64))
                .build();
        p.bool("enabled", true); // the Micronaut library's switch; accepted, ignored here
        p.rejectUnknown();
        return settings;
    }

    /** The filter these settings describe. */
    public FleetToolFilter filter() {
        return FleetToolFilter.of(includeAgents, excludeAgents, requireAttested);
    }

    /** The naming these settings describe. */
    public ToolNamingStrategy naming() {
        return ToolNamingStrategy.prefixed(prefix);
    }

    public static final class Builder {
        private String prefix = "fleet_";
        private List<String> includeAgents = List.of();
        private List<String> excludeAgents = List.of();
        private boolean requireAttested;
        private Duration timeout = Duration.ofSeconds(30);
        private ToolInvocation.OnTimeout onTimeout = ToolInvocation.OnTimeout.RESULT;
        private Duration refreshInterval = Duration.ofSeconds(5);
        private int max = 64;

        private Builder() {
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder includeAgents(List<String> includeAgents) {
            this.includeAgents = includeAgents;
            return this;
        }

        public Builder excludeAgents(List<String> excludeAgents) {
            this.excludeAgents = excludeAgents;
            return this;
        }

        public Builder requireAttested(boolean requireAttested) {
            this.requireAttested = requireAttested;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder onTimeout(ToolInvocation.OnTimeout onTimeout) {
            this.onTimeout = onTimeout;
            return this;
        }

        public Builder refreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
            return this;
        }

        public Builder max(int max) {
            this.max = max;
            return this;
        }

        public ToolSettings build() {
            return new ToolSettings(prefix, includeAgents, excludeAgents, requireAttested, timeout, onTimeout,
                    refreshInterval, max);
        }
    }
}
