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
package ai.badmonkey.agentspaces.langchain4j.tools;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the base library's JSON Schema maps into LangChain4j's schema elements. */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    @SuppressWarnings("unchecked")
    public static JsonObjectSchema objectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                builder.addProperty(String.valueOf(entry.getKey()), element((Map<String, Object>) entry.getValue()));
            }
        }
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            List<String> names = new ArrayList<>();
            list.forEach(name -> names.add(String.valueOf(name)));
            builder.required(names);
        }
        if (schema.get("additionalProperties") instanceof Boolean allowed) {
            builder.additionalProperties(allowed);
        }
        if (schema.get("description") instanceof String description) {
            builder.description(description);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    public static JsonSchemaElement element(Map<String, Object> schema) {
        String type = String.valueOf(schema.getOrDefault("type", "object"));
        if (schema.get("enum") instanceof List<?> values) {
            List<String> names = new ArrayList<>();
            values.forEach(value -> names.add(String.valueOf(value)));
            return JsonEnumSchema.builder().enumValues(names).build();
        }
        return switch (type) {
            case "string" -> JsonStringSchema.builder().build();
            case "integer" -> JsonIntegerSchema.builder().build();
            case "number" -> JsonNumberSchema.builder().build();
            case "boolean" -> JsonBooleanSchema.builder().build();
            case "array" -> JsonArraySchema.builder()
                    .items(schema.get("items") instanceof Map<?, ?> items
                            ? element((Map<String, Object>) items) : JsonStringSchema.builder().build())
                    .build();
            default -> objectSchema(schema);
        };
    }

    /** A LangChain4j schema element back to a plain map (what the wire carries as JSON). */
    public static Map<String, Object> toMap(JsonSchemaElement element) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (element) {
            case JsonObjectSchema object -> {
                out.put("type", "object");
                Map<String, Object> properties = new LinkedHashMap<>();
                if (object.properties() != null) {
                    object.properties().forEach((name, value) -> properties.put(name, toMap(value)));
                }
                out.put("properties", properties);
                if (object.required() != null && !object.required().isEmpty()) {
                    out.put("required", new ArrayList<>(object.required()));
                }
                if (object.additionalProperties() != null) {
                    out.put("additionalProperties", object.additionalProperties());
                }
            }
            case JsonArraySchema array -> {
                out.put("type", "array");
                if (array.items() != null) {
                    out.put("items", toMap(array.items()));
                }
            }
            case JsonEnumSchema values -> {
                out.put("type", "string");
                out.put("enum", new ArrayList<>(values.enumValues()));
            }
            case JsonStringSchema ignored -> out.put("type", "string");
            case JsonIntegerSchema ignored -> out.put("type", "integer");
            case JsonNumberSchema ignored -> out.put("type", "number");
            case JsonBooleanSchema ignored -> out.put("type", "boolean");
            default -> out.put("type", "object");
        }
        if (element.description() != null) {
            out.put("description", element.description());
        }
        return out;
    }
}
