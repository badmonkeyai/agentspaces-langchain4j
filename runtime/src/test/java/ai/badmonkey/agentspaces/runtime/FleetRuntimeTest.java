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

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.agent.remote.RemoteActions;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.common.id.AgentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FleetRuntimeTest {

    public record Brief(String topic, int sentences) {
    }

    public record Summary(String topic, String text) {
    }

    @AgentSpec(name = "summarizer", description = "Summarizes a topic into a short briefing", goals = {"summarize"})
    public static class Summarizer {
        @SpaceTake(space = "tasks", pollTimeout = "PT0.2S")
        public Summary summarize(Brief brief) {
            return new Summary(brief.topic(), "summary of " + brief.topic() + " in " + brief.sentences() + " sentences");
        }
    }

    @Test
    @Timeout(120)
    void twoRuntimesJoinOneGroupAndABoundWorkerServesTheOther() {
        int seedPort = Fleets.freePort();
        FleetRuntime worker = Fleets.start(seedPort, 0);
        FleetRuntime client = Fleets.start(Fleets.freePort(), seedPort);
        try {
            assertThat(worker.isRunning()).isTrue();
            assertThat(worker.identity().peerId()).isNotEqualTo(client.identity().peerId());
            worker.bind(new Summarizer());

            FleetContext fleet = client.context();
            assertThat(fleet.groupName()).isEqualTo("it-fleet");
            assertThat(fleet.group().spaceNames()).contains("tasks", "findings", "data");
            assertThat(Fleets.await(Duration.ofSeconds(30), () -> !RemoteActions.over(fleet.group(), fleet.peerId())
                    .available().isEmpty())).as("the worker's card reaches the client").isTrue();

            Space tasks = fleet.group().space("tasks");
            tasks.write(new Brief("offsite", 2), Lease.of(Duration.ofMinutes(5)));
            assertThat(Fleets.await(Duration.ofSeconds(30), () ->
                    !tasks.readAll(Template.of(Summary.class), 10).isEmpty())).isTrue();
            assertThat(tasks.readAll(Template.of(Summary.class), 10)).singleElement()
                    .satisfies(s -> assertThat(s.text()).isEqualTo("summary of offsite in 2 sentences"));

            assertThatThrownBy(() -> client.context("other")).hasMessageContaining("other");
            assertThatThrownBy(() -> fleet.requiredSpace("missing", "x.space")).hasMessageContaining("name: missing");
        } finally {
            client.close();
            worker.close();
        }
        assertThat(worker.isRunning()).isFalse();
        assertThat(worker.scheduler().executor().isShutdown()).isTrue();
    }

    @Test
    @Timeout(60)
    void participantsStartAfterTheNodeAndStopInReverse() {
        List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        FleetParticipant first = participant("first", log);
        FleetParticipant second = participant("second", log);
        try (FleetRuntime runtime = FleetRuntime.builder(PropertiesLoader.flat(Fleets.fleet(Fleets.freePort(), 0)))
                .participant(first).start()) {
            runtime.participant(second);
            assertThat(log).containsExactly("first start", "second start");
            runtime.refresh();
            assertThat(log).containsExactly("first start", "second start", "first refresh", "second refresh");
        }
        assertThat(log).endsWith("second stop", "first stop");
    }

    @Test
    @Timeout(60)
    void thePropagatedTakeIsVisibleAndRenewableOnAnotherThread() throws Exception {
        try (FleetRuntime runtime = Fleets.start(Fleets.freePort(), 0)) {
            Space tasks = runtime.context().group().space("tasks");
            tasks.write(new Brief("lease", 1), Lease.of(Duration.ofMinutes(5)));
            Duration lease = Duration.ofSeconds(1);
            TakenEntry<Brief> taken = tasks.take(Template.of(Brief.class), Lease.of(lease), Duration.ofSeconds(5)).orElseThrow();
            TakeContext take = new TakeContext(taken, lease, new AgentId(runtime.identity().peerId(), "w"), "tasks");
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                TakeContext.bind(take);
                AtomicBoolean seenUnwrapped = new AtomicBoolean(true);
                pool.submit(() -> seenUnwrapped.set(TakeContext.current().isPresent())).get();
                assertThat(seenUnwrapped).isFalse();

                Optional<TakeContext> seen = pool.submit(TakePropagation.wrap(TakeContext::current)).get();
                assertThat(seen).isPresent();

                ExecutorService propagating = TakePropagation.executor(pool);
                propagating.submit(() -> {
                    // Renew from the other thread for longer than the lease.
                    for (int i = 0; i < 5; i++) {
                        TakeContext.current().orElseThrow().renew();
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }).get();
                assertThat(TakeContext.current()).as("the submitting thread keeps its own binding").isPresent();

                // Run inline (a caller-runs executor): the thread's own take is back afterwards.
                TakePropagation.wrap((Runnable) () -> assertThat(TakeContext.current()).isPresent()).run();
                assertThat(TakeContext.current()).as("the inline run restored the binding").contains(take);
                TakeContext.unbind();
                TakePropagation.wrap((Runnable) () -> { }).run();
                assertThat(TakeContext.current()).as("nothing was bound, nothing is bound").isEmpty();
                TakeContext.bind(take);
                assertThat(tasks.take(Template.of(Brief.class), Lease.of(lease), Duration.ofMillis(300)))
                        .as("still held after 2.5s on a 1s lease").isEmpty();
                tasks.complete(taken);
            } finally {
                TakeContext.unbind();
                pool.shutdownNow();
            }
        }
    }

    private static FleetParticipant participant(String name, List<String> log) {
        return new FleetParticipant() {
            @Override
            public void start() {
                log.add(name + " start");
            }

            @Override
            public void refresh() {
                log.add(name + " refresh");
            }

            @Override
            public void stop() {
                log.add(name + " stop");
            }
        };
    }
}
