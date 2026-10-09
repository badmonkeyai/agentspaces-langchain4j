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
package ai.badmonkey.agentspaces.runtime;

import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reads the {@code agentspaces.*} configuration the Spring Boot starter and
 * the Micronaut library read, from YAML, a nested map, or flat dotted keys,
 * into the starter's {@link AgentSpacesProperties}: kebab-case keys to the
 * POJO's camel-case setters, lists of maps to the nested group and space
 * definitions, durations in ISO-8601 ({@code PT5M}) or the short form
 * ({@code 5m}, {@code 250ms}, {@code 2h}, {@code 1d}). Unknown keys fail,
 * so a typo surfaces at startup. The whole document is returned by
 * {@link #tree(Path)} for the sections this project's own settings read.
 */
public final class PropertiesLoader {

    private PropertiesLoader() {
    }

    /** The {@code agentspaces} subtree of a YAML file, bound. */
    public static AgentSpacesProperties yaml(Path file) {
        return map(agentspacesSubtree(tree(file)));
    }

    /** The {@code agentspaces} subtree of a YAML stream, bound. */
    public static AgentSpacesProperties yaml(InputStream in) {
        return map(agentspacesSubtree(tree(in)));
    }

    /**
     * The whole YAML document as a nested map (an empty document is an empty
     * map). Needs {@code jackson-dataformat-yaml} on the classpath; the map
     * and flat forms do not.
     */
    public static Map<String, Object> tree(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return tree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** The whole YAML document as a nested map. */
    public static Map<String, Object> tree(InputStream in) {
        return YamlDocuments.read(in);
    }

    /** The whole YAML text as a nested map. */
    public static Map<String, Object> tree(Reader reader) {
        return YamlDocuments.read(reader);
    }

    /** The {@code agentspaces} key of a document, or the document itself when it has none. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> agentspacesSubtree(Map<String, Object> document) {
        Object sub = document.get("agentspaces");
        if (sub instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return document;
    }

    /**
     * Binds a nested map (the {@code agentspaces} subtree: kebab-case keys,
     * lists for repeated sections) into the properties POJO. Keys this
     * project's own settings own ({@code langchain4j}, {@code tools},
     * {@code runtime}) are not the starter's and are dropped here.
     */
    public static AgentSpacesProperties map(Map<String, Object> tree) {
        Objects.requireNonNull(tree, "tree");
        @SuppressWarnings("unchecked")
        Map<String, Object> cleaned = (Map<String, Object>) withoutIndexedKeys(tree);
        cleaned.remove("enabled");
        cleaned.remove("langchain4j");
        cleaned.remove("tools");
        cleaned.remove("runtime");
        cleaned.remove("micronaut");
        return mapper().convertValue(cleaned, AgentSpacesProperties.class);
    }

    /**
     * Binds flat dotted keys ({@code agentspaces.groups[0].spaces[1].name})
     * the way an environment or a test map carries them; the leading
     * {@code agentspaces.} prefix is optional.
     */
    public static AgentSpacesProperties flat(Map<String, ?> flat) {
        return map(nest(flat));
    }

    /**
     * Flat dotted keys with {@code [i]} indexes to the nested tree. A list
     * with a missing index, or a key used both as a value and as a section,
     * fails naming the key.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> nest(Map<String, ?> flat) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : new TreeMap<>(flat).entrySet()) {
            String fullKey = entry.getKey();
            String key = fullKey.startsWith("agentspaces.") ? fullKey.substring("agentspaces.".length()) : fullKey;
            String[] parts = key.split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                int bracket = part.indexOf('[');
                String name = bracket < 0 ? part : part.substring(0, bracket);
                Integer index = bracket < 0 ? null
                        : Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
                boolean last = i == parts.length - 1;
                Object existing = node.get(name);
                if (index == null) {
                    if (last) {
                        if (existing instanceof Map || existing instanceof List) {
                            throw new IllegalArgumentException("'" + fullKey + "' is both a value and a section");
                        }
                        node.put(name, entry.getValue());
                    } else {
                        if (existing != null && !(existing instanceof Map)) {
                            throw new IllegalArgumentException("'" + fullKey + "' is both a value and a section");
                        }
                        node = (Map<String, Object>) node.computeIfAbsent(name, k -> new LinkedHashMap<>());
                    }
                } else {
                    if (existing != null && !(existing instanceof List)) {
                        throw new IllegalArgumentException("'" + fullKey + "' is both a value and a list");
                    }
                    List<Object> list = (List<Object>) node.computeIfAbsent(name, k -> new ArrayList<>());
                    while (list.size() <= index) {
                        list.add(null);
                    }
                    if (last) {
                        list.set(index, entry.getValue());
                    } else {
                        if (list.get(index) == null) {
                            list.set(index, new LinkedHashMap<String, Object>());
                        } else if (!(list.get(index) instanceof Map)) {
                            throw new IllegalArgumentException("'" + fullKey + "' is both a value and a section");
                        }
                        node = (Map<String, Object>) list.get(index);
                    }
                }
            }
        }
        rejectGaps(root, "agentspaces");
        return root;
    }

    @SuppressWarnings("unchecked")
    private static void rejectGaps(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> rejectGaps(v, path + "." + k));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == null) {
                    throw new IllegalArgumentException("'" + path + "[" + i + "]' is missing: list indexes must be"
                            + " contiguous from 0");
                }
                rejectGaps(list.get(i), path + "[" + i + "]");
            }
        }
    }

    private static ObjectMapper mapper() {
        SimpleModule durations = new SimpleModule("agentspaces-durations")
                .addDeserializer(Duration.class, new LenientDuration());
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .registerModule(durations);
    }

    @SuppressWarnings("unchecked")
    private static Object withoutIndexedKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.contains("[")) {
                    continue;
                }
                copy.put(key, withoutIndexedKeys(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(withoutIndexedKeys(item));
            }
            return copy;
        }
        return value;
    }

    /** ISO-8601, a plain number of milliseconds, or the {@code 5m}-style suffix. */
    static final class LenientDuration extends JsonDeserializer<Duration> {
        @Override
        public Duration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) {
                return Duration.ofMillis(parser.getLongValue());
            }
            return Props.parseDuration(parser.getText());
        }
    }
}
