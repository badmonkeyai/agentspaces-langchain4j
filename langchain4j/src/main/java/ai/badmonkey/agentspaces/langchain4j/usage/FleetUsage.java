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
package ai.badmonkey.agentspaces.langchain4j.usage;

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Token usage as the fleet sees it: local totals per model and per agent, a
 * push-sum aggregate per model and time window (so any peer reads the fleet's
 * total with no collector), and an optional leased entry per call in a
 * metrics space. The same epochs and record as the Spring AI module, so a
 * mixed fleet sums as one.
 */
public class FleetUsage implements UsageSink {

    public static final String EPOCH_PREFIX = "agentspaces.usage.";

    private final PushSumAggregate aggregate;
    private final Space metrics;
    private final Lease entryLease;
    private final Duration interval;
    private final Clock clock;
    private final Map<String, LongAdder> byModel = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byAgent = new ConcurrentHashMap<>();

    public FleetUsage(PushSumAggregate aggregate, Space metrics, Duration entryLease, Duration interval, Clock clock) {
        this.aggregate = aggregate;
        this.metrics = metrics;
        this.entryLease = Lease.of(Objects.requireNonNull(entryLease, "entryLease"));
        this.interval = Objects.requireNonNull(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void record(ModelUsage usage) {
        byModel.computeIfAbsent(usage.model(), k -> new LongAdder()).add(usage.totalTokens());
        byAgent.computeIfAbsent(usage.agent() == null || usage.agent().isEmpty() ? "(unattributed)" : usage.agent(),
                k -> new LongAdder()).add(usage.totalTokens());
        if (metrics != null) {
            metrics.write(usage, entryLease);
        }
    }

    /** Starts the current window's aggregate per model with this peer's totals. */
    public void publish() {
        if (aggregate == null) {
            return;
        }
        long window = window();
        byModel.forEach((model, total) ->
                aggregate.start(epoch(model, window), PushSumAggregate.Mode.SUM, total.sum()));
    }

    /** The fleet's estimated total for a model, from the newest window that has one. */
    public OptionalDouble fleetTotal(String model) {
        if (aggregate == null) {
            return OptionalDouble.empty();
        }
        long window = window();
        for (long w = window; w >= window - 2; w--) {
            OptionalDouble estimate = aggregate.estimate(epoch(model, w));
            if (estimate.isPresent()) {
                return estimate;
            }
        }
        return OptionalDouble.empty();
    }

    public Map<String, Long> localByModel() {
        return snapshot(byModel);
    }

    public Map<String, Long> localByAgent() {
        return snapshot(byAgent);
    }

    private long window() {
        return clock.millis() / interval.toMillis();
    }

    private static String epoch(String model, long window) {
        return EPOCH_PREFIX + model + "." + window;
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> counts) {
        Map<String, Long> out = new TreeMap<>();
        counts.forEach((key, value) -> out.put(key, value.sum()));
        return out;
    }
}
