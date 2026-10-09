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

import ai.badmonkey.agentspaces.runtime.FleetParticipant;
import ai.badmonkey.agentspaces.runtime.FleetScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/** Publishes this peer's usage into the fleet aggregate on the publish interval. */
public class FleetUsagePublisher implements FleetParticipant {

    private final FleetUsage usage;
    private final FleetScheduler scheduler;
    private final Duration interval;
    private ScheduledFuture<?> task;

    public FleetUsagePublisher(FleetUsage usage, FleetScheduler scheduler, Duration interval) {
        this.usage = Objects.requireNonNull(usage, "usage");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    @Override
    public synchronized void start() {
        if (task == null) {
            task = scheduler.scheduleAtFixedRate(interval, interval, usage::publish);
        }
    }

    @Override
    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
