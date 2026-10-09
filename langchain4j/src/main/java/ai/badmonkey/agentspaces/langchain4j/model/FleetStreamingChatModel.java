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

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Objects;

/**
 * The streaming face of {@link FleetChatModel}: LangChain4j keeps
 * {@code ChatModel} and {@code StreamingChatModel} as separate types, so a
 * service that returns {@code TokenStream} receives this bean.
 */
public class FleetStreamingChatModel implements StreamingChatModel {

    private final FleetChatModel model;

    public FleetStreamingChatModel(FleetChatModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        model.stream(chatRequest, handler);
    }

    @Override
    public List<ChatModelListener> listeners() {
        return model.listeners();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return model.defaultRequestParameters();
    }
}
