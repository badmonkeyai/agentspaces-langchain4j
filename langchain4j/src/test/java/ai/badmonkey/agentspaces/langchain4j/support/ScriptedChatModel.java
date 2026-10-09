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
package ai.badmonkey.agentspaces.langchain4j.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A model that answers from a script: each call takes the next step, which
 * may inspect the request (to echo tool results, for instance). Records every
 * request so tests can see what the model was shown. Also streams: the answer
 * text arrives in three partial responses.
 */
public class ScriptedChatModel implements ChatModel {

    private final String modelName;
    private final List<Function<ChatRequest, AiMessage>> steps = new CopyOnWriteArrayList<>();
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    private final List<ChatModelListener> listeners = new ArrayList<>();
    private int next;

    public ScriptedChatModel(String modelName) {
        this.modelName = modelName;
    }

    public ScriptedChatModel then(Function<ChatRequest, AiMessage> step) {
        steps.add(step);
        return this;
    }

    public ScriptedChatModel thenText(String text) {
        return then(request -> AiMessage.from(text));
    }

    public ScriptedChatModel listen(ChatModelListener listener) {
        listeners.add(listener);
        return this;
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized AiMessage step(ChatRequest request) {
        requests.add(request);
        if (steps.isEmpty()) {
            return AiMessage.from("(no script)");
        }
        Function<ChatRequest, AiMessage> step = steps.get(Math.min(next, steps.size() - 1));
        next++;
        return step.apply(request);
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        AiMessage message = step(chatRequest);
        return ChatResponse.builder().aiMessage(message).modelName(modelName)
                .tokenUsage(new TokenUsage(10, 5, 15))
                .finishReason(message.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP)
                .build();
    }

    /** Streams the next step's text in three partial responses. */
    public void stream(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        ChatResponse response = doChat(chatRequest);
        String text = response.aiMessage().text() == null ? "" : response.aiMessage().text();
        int third = Math.max(1, text.length() / 3);
        for (int i = 0; i < text.length(); i += third) {
            handler.onPartialResponse(text.substring(i, Math.min(text.length(), i + third)));
        }
        handler.onCompleteResponse(response);
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().modelName(modelName).build();
    }
}
