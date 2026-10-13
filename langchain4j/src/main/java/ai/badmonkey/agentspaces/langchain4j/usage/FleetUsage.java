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
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsageTotal;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

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
    private final Map<String, LongAdder> promptByModel = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> completionByModel = new ConcurrentHashMap<>();
    private final long sinceMillis = System.currentTimeMillis();
    private volatile String peer = "";

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
        promptByModel.computeIfAbsent(usage.model(), k -> new LongAdder()).add(usage.promptTokens());
        completionByModel.computeIfAbsent(usage.model(), k -> new LongAdder()).add(usage.completionTokens());
        if (usage.peer() != null && !usage.peer().isBlank()) {
            peer = usage.peer();
        }
        byAgent.computeIfAbsent(usage.agent() == null || usage.agent().isEmpty() ? "(unattributed)" : usage.agent(),
                k -> new LongAdder()).add(usage.totalTokens());
        if (metrics != null) {
            metrics.write(usage, entryLease);
        }
    }

    /** Starts the current window's aggregate per model with this peer's totals. */
    /**
     * Republishes this peer's running total per model into the metrics space as a
     * {@link ModelUsageTotal} under a lease of three publish intervals: a per-peer counter that
     * anti-entropy carries like any entry, so no gossip protocol runs for usage (PERF2 §3.5).
     */
    public void publish() {
        if (metrics == null) {
            return;
        }
        Duration retention = interval.multipliedBy(3);
        Lease lease = Lease.of(retention.compareTo(Duration.ofSeconds(10)) < 0 ? Duration.ofSeconds(10) : retention);
        byModel.forEach((model, total) -> metrics.write(new ModelUsageTotal(model, peer,
                promptByModel.getOrDefault(model, new LongAdder()).sum(),
                completionByModel.getOrDefault(model, new LongAdder()).sum(),
                total.sum(), sinceMillis), lease));
    }

    /** The fleet's estimated total for a model, from the newest window that has one. */
    /** The fleet's total for a model: the newest {@link ModelUsageTotal} per issuing peer, summed. */
    public OptionalDouble fleetTotal(String model) {
        if (metrics == null) {
            return OptionalDouble.empty();
        }
        Map<String, Long> newestByIssuer = new HashMap<>();
        for (Space.Issued<ModelUsageTotal> issued : metrics.readAllIssued(
                Template.of(ModelUsageTotal.class).where("model", eq(model)), 1000)) {
            newestByIssuer.merge(issued.issuer().peer().value(), issued.entry().totalTokens(), Math::max);
        }
        if (newestByIssuer.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(newestByIssuer.values().stream().mapToLong(Long::longValue).sum());
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
