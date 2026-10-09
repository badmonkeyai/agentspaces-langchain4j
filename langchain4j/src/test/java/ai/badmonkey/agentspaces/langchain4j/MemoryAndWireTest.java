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

import ai.badmonkey.agentspaces.common.codec.CborCodec;
import ai.badmonkey.agentspaces.langchain4j.memory.SpaceChatMemoryStore;
import ai.badmonkey.agentspaces.langchain4j.model.WireMapping;
import ai.badmonkey.agentspaces.langchain4j.support.Fleets;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.springai.model.wire.ConversationSnapshot;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryAndWireTest {

    @Test
    void messagesRoundTripThroughTheWireWithSpringsConventions() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("You coordinate."),
                UserMessage.from("Plan the offsite"),
                AiMessage.from(ToolExecutionRequest.builder().id("c1").name("fleet_pricer").arguments("{\"a\":1}").build()),
                ToolExecutionResultMessage.from("c1", "fleet_pricer", "{\"total\":28.5}"),
                AiMessage.from("Done."));
        List<WireMessage> wire = WireMapping.toWire(messages);
        assertThat(wire).extracting(WireMessage::role).containsExactly("system", "user", "assistant", "tool", "assistant");
        assertThat(wire.get(2).toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.type()).isEqualTo("function");
            assertThat(call.name()).isEqualTo("fleet_pricer");
        });
        assertThat(wire.get(3).toolResponses()).singleElement().satisfies(r -> assertThat(r.responseData()).contains("28.5"));
        List<ChatMessage> back = WireMapping.fromWire(wire);
        assertThat(back.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) back.get(2)).toolExecutionRequests()).singleElement()
                .satisfies(r -> assertThat(r.arguments()).isEqualTo("{\"a\":1}"));
        assertThat(((ToolExecutionResultMessage) back.get(3)).toolName()).isEqualTo("fleet_pricer");
        assertThat(((UserMessage) back.get(1)).singleText()).isEqualTo("Plan the offsite");
    }

    @Test
    void parametersAndToolsRoundTrip() {
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi"))
                .parameters(ChatRequestParameters.builder().modelName("m").temperature(0.2).maxOutputTokens(100)
                        .toolSpecifications(ToolSpecification.builder().name("t").description("d")
                                .parameters(JsonObjectSchema.builder().addStringProperty("topic").required("topic").build())
                                .build())
                        .build())
                .build();
        var options = WireMapping.toWire(request.parameters());
        assertThat(options.model()).isEqualTo("m");
        assertThat(options.temperature()).isEqualTo(0.2);
        var tools = WireMapping.toolDefinitions(request);
        assertThat(tools).singleElement().satisfies(t -> assertThat(t.inputSchema()).contains("\"topic\""));
        ChatRequest back = WireMapping.fromWire(WireMapping.toWire(request.messages()), options, "m", tools);
        assertThat(back.parameters().maxOutputTokens()).isEqualTo(100);
        assertThat(back.toolSpecifications()).singleElement().satisfies(spec ->
                assertThat(spec.parameters().properties()).containsKey("topic"));
    }

    @Test
    void theSpringGoldenSnapshotReadsAsLangChain4jMessages() throws Exception {
        Properties golden = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/wire-golden.properties")) {
            assertThat(in).as("golden vectors on the test classpath").isNotNull();
            golden.load(in);
        }
        ConversationSnapshot snapshot = CborCodec.defaultCodec().fromBytes(
                HexFormat.of().parseHex(golden.getProperty("ConversationSnapshot")), ConversationSnapshot.class);
        List<ChatMessage> messages = WireMapping.fromWire(snapshot.messages());
        assertThat(messages).singleElement().isInstanceOf(UserMessage.class);
        UserMessage user = (UserMessage) messages.get(0);
        assertThat(WireMapping.textOf(user)).isEqualTo("Plan the offsite");
        assertThat(user.contents()).hasSize(2); // the text and the golden image
        assertThat(WireMapping.toWire(user).media()).singleElement()
                .satisfies(m -> assertThat(m.mimeType()).isEqualTo("image/png"));
    }

    @Test
    @Timeout(120)
    void theStoreKeepsOneSnapshotPerConversationAndAConversationResumesOnAnotherPeer() {
        int seedPort = Fleets.freePort();
        try (FleetRuntime first = Fleets.start(seedPort, 0, "conversations");
             FleetRuntime second = Fleets.start(Fleets.freePort(), seedPort, "conversations")) {
            LangChain4jFleet fleet = LangChain4jFleet.builder(first)
                    .memory(LangChain4jSettings.Memory.on().withConversationId(LangChain4jSettings.ConversationIdMode.EXPLICIT))
                    .build();
            SpaceChatMemoryStore store = fleet.memoryStore();
            store.updateMessages("conv-1", List.of(UserMessage.from("one")));
            store.updateMessages("conv-1", List.of(UserMessage.from("one"), AiMessage.from("two")));
            assertThat(store.getMessages("conv-1")).hasSize(2);
            assertThat(store.latest("conv-1")).hasValueSatisfying(s -> assertThat(s.version()).isEqualTo(2));
            assertThat(first.context().group().space("conversations")
                    .readAll(ai.badmonkey.agentspaces.api.space.Template.of(ConversationSnapshot.class), 100)).hasSize(1);
            ChatMemory memory = fleet.memory().get("conv-1");
            assertThat(memory.messages()).hasSize(2);
            memory.add(UserMessage.from("three"));
            assertThat(store.getMessages("conv-1")).hasSize(3);

            // The second peer resumes the conversation once the snapshot replicates.
            LangChain4jFleet other = LangChain4jFleet.builder(second)
                    .memory(LangChain4jSettings.Memory.on().withConversationId(LangChain4jSettings.ConversationIdMode.EXPLICIT))
                    .build();
            assertThat(Fleets.await(java.time.Duration.ofSeconds(30),
                    () -> other.memoryStore().getMessages("conv-1").size() == 3)).isTrue();

            store.deleteMessages("conv-1");
            assertThat(store.getMessages("conv-1")).isEmpty();
        }
    }
}
