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

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.langchain4j.model.WireMapping;
import ai.badmonkey.agentspaces.springai.model.wire.ConversationSnapshot;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;
import static ai.badmonkey.agentspaces.api.space.Matchers.lt;

/**
 * Chat memory in a replicated space: each update writes the conversation's
 * next leased {@code ConversationSnapshot} and retires the older ones, so a
 * task that reappears after a crash resumes on another peer with the
 * conversation it had, and an abandoned conversation leaves the space on its
 * own. The snapshot is the shared wire record, so a Spring AI worker's
 * conversation reads here and vice versa.
 */
public class SpaceChatMemoryStore implements ChatMemoryStore {

    private static final int READ_LIMIT = 10_000;
    private static final Lease RETIRE_LEASE = Lease.of(Duration.ofSeconds(30));

    private final Space space;
    private final Lease snapshotLease;

    public SpaceChatMemoryStore(Space space, Duration ttl) {
        this.space = Objects.requireNonNull(space, "space");
        this.snapshotLease = Lease.of(Objects.requireNonNull(ttl, "ttl"));
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return latest(id(memoryId)).map(snapshot -> WireMapping.fromWire(snapshot.messages())).orElse(List.of());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = id(memoryId);
        long next = latest(id).map(snapshot -> snapshot.version() + 1).orElse(1L);
        space.write(new ConversationSnapshot(id, next, WireMapping.toWire(messages)), snapshotLease);
        retire(id, next);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        retire(id(memoryId), Long.MAX_VALUE);
    }

    /** The newest snapshot of a conversation, if any. */
    public Optional<ConversationSnapshot> latest(String conversationId) {
        return space.readAll(of(conversationId), READ_LIMIT).stream()
                .max(Comparator.comparingLong(ConversationSnapshot::version));
    }

    private void retire(String conversationId, long below) {
        Template<ConversationSnapshot> older = of(conversationId).where("version", lt(below));
        while (true) {
            Optional<TakenEntry<ConversationSnapshot>> taken = space.take(older, RETIRE_LEASE, Duration.ZERO);
            if (taken.isEmpty()) {
                return;
            }
            space.complete(taken.get());
        }
    }

    private static String id(Object memoryId) {
        return String.valueOf(Objects.requireNonNull(memoryId, "memoryId"));
    }

    private static Template<ConversationSnapshot> of(String conversationId) {
        return Template.of(ConversationSnapshot.class).where("conversationId", eq(conversationId));
    }
}
