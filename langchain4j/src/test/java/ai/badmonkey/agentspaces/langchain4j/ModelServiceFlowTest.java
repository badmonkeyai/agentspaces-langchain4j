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
package ai.badmonkey.agentspaces.langchain4j;

import ai.badmonkey.agentspaces.langchain4j.model.FleetChatModel;
import ai.badmonkey.agentspaces.langchain4j.model.FleetStreamingChatModel;
import ai.badmonkey.agentspaces.langchain4j.model.ModelCatalog;
import ai.badmonkey.agentspaces.langchain4j.model.ModelServer;
import ai.badmonkey.agentspaces.langchain4j.support.Fleets;
import ai.badmonkey.agentspaces.langchain4j.support.ScriptedChatModel;
import ai.badmonkey.agentspaces.langchain4j.support.ScriptedStreamingChatModel;
import ai.badmonkey.agentspaces.langchain4j.support.Scripts;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelServiceFlowTest {

    @Test
    @Timeout(180)
    void aKeylessPeerCallsAndStreamsThroughAServerPeer() throws Exception {
        int seedPort = Fleets.freePort();
        try (FleetRuntime server = Fleets.start(seedPort, 0, "model-requests");
             FleetRuntime client = Fleets.start(Fleets.freePort(), seedPort, "model-requests")) {
            ScriptedChatModel scripted = Scripts.server();
            LangChain4jFleet serving = LangChain4jFleet.builder(server)
                    .modelServer(LangChain4jSettings.ModelServer.on()).build();
            ModelServer modelServer = serving.modelServer(
                    ModelCatalog.of("scripted", scripted, new ScriptedStreamingChatModel(scripted)));
            assertThat(modelServer.isRunning()).isTrue();

            LangChain4jFleet calling = LangChain4jFleet.builder(client)
                    .modelClient(LangChain4jSettings.ModelClient.of("scripted")).build();
            FleetChatModel model = calling.chatModel();
            FleetStreamingChatModel streaming = calling.streamingChatModel();

            ChatResponse response = model.chat(ChatRequest.builder().messages(UserMessage.from("hello")).build());
            assertThat(response.aiMessage().text()).isEqualTo("served by the fleet");
            assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(15);
            assertThat(response.modelName()).isEqualTo("scripted");
            assertThat(modelServer.served()).isEqualTo(1);

            List<String> partials = new CopyOnWriteArrayList<>();
            CompletableFuture<ChatResponse> complete = new CompletableFuture<>();
            streaming.chat(ChatRequest.builder().messages(UserMessage.from("stream it")).build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            partials.add(partialResponse);
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse completeResponse) {
                            complete.complete(completeResponse);
                        }

                        @Override
                        public void onError(Throwable error) {
                            complete.completeExceptionally(error);
                        }
                    });
            ChatResponse streamed = complete.get(60, TimeUnit.SECONDS);
            assertThat(String.join("", partials)).isEqualTo("served by the fleet");
            assertThat(partials.size()).isGreaterThan(1);
            assertThat(streamed.aiMessage().text()).isEqualTo("served by the fleet");
            assertThat(streamed.tokenUsage().totalTokenCount()).isEqualTo(15);
        }
    }

    @Test
    void theModelServiceIsOffByDefault() {
        try (FleetRuntime runtime = Fleets.start(Fleets.freePort(), 0)) {
            LangChain4jFleet fleet = LangChain4jFleet.builder(runtime).build();
            assertThatThrownBy(fleet::chatModel).hasMessageContaining("model client");
            assertThat(fleet.participants()).isEmpty();
        }
    }
}
