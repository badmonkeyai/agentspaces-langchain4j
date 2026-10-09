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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The scheduler this project's periodic work runs on: the host's own
 * {@link ScheduledExecutorService} when it gives one, otherwise one over
 * virtual threads that the owner closes. The {@link Duration} methods are the
 * shape every participant uses, so a host adapts one type.
 */
public final class FleetScheduler implements AutoCloseable {

    private final ScheduledExecutorService executor;
    private final boolean owned;

    private FleetScheduler(ScheduledExecutorService executor, boolean owned) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.owned = owned;
    }

    /** Over a scheduler the host owns and closes. */
    public static FleetScheduler over(ScheduledExecutorService executor) {
        return new FleetScheduler(executor, false);
    }

    /** A scheduler of this object's own, on virtual threads, closed with it. */
    public static FleetScheduler virtual(String threadNamePrefix) {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(8,
                Thread.ofVirtual().name(threadNamePrefix, 0).factory());
        return new FleetScheduler(executor, true);
    }

    public ScheduledExecutorService executor() {
        return executor;
    }

    public ScheduledFuture<?> schedule(Duration delay, Runnable task) {
        return executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable task) {
        return executor.scheduleAtFixedRate(task, initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Duration initialDelay, Duration delay, Runnable task) {
        return executor.scheduleWithFixedDelay(task, initialDelay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Whether this object owns, and will close, the executor. */
    public boolean owned() {
        return owned;
    }

    @Override
    public void close() {
        if (owned) {
            executor.shutdownNow();
        }
    }
}
