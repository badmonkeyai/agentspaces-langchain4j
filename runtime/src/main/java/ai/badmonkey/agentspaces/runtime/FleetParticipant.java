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

/**
 * Something that runs with the fabric: started by {@link FleetRuntime}
 * after annotated beans are enrolled and the node ticks, refreshed on the
 * card-refresh cadence, and stopped in reverse order before the node closes.
 * Participants start in the order they were added.
 */
public interface FleetParticipant {

    /** Starts; called once, when the runtime starts. */
    void start();

    /** Runs on every card-refresh tick; exceptions are logged, never fatal. */
    default void refresh() {
    }

    /** Stops; called once, on shutdown. */
    void stop();
}
