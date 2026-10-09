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

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.Objects;

/**
 * A message window over the space store, keyed by the resolver's
 * conversation (the in-flight take by default) or by the memory id the
 * caller passes when the resolver has none.
 */
public class FleetChatMemoryProvider implements ChatMemoryProvider {

    private final SpaceChatMemoryStore store;
    private final ConversationIdResolver resolver;
    private final int maxMessages;

    public FleetChatMemoryProvider(SpaceChatMemoryStore store, ConversationIdResolver resolver, int maxMessages) {
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.maxMessages = maxMessages;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        Object id = resolver.resolve().<Object>map(s -> s).orElse(memoryId);
        return MessageWindowChatMemory.builder().id(id).maxMessages(maxMessages).chatMemoryStore(store).build();
    }
}
