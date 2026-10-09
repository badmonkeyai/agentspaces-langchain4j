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

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a fleet tool from the model's JSON: deserializes the arguments into
 * the action's input record (unknown and missing fields are rejected before
 * anything enters a space), invokes the action, and serializes the result.
 * A timeout becomes a text the model can act on, or a failure when
 * {@code on-timeout} is {@code throw}.
 */
public final class ToolInvocation {

    /** What a timeout does. */
    public enum OnTimeout {
        /** Return a result that explains the timeout. */
        RESULT,
        /** Throw {@link ToolInvocationException}. */
        THROW
    }

    /** The outcome: the JSON text for the model and whether it is an error. */
    public record Outcome(String text, boolean error) {
    }

    private final ObjectMapper json;
    private final Duration timeout;
    private final OnTimeout onTimeout;

    public ToolInvocation(Duration timeout, OnTimeout onTimeout) {
        this.json = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.onTimeout = Objects.requireNonNull(onTimeout, "onTimeout");
    }

    public Duration timeout() {
        return timeout;
    }

    /** Parses, invokes, and renders; the outcome's error flag follows {@code on-timeout}. */
    public Outcome call(FleetAction action, String argumentsJson) {
        Object input = parse(action, argumentsJson);
        Optional<Object> result;
        try {
            result = action.invoke(input, timeout);
        } catch (RuntimeException e) {
            throw new ToolInvocationException("fleet tool " + action.name() + " failed: " + e.getMessage(), e);
        }
        if (result.isPresent()) {
            return new Outcome(write(result.get()), false);
        }
        String message = "the fleet produced no " + action.outputType().getSimpleName()
                + " for this " + action.inputType().getSimpleName() + " within " + timeout;
        if (onTimeout == OnTimeout.THROW) {
            throw new ToolInvocationException(message, null);
        }
        return new Outcome(write(Map.of("error", message)), true);
    }

    /** The arguments as the input record, validated. */
    public Object parse(FleetAction action, String argumentsJson) {
        try {
            JsonNode node = json.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            if (action.inputType().isRecord()) {
                if (!node.isObject()) {
                    throw new IllegalArgumentException("expected a JSON object for "
                            + action.inputType().getSimpleName());
                }
                List<String> missing = new ArrayList<>();
                for (RecordComponent component : action.inputType().getRecordComponents()) {
                    if (component.getType() != Optional.class && !node.has(component.getName())) {
                        missing.add(component.getName());
                    }
                }
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException("missing field(s) " + missing + " for "
                            + action.inputType().getSimpleName());
                }
            }
            return json.treeToValue(node, action.inputType());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new ToolInvocationException("invalid arguments for fleet tool " + action.name() + ": "
                    + e.getMessage(), e);
        }
    }

    /** Serializes a value for the model. */
    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new ToolInvocationException("cannot serialize a " + value.getClass().getSimpleName(), e);
        }
    }

    /** A tool invocation that could not produce a result for the model. */
    public static final class ToolInvocationException extends RuntimeException {
        public ToolInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
