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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A kebab-case configuration subtree read by key, with the conversions the
 * Spring starter's properties accept: durations in ISO-8601 or the short
 * form ({@code 5m}, {@code 250ms}), booleans and numbers as text or values.
 * Unknown keys fail, so a typo surfaces where the settings are built.
 */
public final class Props {

    private final Map<String, Object> tree;
    private final String path;
    private final Set<String> read = new LinkedHashSet<>();

    private Props(Map<String, Object> tree, String path) {
        this.tree = tree;
        this.path = path;
    }

    /** Over a subtree; {@code path} names it in error messages. */
    @SuppressWarnings("unchecked")
    public static Props of(Map<String, ?> tree, String path) {
        return new Props(tree == null ? Map.of() : (Map<String, Object>) tree, path);
    }

    public boolean has(String key) {
        return tree.containsKey(key);
    }

    public String string(String key, String fallback) {
        Object value = get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public boolean bool(String key, boolean fallback) {
        Object value = get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if (text.equals("true") || text.equals("false")) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException(path + "." + key + ": not a boolean: '" + value + "'");
    }

    public int integer(String key, int fallback) {
        Object value = get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + "." + key + ": not an integer: '" + value + "'");
        }
    }

    public Duration duration(String key, Duration fallback) {
        Object value = get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }
        try {
            Duration parsed = parseDuration(String.valueOf(value));
            return parsed == null ? fallback : parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(path + "." + key + ": not a duration: '" + value + "'");
        }
    }

    public <E extends Enum<E>> E enumeration(String key, Class<E> type, E fallback) {
        Object value = get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().replace('-', '_').toUpperCase();
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + "." + key + ": '" + value + "' is not one of "
                    + List.of(type.getEnumConstants()));
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> strings(String key) {
        Object value = get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            list.forEach(item -> out.add(String.valueOf(item)));
            return List.copyOf(out);
        }
        if (value instanceof Map<?, ?> indexed) {
            // Flat sources may carry a list as an index-keyed map.
            List<String> out = new ArrayList<>();
            ((Map<String, Object>) indexed).values().forEach(item -> out.add(String.valueOf(item)));
            return List.copyOf(out);
        }
        List<String> out = new ArrayList<>();
        for (String item : String.valueOf(value).split(",")) {
            if (!item.isBlank()) {
                out.add(item.trim());
            }
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> map(String key) {
        Object value = get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, String.valueOf(v)));
            return Map.copyOf(out);
        }
        throw new IllegalArgumentException(path + "." + key + ": expected a map");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Double> doubles(String key) {
        Object value = get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Double> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, Double.parseDouble(String.valueOf(v))));
            return Map.copyOf(out);
        }
        throw new IllegalArgumentException(path + "." + key + ": expected a map of numbers");
    }

    /** The nested subtree under {@code key}, empty when absent. */
    @SuppressWarnings("unchecked")
    public Props sub(String key) {
        Object value = get(key);
        if (value == null) {
            return new Props(Map.of(), path + "." + key);
        }
        if (value instanceof Map<?, ?> map) {
            return new Props((Map<String, Object>) map, path + "." + key);
        }
        throw new IllegalArgumentException(path + "." + key + ": expected a section");
    }

    /** Fails when the subtree carries a key this reader never asked for. */
    public void rejectUnknown() {
        for (String key : tree.keySet()) {
            if (!read.contains(key)) {
                throw new IllegalArgumentException(path + ": unknown key '" + key + "'; known keys: " + read);
            }
        }
    }

    private Object get(String key) {
        read.add(key);
        return tree.get(key);
    }

    /** ISO-8601, a plain number of milliseconds, or the {@code 5m}-style suffix; null for blank. */
    public static Duration parseDuration(String text) {
        String value = Objects.requireNonNull(text, "text").trim();
        if (value.isEmpty()) {
            return null;
        }
        String upper = value.toUpperCase();
        if (upper.startsWith("P") || upper.startsWith("-P")) {
            return Duration.parse(upper);
        }
        if (value.chars().allMatch(Character::isDigit)) {
            return Duration.ofMillis(Long.parseLong(value));
        }
        int split = value.length();
        while (split > 0 && !Character.isDigit(value.charAt(split - 1))) {
            split--;
        }
        long amount = Long.parseLong(value.substring(0, split).trim());
        return switch (value.substring(split).trim().toLowerCase()) {
            case "ns" -> Duration.ofNanos(amount);
            case "us" -> Duration.ofNanos(amount * 1_000);
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("not a duration: '" + text + "'");
        };
    }
}
