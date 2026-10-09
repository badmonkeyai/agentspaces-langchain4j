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
package ai.badmonkey.agentspaces.langchain4j.memory;

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings.ConversationIdMode;

import java.util.Optional;

/** Names the conversation a model call belongs to; the default follows the in-flight take. */
@FunctionalInterface
public interface ConversationIdResolver {

    /** The conversation id for the current thread, if one applies. */
    Optional<String> resolve();

    static ConversationIdResolver of(ConversationIdMode mode) {
        return switch (mode) {
            case TAKE -> () -> TakeContext.current().map(take -> take.entryId().toString());
            case AGENT -> () -> TakeContext.current().map(take -> take.agent().localName());
            case EXPLICIT -> Optional::empty;
        };
    }
}
