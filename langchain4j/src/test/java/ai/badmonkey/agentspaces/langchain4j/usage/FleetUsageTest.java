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

import ai.badmonkey.agentspaces.api.space.EntryHandle;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.SpaceListener;
import ai.badmonkey.agentspaces.api.space.Subscription;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.common.id.AgentId;
import ai.badmonkey.agentspaces.common.id.SpaceId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.space.local.LocalSpace;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsageTotal;
import ai.badmonkey.agentspaces.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FleetUsage} over one in-process {@link LocalSpace} on a {@link TestClock}.
 * A {@link LocalSpace} attributes every write to its one peer, so the tests reach
 * it through {@link IssuedView}s, each of which labels its writes with a peer of
 * its own: that is what {@code readAllIssued} reports in a replicated space, and
 * what {@code fleetTotal} keys on.
 */
class FleetUsageTest {

    private static final Instant START = Instant.parse("2026-11-02T10:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private final TestClock clock = TestClock.startingAt(START);
    private final LocalSpace store = LocalSpace.builder("metrics", PeerIdentity.generate().agent("host"))
            .clock(clock).build();
    private final Map<Object, AgentId> issuers = Collections.synchronizedMap(new IdentityHashMap<>());

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void recordCountsPerModelAndPerAgentAndWritesOneEntryPerCall() {
        FleetUsage usage = sink(view("p1"), INTERVAL);
        usage.record(new ModelUsage("m1", "p", 1, 2, 3, "a/x", "peer:z1"));
        usage.record(new ModelUsage("m1", "p", 1, 1, 2, "", "peer:z1"));
        usage.record(new ModelUsage("m2", "p", 5, 5, 10, "a/x", "peer:z1"));

        assertThat(usage.localByModel()).containsEntry("m1", 5L).containsEntry("m2", 10L);
        assertThat(usage.localByAgent()).containsEntry("a/x", 13L).containsEntry("(unattributed)", 2L);
        assertThat(store.readAll(Template.of(ModelUsage.class), 10)).hasSize(3);
    }

    @Test
    void publishWritesOneTotalPerModelStampedWithTheClocksStart() {
        FleetUsage usage = sink(view("p1"), INTERVAL);
        usage.record(new ModelUsage("m1", "p", 1, 2, 3, "a/x", "peer:z1"));
        usage.record(new ModelUsage("m1", "p", 4, 5, 9, "a/x", "peer:z1"));
        usage.record(new ModelUsage("m2", "p", 5, 5, 10, "a/x", "peer:z1"));
        clock.advance(Duration.ofMinutes(5));

        usage.publish();

        List<ModelUsageTotal> totals = store.readAll(Template.of(ModelUsageTotal.class), 10);
        assertThat(totals).hasSize(2);
        assertThat(totals).contains(new ModelUsageTotal("m1", "peer:z1", 5, 7, 12, START.toEpochMilli()),
                new ModelUsageTotal("m2", "peer:z1", 5, 5, 10, START.toEpochMilli()));
    }

    @Test
    void aPublishedTotalLivesThreeIntervals() {
        FleetUsage usage = sink(view("p1"), INTERVAL);
        usage.record(new ModelUsage("m1", "p", 1, 2, 3, "a/x", "peer:z1"));
        usage.publish();

        clock.advance(Duration.ofSeconds(89));
        assertThat(store.readAll(Template.of(ModelUsageTotal.class), 10)).hasSize(1);
        clock.advance(Duration.ofSeconds(2));
        assertThat(store.readAll(Template.of(ModelUsageTotal.class), 10)).isEmpty();
    }

    @Test
    void aPublishedTotalLivesTenSecondsAtLeast() {
        FleetUsage usage = sink(view("p1"), Duration.ofSeconds(1));
        usage.record(new ModelUsage("m1", "p", 1, 2, 3, "a/x", "peer:z1"));
        usage.publish();

        clock.advance(Duration.ofSeconds(9));
        assertThat(store.readAll(Template.of(ModelUsageTotal.class), 10)).hasSize(1);
        clock.advance(Duration.ofSeconds(2));
        assertThat(store.readAll(Template.of(ModelUsageTotal.class), 10)).isEmpty();
    }

    @Test
    void theFleetTotalSumsOneTotalPerIssuer() {
        FleetUsage a = sink(view("p1"), INTERVAL);
        FleetUsage b = sink(view("p2"), INTERVAL);
        a.record(new ModelUsage("m1", "p", 60, 40, 100, "a/x", "peer:a"));
        b.record(new ModelUsage("m1", "p", 150, 100, 250, "a/y", "peer:b"));
        a.publish();
        b.publish();
        a.publish();
        b.publish();

        assertThat(store.readAll(Template.of(ModelUsageTotal.class), 10)).hasSize(4);
        assertThat(a.fleetTotal("m1")).hasValue(350);
        assertThat(b.fleetTotal("m1")).hasValue(350);
        assertThat(a.fleetTotal("m9")).isEmpty();
    }

    @Test
    void aRestartedPeersNewCountReplacesItsPredecessors() {
        Space p1 = view("p1");
        long earlier = START.minus(Duration.ofHours(1)).toEpochMilli();
        p1.write(new ModelUsageTotal("m1", "peer:a", 300, 200, 500, earlier), Lease.of(Duration.ofMinutes(5)));
        p1.write(new ModelUsageTotal("m1", "peer:a", 30, 10, 40, START.toEpochMilli()), Lease.of(Duration.ofMinutes(5)));

        assertThat(sink(view("reader"), INTERVAL).fleetTotal("m1")).hasValue(40);
    }

    @Test
    void withinOneStartTheLargestCountWinsWhateverTheOrder() {
        Space p1 = view("p1");
        long start = START.toEpochMilli();
        p1.write(new ModelUsageTotal("m1", "peer:a", 30, 10, 40, start), Lease.of(Duration.ofMinutes(5)));
        p1.write(new ModelUsageTotal("m1", "peer:a", 60, 30, 90, start), Lease.of(Duration.ofMinutes(5)));
        Space p2 = view("p2");
        p2.write(new ModelUsageTotal("m1", "peer:b", 6, 3, 9, start), Lease.of(Duration.ofMinutes(5)));
        p2.write(new ModelUsageTotal("m1", "peer:b", 3, 1, 4, start), Lease.of(Duration.ofMinutes(5)));

        assertThat(sink(view("reader"), INTERVAL).fleetTotal("m1")).hasValue(99);
    }

    @Test
    void theKeyIsTheSignedIssuerAndNotTheEntrysPeerField() {
        Space p1 = view("p1");
        long start = START.toEpochMilli();
        p1.write(new ModelUsageTotal("m1", "alpha", 30, 10, 40, start), Lease.of(Duration.ofMinutes(5)));
        p1.write(new ModelUsageTotal("m1", "beta", 60, 30, 90, start), Lease.of(Duration.ofMinutes(5)));

        assertThat(sink(view("reader"), INTERVAL).fleetTotal("m1")).hasValue(90);
    }

    @Test
    void anIssuerWhoseLastTotalLapsesLeavesTheSum() {
        FleetUsage a = sink(view("p1"), INTERVAL);
        FleetUsage b = sink(view("p2"), INTERVAL);
        a.record(new ModelUsage("m1", "p", 60, 40, 100, "a/x", "peer:a"));
        b.record(new ModelUsage("m1", "p", 150, 100, 250, "a/y", "peer:b"));
        a.publish();
        clock.advance(Duration.ofSeconds(60));
        b.publish();
        assertThat(a.fleetTotal("m1")).hasValue(350);

        clock.advance(Duration.ofSeconds(31));
        assertThat(a.fleetTotal("m1")).hasValue(250);
        clock.advance(Duration.ofSeconds(60));
        assertThat(a.fleetTotal("m1")).isEmpty();
    }

    @Test
    void aReadThatReachesTheLimitWarnsOncePerSinkAndStillAnswers() {
        Space p1 = view("p1");
        long start = START.toEpochMilli();
        for (int i = 1; i <= FleetUsage.FLEET_READ_LIMIT + 1; i++) {
            p1.write(new ModelUsageTotal("m1", "peer:a", i, 0, i, start), Lease.of(Duration.ofMinutes(5)));
        }
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(FleetUsage.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            FleetUsage reader = sink(view("reader"), INTERVAL);
            assertThat(reader.fleetTotal("m1")).isPresent();
            assertThat(reader.fleetTotal("m1")).isPresent();
            assertThat(reader.fleetTotal("m2")).isEmpty();
            assertThat(sink(view("other"), INTERVAL).fleetTotal("m1")).isPresent();
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).contains("m1", String.valueOf(FleetUsage.FLEET_READ_LIMIT), "undercount");
        });
    }

    @Test
    void aReadBelowTheLimitDoesNotWarn() {
        Space p1 = view("p1");
        p1.write(new ModelUsageTotal("m1", "peer:a", 1, 0, 1, START.toEpochMilli()), Lease.of(Duration.ofMinutes(5)));
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(FleetUsage.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            assertThat(sink(view("reader"), INTERVAL).fleetTotal("m1")).hasValue(1);
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(records).isEmpty();
    }

    @Test
    void withoutAMetricsSpaceTheSinkOnlyCountsLocally() {
        FleetUsage usage = new FleetUsage(null, Duration.ofHours(1), INTERVAL, Clock.fixed(START, ZoneOffset.UTC));
        usage.record(new ModelUsage("m1", "p", 1, 2, 3, "a/x", "peer:z1"));
        usage.publish();

        assertThat(usage.localByModel()).containsEntry("m1", 3L);
        assertThat(usage.fleetTotal("m1")).isEmpty();
        assertThat(store.readAll(Template.of(ModelUsageTotal.class), 10)).isEmpty();
    }

    @Test
    void theDeprecatedPushSumMembersAreGone() {
        assertThat(FleetUsage.class.getConstructors())
                .extracting(c -> List.of(c.getParameterTypes()))
                .containsExactly(List.of(Space.class, Duration.class, Duration.class, Clock.class));
        assertThat(FleetUsage.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("EPOCH_PREFIX");
    }

    private FleetUsage sink(Space metrics, Duration interval) {
        return new FleetUsage(metrics, Duration.ofHours(1), interval, Clock.fixed(clock.instant(), ZoneOffset.UTC));
    }

    private Space view(String peerName) {
        return new IssuedView(store, PeerIdentity.generate().agent(peerName), issuers);
    }

    /**
     * The shared store seen from one peer: writes are remembered under that peer's
     * {@link AgentId}, and {@code readAllIssued} reports them under it, as a
     * replicated space would from the entry's signature.
     */
    private record IssuedView(LocalSpace store, AgentId issuer, Map<Object, AgentId> issuers) implements Space {

        @Override
        public String name() {
            return store.name();
        }

        @Override
        public SpaceId id() {
            return store.id();
        }

        @Override
        public <T> EntryHandle write(T entry, Lease lease) {
            issuers.put(entry, issuer);
            return store.write(entry, lease);
        }

        @Override
        public <T> EntryHandle write(T entry, Lease lease, Map<String, String> tags) {
            issuers.put(entry, issuer);
            return store.write(entry, lease, tags);
        }

        @Override
        public <T> Optional<T> read(Template<T> template) {
            return store.read(template);
        }

        @Override
        public <T> Optional<T> read(Template<T> template, Duration timeout) {
            return store.read(template, timeout);
        }

        @Override
        public <T> List<T> readAll(Template<T> template, int limit) {
            return store.readAll(template, limit);
        }

        @Override
        public <T> List<Issued<T>> readAllIssued(Template<T> template, int limit) {
            return store.readAllIssued(template, limit).stream()
                    .map(issued -> new Issued<>(issued.entry(),
                            issuers.getOrDefault(issued.entry(), issued.issuer()), issued.attestation()))
                    .toList();
        }

        @Override
        public <T> Optional<TakenEntry<T>> take(Template<T> template, Lease takeLease, Duration timeout) {
            return store.take(template, takeLease, timeout);
        }

        @Override
        public void complete(TakenEntry<?> taken) {
            store.complete(taken);
        }

        @Override
        public <R> EntryHandle complete(TakenEntry<?> taken, R result, Lease resultLease) {
            return store.complete(taken, result, resultLease);
        }

        @Override
        public <T> Subscription notify(Template<T> template, SpaceListener<T> listener, Lease lease) {
            return store.notify(template, listener, lease);
        }
    }
}
