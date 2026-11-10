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
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsageTotal;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * The default {@link UsageSink}. It keeps this peer's token counts per model and
 * per agent, optionally writes each call as a leased {@link ModelUsage} entry
 * for audit, and on every {@link #publish} writes one {@link ModelUsageTotal}
 * per model into the metrics space with this peer's running total and the
 * instant it started counting, under a lease of three publish intervals (ten
 * seconds at least). Anti-entropy carries that entry to every member like any
 * other, so no gossip protocol runs for usage and any peer reads the fleet's
 * total with no collector.
 *
 * <p>The fleet total for a model ({@link #fleetTotal}) is the sum, over the
 * peers that published a total within the last three publish intervals, of each
 * peer's tokens since it started. A peer that stops publishing leaves the sum
 * when its last entry lapses, and a restarted peer's count begins at zero: the
 * reader keeps, per signed issuer, the entry with the newest start, so the new
 * count replaces the old one on the next read. The figure is operational. Any
 * member that can write the metrics space can publish a total, so use the
 * per-call {@link ModelUsage} entries for billing.
 *
 * <p>The class assumes one sink per peer. Two sinks in one JVM (for example the
 * Spring AI and the LangChain4j starters on one classpath) publish under one
 * issuer, and the newest start wins, so the fleet total then carries only one of
 * them for that model.
 */
public class FleetUsage implements UsageSink {

    /**
     * The most per-peer totals one {@link #fleetTotal} read takes. A read that
     * returns this many entries may have dropped others, so the total may
     * undercount; the sink warns once when that happens.
     */
    static final int FLEET_READ_LIMIT = 1000;

    private static final System.Logger LOG = System.getLogger(FleetUsage.class.getName());

    private final Space metrics;
    private final Lease entryLease;
    private final Duration interval;
    private final long sinceMillis;
    private final Map<String, LongAdder> byModel = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byAgent = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> promptByModel = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> completionByModel = new ConcurrentHashMap<>();
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile String peer = "";

    /**
     * Creates the sink.
     *
     * @param metrics    the space usage entries and per-peer totals go to, or null for local counts only
     * @param entryLease the lease of per-call usage entries
     * @param interval   the publish interval; a total's lease is three of them, ten seconds at least
     * @param clock      the clock that stamps when this peer started counting
     */
    public FleetUsage(Space metrics, Duration entryLease, Duration interval, Clock clock) {
        this.metrics = metrics;
        this.entryLease = Lease.of(Objects.requireNonNull(entryLease, "entryLease"));
        this.interval = Objects.requireNonNull(interval, "interval");
        this.sinceMillis = Objects.requireNonNull(clock, "clock").millis();
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

    /**
     * Republishes this peer's running total per model into the metrics space as a
     * {@link ModelUsageTotal} under a lease of three publish intervals (ten seconds
     * at least): a per-peer counter that anti-entropy carries like any entry, so no
     * gossip protocol runs for usage (PERF2 §3.5). Does nothing without a metrics space.
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

    /**
     * The fleet's total tokens for a model: one {@link ModelUsageTotal} per signed
     * issuer, the one with the newest start and then the largest count, summed.
     * The read takes at most {@link #FLEET_READ_LIMIT} entries and warns once per
     * sink when it reaches that many, because the total may then undercount.
     *
     * @param model the model
     * @return the sum, or empty without a metrics space or before any peer published
     */
    public OptionalDouble fleetTotal(String model) {
        if (metrics == null) {
            return OptionalDouble.empty();
        }
        List<Space.Issued<ModelUsageTotal>> read = metrics.readAllIssued(
                Template.of(ModelUsageTotal.class).where("model", eq(model)), FLEET_READ_LIMIT);
        if (read.size() >= FLEET_READ_LIMIT && warned.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "fleet usage for " + model + " read " + FLEET_READ_LIMIT
                    + " entries, the most one read takes; the total may undercount");
        }
        Map<String, ModelUsageTotal> newestByIssuer = new HashMap<>();
        for (Space.Issued<ModelUsageTotal> issued : read) {
            newestByIssuer.merge(issued.issuer().peer().value(), issued.entry(), FleetUsage::newer);
        }
        if (newestByIssuer.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(newestByIssuer.values().stream().mapToLong(ModelUsageTotal::totalTokens).sum());
    }

    /** This peer's total tokens per model. */
    public Map<String, Long> localByModel() {
        return snapshot(byModel);
    }

    /** This peer's total tokens per agent. */
    public Map<String, Long> localByAgent() {
        return snapshot(byAgent);
    }

    /** The entry from the later start; within one start, the larger count. */
    private static ModelUsageTotal newer(ModelUsageTotal a, ModelUsageTotal b) {
        if (a.sinceMillis() != b.sinceMillis()) {
            return a.sinceMillis() > b.sinceMillis() ? a : b;
        }
        return a.totalTokens() >= b.totalTokens() ? a : b;
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> counts) {
        Map<String, Long> out = new TreeMap<>();
        counts.forEach((key, value) -> out.put(key, value.sum()));
        return out;
    }
}
