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

import ai.badmonkey.agentspaces.agent.remote.RemoteAction;
import ai.badmonkey.agentspaces.common.id.AgentId;
import ai.badmonkey.agentspaces.common.id.PeerId;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One invocable action of a foreign AgentCard, as the tool bridges see it:
 * a stable name, the card's sanitized description and goals, who performs
 * it, the entry types it consumes and produces, and the invocation. The
 * same shape as the Spring AI module's, over the core's {@link RemoteAction}.
 */
public interface FleetAction {

    /** A stable name, {@code <agent>_<InputType>}. */
    String name();

    /** The card's description, stripped of control characters and capped. */
    String description();

    /** The card's goals, sanitized. */
    List<String> goals();

    /** The agent that performs the action. */
    AgentId agent();

    /** The peer that issued the card. */
    PeerId issuer();

    /** Whether the card carries the agent's own certified key. */
    boolean attested();

    /** The entry type the action consumes: the tool's input. */
    Class<?> inputType();

    /** The entry type the action produces: the tool's result. */
    Class<?> outputType();

    /** Writes the input as a task and awaits the correlated result. */
    Optional<Object> invoke(Object input, Duration timeout);

    static FleetAction of(RemoteAction action) {
        Objects.requireNonNull(action, "action");
        return new FleetAction() {
            @Override
            public String name() {
                return action.name();
            }

            @Override
            public String description() {
                return action.description();
            }

            @Override
            public List<String> goals() {
                return action.goals();
            }

            @Override
            public AgentId agent() {
                return action.card().agent();
            }

            @Override
            public PeerId issuer() {
                return action.card().issuer();
            }

            @Override
            public boolean attested() {
                return action.card().attested();
            }

            @Override
            public Class<?> inputType() {
                return action.inputType();
            }

            @Override
            public Class<?> outputType() {
                return action.outputType();
            }

            @Override
            public Optional<Object> invoke(Object input, Duration timeout) {
                return action.invoke(input, timeout);
            }

            @Override
            public String toString() {
                return action.toString();
            }
        };
    }
}
