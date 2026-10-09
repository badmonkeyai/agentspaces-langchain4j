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
package ai.badmonkey.agentspaces.examples.langchain4j;

import ai.badmonkey.agentspaces.langchain4j.LangChain4jFleet;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.PropertiesLoader;
import ai.badmonkey.agentspaces.tools.ToolSettings;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.io.InputStream;
import java.util.Map;

/**
 * One peer from one YAML file: the starter's {@code agentspaces.*} keys build
 * the runtime, and this project's {@code tools} and {@code langchain4j}
 * sections build the facade. The worker and the orchestrator differ only in
 * the file they read and the models they bring.
 */
public final class Peers {

    private Peers() {
    }

    /** The YAML document on the classpath. */
    public static Map<String, Object> document(String resource) {
        try (InputStream in = Peers.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no classpath resource " + resource);
            }
            return PropertiesLoader.tree(in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The runtime the document's {@code agentspaces} section describes, started. */
    public static FleetRuntime start(Map<String, Object> document) {
        return FleetRuntime.start(PropertiesLoader.map(PropertiesLoader.agentspacesSubtree(document)));
    }

    /** The facade the document's {@code tools} and {@code langchain4j} sections describe. */
    @SuppressWarnings("unchecked")
    public static LangChain4jFleet fleet(FleetRuntime runtime, Map<String, Object> document) {
        Map<String, Object> agentspaces = PropertiesLoader.agentspacesSubtree(document);
        LangChain4jSettings settings = LangChain4jSettings.from(
                (Map<String, Object>) agentspaces.getOrDefault("langchain4j", Map.of()));
        ToolSettings tools = ToolSettings.from((Map<String, Object>) agentspaces.getOrDefault("tools", Map.of()));
        return LangChain4jFleet.builder(runtime).settings(settings).tools(tools).build();
    }

    /** The worker's own model: OpenAI when the key is set, named by its model name. */
    public static ChatModel openAiModel(LangChain4jFleet fleet, String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName(modelName)
                .listeners(fleet.listeners())      // the take-lease and usage listeners
                .build();
    }

    public static StreamingChatModel openAiStreamingModel(String modelName) {
        return OpenAiStreamingChatModel.builder().apiKey(System.getenv("OPENAI_API_KEY")).modelName(modelName).build();
    }

    /** The model name a model reports, or the given default. */
    public static String modelName(ChatModel model, String fallback) {
        ChatRequestParameters parameters = model.defaultRequestParameters();
        return parameters != null && parameters.modelName() != null ? parameters.modelName() : fallback;
    }
}
