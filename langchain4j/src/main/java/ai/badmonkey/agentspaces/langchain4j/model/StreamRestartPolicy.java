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
package ai.badmonkey.agentspaces.langchain4j.model;

import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings.RestartMode;

/** What a stream does when its server dies and another attempt begins. */
@FunctionalInterface
public interface StreamRestartPolicy {

    /** The two outcomes. */
    enum Action {
        /** End the stream with {@link FleetStreamRestartedException}. */
        FAIL,
        /** Emit a restart marker, then the new attempt from its beginning. */
        RESTART
    }

    Action decide(String requestId, int fromAttempt, int toAttempt, String partialText);

    static StreamRestartPolicy of(RestartMode mode) {
        Action action = mode == RestartMode.RESTART ? Action.RESTART : Action.FAIL;
        return (requestId, fromAttempt, toAttempt, partialText) -> action;
    }
}
