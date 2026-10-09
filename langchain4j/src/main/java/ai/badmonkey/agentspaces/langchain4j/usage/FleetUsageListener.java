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
package ai.badmonkey.agentspaces.langchain4j.usage;

import ai.badmonkey.agentspaces.agent.TakeContext;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Objects;

/**
 * Turns every model response's token usage into a {@code ModelUsage} for the
 * sink, attributed to the in-flight take's agent when there is one.
 */
public class FleetUsageListener implements ChatModelListener {

    private final UsageSink sink;
    private final String peer;

    public FleetUsageListener(UsageSink sink, String peer) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.peer = Objects.requireNonNull(peer, "peer");
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        ChatResponse response = context.chatResponse();
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage == null) {
            return;
        }
        String model = response.modelName() != null ? response.modelName()
                : context.chatRequest() != null && context.chatRequest().modelName() != null
                        ? context.chatRequest().modelName() : "unknown";
        String provider = context.modelProvider() == null ? "" : context.modelProvider().name().toLowerCase();
        String agent = TakeContext.current().map(take -> take.agent().localName()).orElse("");
        sink.record(new ModelUsage(model, provider,
                value(usage.inputTokenCount()), value(usage.outputTokenCount()), value(usage.totalTokenCount()),
                agent, peer));
    }

    private static long value(Integer count) {
        return count == null ? 0 : count;
    }
}
