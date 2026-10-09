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

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The models a server peer serves, by name, with the LangChain4j model
 * behind each. {@link #of(Map, Map)} takes them by served name; the four-
 * argument form names each model from its default request parameters, as
 * a host with named model beans does, with {@code models} narrowing the
 * list and {@code modelBeans} mapping a model name to a bean name.
 */
public interface ModelCatalog {

    List<String> models();

    Optional<ChatModel> model(String model);

    /** The streaming twin of a model, when the peer has one. */
    default Optional<StreamingChatModel> streamingModel(String model) {
        return Optional.empty();
    }

    default String defaultModel() {
        return models().isEmpty() ? "" : models().get(0);
    }

    /** Models by the name they are served under, with their streaming twins by the same name. */
    static ModelCatalog of(Map<String, ChatModel> byModel, Map<String, StreamingChatModel> streamingByModel) {
        Map<String, ChatModel> models = new LinkedHashMap<>(byModel);
        Map<String, StreamingChatModel> streaming = new LinkedHashMap<>(streamingByModel);
        if (models.isEmpty()) {
            throw new IllegalStateException("a model server needs at least one ChatModel to serve");
        }
        List<String> names = List.copyOf(models.keySet());
        return new ModelCatalog() {
            @Override
            public List<String> models() {
                return names;
            }

            @Override
            public Optional<ChatModel> model(String model) {
                return Optional.ofNullable(models.get(model));
            }

            @Override
            public Optional<StreamingChatModel> streamingModel(String model) {
                return Optional.ofNullable(streaming.get(model));
            }
        };
    }

    /** One model under its served name. */
    static ModelCatalog of(String name, ChatModel model) {
        return of(Map.of(name, model), Map.of());
    }

    /** One model and its streaming twin under one served name. */
    static ModelCatalog of(String name, ChatModel model, StreamingChatModel streaming) {
        return of(Map.of(name, model), Map.of(name, streaming));
    }

    static ModelCatalog of(List<String> models, Map<String, ChatModel> beans,
                           Map<String, StreamingChatModel> streamingBeans, Map<String, String> modelBeans) {
        Map<String, ChatModel> byModel = new LinkedHashMap<>();
        Map<String, StreamingChatModel> streamingByModel = new LinkedHashMap<>();
        modelBeans.forEach((model, bean) -> {
            ChatModel chat = beans.get(bean);
            if (chat == null) {
                throw new IllegalStateException("model-server.model-beans maps '"
                        + model + "' to bean '" + bean + "', which is not a ChatModel bean; beans: " + beans.keySet());
            }
            byModel.put(model, chat);
            if (streamingBeans.containsKey(bean)) {
                streamingByModel.put(model, streamingBeans.get(bean));
            }
        });
        beans.forEach((bean, chat) -> {
            String name = modelName(chat);
            if (name != null && !byModel.containsKey(name) && !modelBeans.containsValue(bean)) {
                byModel.put(name, chat);
                if (streamingBeans.containsKey(bean)) {
                    streamingByModel.put(name, streamingBeans.get(bean));
                }
            }
        });
        List<String> served = models.isEmpty() ? new ArrayList<>(byModel.keySet()) : List.copyOf(models);
        for (String model : served) {
            if (!byModel.containsKey(model)) {
                throw new IllegalStateException("model-server serves '" + model + "' but no ChatModel bean"
                        + " reports that model name; beans by model: " + byModel.keySet()
                        + "; map it with model-server.model-beans." + model);
            }
        }
        if (served.isEmpty()) {
            throw new IllegalStateException("model-server.enabled=true but this peer has no ChatModel to serve");
        }
        List<String> names = List.copyOf(served);
        return new ModelCatalog() {
            @Override
            public List<String> models() {
                return names;
            }

            @Override
            public Optional<ChatModel> model(String model) {
                return names.contains(model) ? Optional.ofNullable(byModel.get(model)) : Optional.empty();
            }

            @Override
            public Optional<StreamingChatModel> streamingModel(String model) {
                return names.contains(model) ? Optional.ofNullable(streamingByModel.get(model)) : Optional.empty();
            }
        };
    }

    /** The model name a LangChain4j model reports through its default parameters, if any. */
    static String modelName(ChatModel model) {
        Objects.requireNonNull(model, "model");
        try {
            var parameters = model.defaultRequestParameters();
            return parameters == null ? null : parameters.modelName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
