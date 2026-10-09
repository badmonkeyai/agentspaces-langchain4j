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

import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import ai.badmonkey.agentspaces.springai.model.wire.WireMedia;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import ai.badmonkey.agentspaces.springai.model.wire.WireOptions;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolCall;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolDefinition;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireUsage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import ai.badmonkey.agentspaces.langchain4j.tools.JsonSchemas;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j messages, parameters, tools, and responses to the wire records
 * and back, with the Spring AI module's conventions: roles {@code system},
 * {@code user}, {@code assistant}, {@code tool}; tool calls of type
 * {@code function}; finish reasons as Spring's strings ({@code STOP},
 * {@code TOOL_CALLS}, {@code LENGTH}, ...); media inline as bytes or by URL.
 */
public final class WireMapping {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMapping() {
    }

    // ---------------------------------------------------------------- messages

    public static WireMessage toWire(ChatMessage message) {
        return switch (message) {
            case SystemMessage system -> new WireMessage("system", system.text(), List.of(), List.of(),
                    List.of(), Map.of());
            case UserMessage user -> new WireMessage("user", textOf(user), List.of(), List.of(),
                    media(user.contents()), user.name() == null ? Map.of() : Map.of("name", user.name()));
            case AiMessage ai -> new WireMessage("assistant", ai.text() == null ? "" : ai.text(),
                    ai.hasToolExecutionRequests() ? ai.toolExecutionRequests().stream()
                            .map(r -> new WireToolCall(r.id(), "function", r.name(), r.arguments())).toList()
                            : List.of(),
                    List.of(), List.of(), Map.of());
            case ToolExecutionResultMessage tool -> new WireMessage("tool", null, List.of(),
                    List.of(new WireToolResponse(tool.id(), tool.toolName(), tool.text())), List.of(), Map.of());
            default -> new WireMessage(message.type().name().toLowerCase(), String.valueOf(message),
                    List.of(), List.of(), List.of(), Map.of());
        };
    }

    public static ChatMessage fromWire(WireMessage message) {
        return switch (message.role()) {
            case "system" -> SystemMessage.from(text(message));
            case "assistant" -> {
                AiMessage.Builder builder = AiMessage.builder().text(message.text() == null ? "" : message.text());
                if (!message.toolCalls().isEmpty()) {
                    builder.toolExecutionRequests(message.toolCalls().stream().map(c -> ToolExecutionRequest.builder()
                            .id(c.id()).name(c.name()).arguments(c.arguments()).build()).toList());
                }
                yield builder.build();
            }
            case "tool" -> {
                WireToolResponse response = message.toolResponses().isEmpty()
                        ? new WireToolResponse(null, "", text(message)) : message.toolResponses().get(0);
                yield ToolExecutionResultMessage.from(response.id(), response.name(), response.responseData());
            }
            default -> {
                if (message.media().isEmpty()) {
                    yield UserMessage.from(text(message));
                }
                List<Content> contents = new ArrayList<>();
                if (!text(message).isEmpty()) {
                    contents.add(TextContent.from(text(message)));
                }
                contents.addAll(fromWireMedia(message.media()));
                yield UserMessage.from(contents);
            }
        };
    }

    public static List<WireMessage> toWire(List<ChatMessage> messages) {
        return messages.stream().map(WireMapping::toWire).toList();
    }

    public static List<ChatMessage> fromWire(List<WireMessage> messages) {
        return messages.stream().map(WireMapping::fromWire).toList();
    }

    // ---------------------------------------------------------------- parameters and tools

    public static WireOptions toWire(ChatRequestParameters parameters) {
        if (parameters == null) {
            return null;
        }
        return new WireOptions(parameters.modelName(), parameters.temperature(), parameters.maxOutputTokens(),
                parameters.topP(), parameters.topK(), parameters.frequencyPenalty(),
                parameters.presencePenalty(), parameters.stopSequences());
    }

    public static List<WireToolDefinition> toolDefinitions(ChatRequest request) {
        List<ToolSpecification> specs = request.toolSpecifications();
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<WireToolDefinition> out = new ArrayList<>();
        for (ToolSpecification spec : specs) {
            out.add(new WireToolDefinition(spec.name(), spec.description() == null ? "" : spec.description(),
                    schemaJson(spec.parameters())));
        }
        return out;
    }

    public static ChatRequest fromWire(List<WireMessage> messages, WireOptions options, String model,
                                       List<WireToolDefinition> tools) {
        var builder = ChatRequestParameters.builder().modelName(model);
        if (options != null) {
            builder.temperature(options.temperature()).maxOutputTokens(options.maxTokens())
                    .topP(options.topP()).topK(options.topK())
                    .frequencyPenalty(options.frequencyPenalty()).presencePenalty(options.presencePenalty());
            if (options.stopSequences() != null) {
                builder.stopSequences(options.stopSequences());
            }
        }
        if (!tools.isEmpty()) {
            List<ToolSpecification> specs = new ArrayList<>();
            for (WireToolDefinition tool : tools) {
                specs.add(ToolSpecification.builder().name(tool.name()).description(tool.description())
                        .parameters(schemaFromJson(tool.inputSchema())).build());
            }
            builder.toolSpecifications(specs);
        }
        return ChatRequest.builder().messages(fromWire(messages)).parameters(builder.build()).build();
    }

    // ---------------------------------------------------------------- responses

    public static List<WireGeneration> generations(ChatResponse response) {
        AiMessage message = response.aiMessage();
        return List.of(new WireGeneration(message == null ? null : toWire(message), finishReason(response.finishReason())));
    }

    public static WireUsage usage(ChatResponse response) {
        TokenUsage usage = response.tokenUsage();
        if (usage == null) {
            return null;
        }
        return new WireUsage(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
    }

    public static ChatResponse toResponse(List<WireGeneration> generations, WireUsage usage, String model, String id) {
        AiMessage message = AiMessage.from("");
        FinishReason finish = null;
        StringBuilder text = new StringBuilder();
        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (WireGeneration generation : generations) {
            if (generation.output() != null) {
                if (generation.output().text() != null) {
                    text.append(generation.output().text());
                }
                for (WireToolCall call : generation.output().toolCalls()) {
                    calls.add(ToolExecutionRequest.builder().id(call.id()).name(call.name())
                            .arguments(call.arguments()).build());
                }
            }
            if (generation.finishReason() != null) {
                finish = finishReason(generation.finishReason());
            }
        }
        if (!generations.isEmpty()) {
            AiMessage.Builder builder = AiMessage.builder().text(text.toString());
            if (!calls.isEmpty()) {
                builder.toolExecutionRequests(calls);
            }
            message = builder.build();
        }
        ChatResponse.Builder builder = ChatResponse.builder().aiMessage(message).id(id);
        if (model != null && !model.isBlank()) {
            builder.modelName(model);
        }
        if (usage != null) {
            builder.tokenUsage(new TokenUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens()));
        }
        if (finish != null) {
            builder.finishReason(finish);
        }
        return builder.build();
    }

    /** LangChain4j's finish reason as the Spring AI module writes it. */
    public static String finishReason(FinishReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case STOP -> "STOP";
            case LENGTH -> "LENGTH";
            case TOOL_EXECUTION -> "TOOL_CALLS";
            case CONTENT_FILTER -> "CONTENT_FILTER";
            default -> reason.name();
        };
    }

    public static FinishReason finishReason(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason.toUpperCase()) {
            case "STOP", "END_TURN" -> FinishReason.STOP;
            case "LENGTH", "MAX_TOKENS" -> FinishReason.LENGTH;
            case "TOOL_CALLS", "TOOL_EXECUTION", "TOOL_USE", "FUNCTION_CALL" -> FinishReason.TOOL_EXECUTION;
            case "CONTENT_FILTER" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    // ---------------------------------------------------------------- internals

    /** The text parts of a user message joined; a text-only message is its single text. */
    public static String textOf(UserMessage user) {
        if (user.hasSingleText()) {
            return user.singleText();
        }
        StringBuilder text = new StringBuilder();
        for (Content content : user.contents()) {
            if (content instanceof TextContent part) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(part.text());
            }
        }
        return text.toString();
    }

    private static String text(WireMessage message) {
        return message.text() == null ? "" : message.text();
    }

    private static List<WireMedia> media(List<Content> contents) {
        List<WireMedia> out = new ArrayList<>();
        for (Content content : contents) {
            switch (content) {
                case ImageContent image -> out.add(media(image.image().mimeType(), image.image().base64Data(),
                        image.image().url()));
                case AudioContent audio -> out.add(media(audio.audio().mimeType(), audio.audio().base64Data(),
                        audio.audio().url()));
                case PdfFileContent pdf -> out.add(media(pdf.pdfFile().mimeType() == null ? "application/pdf"
                        : pdf.pdfFile().mimeType(), pdf.pdfFile().base64Data(), pdf.pdfFile().url()));
                default -> {
                    // text is carried by the message itself
                }
            }
        }
        return out;
    }

    private static WireMedia media(String mimeType, String base64, URI url) {
        if (base64 != null) {
            return new WireMedia(mimeType, null, Base64.getDecoder().decode(base64), null);
        }
        return new WireMedia(mimeType, null, null, url == null ? null : url.toString());
    }

    private static List<Content> fromWireMedia(List<WireMedia> media) {
        List<Content> out = new ArrayList<>();
        for (WireMedia item : media) {
            String mime = item.mimeType() == null ? "" : item.mimeType();
            String base64 = item.data() == null ? null : Base64.getEncoder().encodeToString(item.data());
            if (mime.startsWith("audio/")) {
                Audio.Builder audio = Audio.builder().mimeType(mime);
                out.add(AudioContent.from(base64 != null ? audio.base64Data(base64).build()
                        : audio.url(URI.create(item.url())).build()));
            } else if (mime.equals("application/pdf")) {
                PdfFile.Builder pdf = PdfFile.builder().mimeType(mime);
                out.add(PdfFileContent.from(base64 != null ? pdf.base64Data(base64).build()
                        : pdf.url(URI.create(item.url())).build()));
            } else {
                Image.Builder image = Image.builder().mimeType(mime);
                out.add(ImageContent.from(base64 != null ? image.base64Data(base64).build()
                        : image.url(URI.create(item.url())).build()));
            }
        }
        return out;
    }

    private static String schemaJson(JsonObjectSchema schema) {
        if (schema == null) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            return JSON.writeValueAsString(JsonSchemas.toMap(schema));
        } catch (JacksonException e) {
            return "{\"type\":\"object\"}";
        }
    }

    private static JsonObjectSchema schemaFromJson(String json) {
        if (json == null || json.isBlank()) {
            return JsonObjectSchema.builder().build();
        }
        try {
            Map<String, Object> map = JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
            return JsonSchemas.objectSchema(map);
        } catch (JacksonException e) {
            return JsonObjectSchema.builder().build();
        }
    }
}
