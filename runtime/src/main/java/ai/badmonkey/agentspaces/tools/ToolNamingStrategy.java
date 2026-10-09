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

/** Names a tool from its action; the default prefixes and sanitizes. */
@FunctionalInterface
public interface ToolNamingStrategy {

    /** The longest tool name model providers commonly accept. */
    int MAX_LENGTH = 64;

    String toolName(FleetAction action);

    static ToolNamingStrategy prefixed(String prefix) {
        return action -> sanitize(prefix + action.name());
    }

    static String sanitize(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (clean.isEmpty()) {
            clean = "fleet_tool";
        }
        return clean.length() <= MAX_LENGTH ? clean : clean.substring(0, MAX_LENGTH);
    }
}
