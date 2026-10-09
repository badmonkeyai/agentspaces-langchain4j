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
package ai.badmonkey.agentspaces.langchain4j.worker;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A model with listeners added: for a provider model built without
 * {@code listeners(...)}, this wraps it so the take-lease and usage listeners
 * fire around every call. A listener the delegate already carries is not
 * added again, so wrapping a model that was also built with the fleet's
 * listeners fires each once. Capabilities, the provider, and asynchronous
 * calls are the delegate's.
 */
public final class ListeningChatModel implements ChatModel {

    private final ChatModel delegate;
    private final List<ChatModelListener> listeners;

    public ListeningChatModel(ChatModel delegate, List<ChatModelListener> listeners) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        List<ChatModelListener> own = new ArrayList<>();
        List<ChatModelListener> present = delegate.listeners() == null ? List.of() : delegate.listeners();
        for (ChatModelListener listener : listeners) {
            if (present.stream().noneMatch(p -> p == listener)) {
                own.add(listener);
            }
        }
        this.listeners = List.copyOf(own);
    }

    /** The wrapped model. */
    public ChatModel delegate() {
        return delegate;
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        return delegate.chat(chatRequest);
    }

    @Override
    public CompletableFuture<ChatResponse> doChatAsync(ChatRequest chatRequest) {
        return delegate.chatAsync(chatRequest);
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }
}
