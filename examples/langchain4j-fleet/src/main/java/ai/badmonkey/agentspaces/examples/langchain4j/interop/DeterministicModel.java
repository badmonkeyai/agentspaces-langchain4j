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
package ai.badmonkey.agentspaces.examples.langchain4j.interop;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

/**
 * A deterministic LangChain4j model for runs without a provider key: it
 * answers with a fixed prefix and the last user text, and streams the same
 * answer in three pieces. The worker uses it to "research" a task and serves
 * it to the fleet, so a peer in another JVM, or on another framework, can
 * call it and see which JVM answered.
 */
public final class DeterministicModel implements ChatModel {

    private final String name;
    private final String prefix;

    public DeterministicModel(String name, String prefix) {
        this.name = name;
        this.prefix = prefix;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().modelName(name).build();
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        String text = prefix + lastUserText(chatRequest.messages());
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).modelName(name)
                .tokenUsage(new TokenUsage(8, 4, 12)).finishReason(FinishReason.STOP).build();
    }

    /** The streaming face: the same answer in three pieces (LangChain4j keeps the two interfaces apart). */
    public StreamingChatModel streaming() {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                ChatResponse response = DeterministicModel.this.doChat(chatRequest);
                String text = response.aiMessage().text();
                int third = Math.max(1, text.length() / 3);
                for (int i = 0; i < text.length(); i += third) {
                    handler.onPartialResponse(text.substring(i, Math.min(text.length(), i + third)));
                }
                handler.onCompleteResponse(response);
            }
        };
    }

    private static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.singleText();
            }
        }
        return "";
    }
}
