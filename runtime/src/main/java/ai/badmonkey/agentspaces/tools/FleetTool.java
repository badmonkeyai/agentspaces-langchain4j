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

import java.util.Map;
import java.util.Objects;

/**
 * A fleet action under its tool name, with the description and input schema
 * both bridges present to a model.
 *
 * @param name        the tool name (prefixed, sanitized, unique)
 * @param description what the model reads
 * @param inputSchema the JSON Schema of the input record, as a map
 * @param action      the action behind it
 */
public record FleetTool(String name, String description, Map<String, Object> inputSchema, FleetAction action) {

    public FleetTool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        inputSchema = Map.copyOf(inputSchema);
        Objects.requireNonNull(action, "action");
    }

    /** The description a model reads: the card's text, goals, and the types. */
    public static String describe(FleetAction action) {
        StringBuilder text = new StringBuilder();
        String description = action.description();
        if (description != null && !description.isBlank()) {
            text.append(description.strip());
            if (!description.strip().endsWith(".")) {
                text.append('.');
            }
            text.append(' ');
        }
        if (!action.goals().isEmpty()) {
            text.append("Goals: ").append(String.join("; ", action.goals())).append(". ");
        }
        text.append("Takes a ").append(action.inputType().getSimpleName())
                .append(" and returns a ").append(action.outputType().getSimpleName())
                .append(". The fleet routes the task by type: an agent on another peer that takes ")
                .append(action.inputType().getSimpleName()).append(" (advertised by '")
                .append(action.agent().localName()).append("') performs it.");
        return text.toString();
    }
}
