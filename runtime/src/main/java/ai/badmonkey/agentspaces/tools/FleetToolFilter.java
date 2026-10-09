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

import java.util.List;
import java.util.Set;

/** Decides which fleet actions become tools; the default is the include and exclude lists. */
@FunctionalInterface
public interface FleetToolFilter {

    boolean include(FleetAction action);

    static FleetToolFilter of(List<String> include, List<String> exclude, boolean requireAttested) {
        Set<String> in = Set.copyOf(include);
        Set<String> out = Set.copyOf(exclude);
        return action -> {
            String local = action.agent().localName();
            String full = action.agent().encoded();
            if (out.contains(local) || out.contains(full)) {
                return false;
            }
            if (!in.isEmpty() && !in.contains(local) && !in.contains(full)) {
                return false;
            }
            return !requireAttested || action.attested();
        };
    }
}
