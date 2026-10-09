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
package ai.badmonkey.agentspaces.tools;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A JSON Schema document for a record type, as plain maps: what a model
 * sees as a tool's input. Strings, numbers, booleans, enums, lists and sets,
 * maps, nested records, and {@code Optional} (not required). The same
 * document feeds LangChain4j's schema builders and MCP's {@code inputSchema},
 * so one tool looks the same to both.
 */
public final class RecordSchemas {

    private static final int MAX_DEPTH = 8;

    private RecordSchemas() {
    }

    /**
     * The schema of a record type (an object schema), or {@code {"type":"object"}}
     * for anything that is not a record.
     */
    public static Map<String, Object> of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return schemaOf(type, type, 0);
    }

    private static Map<String, Object> schemaOf(Type generic, Class<?> raw, int depth) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (depth > MAX_DEPTH) {
            schema.put("type", "object");
            return schema;
        }
        if (raw == String.class || raw == char.class || raw == Character.class
                || raw == Instant.class || raw == LocalDate.class || raw == LocalDateTime.class
                || raw == Duration.class || raw == java.util.UUID.class || raw == java.net.URI.class) {
            schema.put("type", "string");
            return schema;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
            return schema;
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class
                || raw == BigInteger.class) {
            schema.put("type", "integer");
            return schema;
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                || raw == BigDecimal.class || Number.class.isAssignableFrom(raw)) {
            schema.put("type", "number");
            return schema;
        }
        if (raw.isEnum()) {
            schema.put("type", "string");
            List<String> values = new ArrayList<>();
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            schema.put("enum", values);
            return schema;
        }
        if (raw.isArray()) {
            schema.put("type", "array");
            schema.put("items", schemaOf(raw.getComponentType(), raw.getComponentType(), depth + 1));
            return schema;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            schema.put("type", "array");
            Type item = typeArgument(generic, 0);
            schema.put("items", schemaOf(item, rawOf(item), depth + 1));
            if (Set.class.isAssignableFrom(raw)) {
                schema.put("uniqueItems", true);
            }
            return schema;
        }
        if (Map.class.isAssignableFrom(raw)) {
            schema.put("type", "object");
            Type value = typeArgument(generic, 1);
            schema.put("additionalProperties", schemaOf(value, rawOf(value), depth + 1));
            return schema;
        }
        if (raw == Optional.class) {
            Type inner = typeArgument(generic, 0);
            return schemaOf(inner, rawOf(inner), depth);
        }
        if (raw.isRecord()) {
            schema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                Class<?> componentRaw = component.getType();
                properties.put(component.getName(),
                        schemaOf(component.getGenericType(), componentRaw, depth + 1));
                if (componentRaw != Optional.class) {
                    required.add(component.getName());
                }
            }
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            schema.put("additionalProperties", false);
            return schema;
        }
        schema.put("type", "object");
        return schema;
    }

    private static Type typeArgument(Type generic, int index) {
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length > index) {
            return parameterized.getActualTypeArguments()[index];
        }
        return Object.class;
    }

    private static Class<?> rawOf(Type type) {
        if (type instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return Object.class;
    }
}
