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
package ai.badmonkey.agentspaces.examples.langchain4j.interop;

import ai.badmonkey.agentspaces.api.spi.SchemaRegistry;
import ai.badmonkey.agentspaces.space.local.SimpleSchemaRegistry;

import java.util.Optional;

/**
 * Names this worker's records the way the research fleet (example 02 in the
 * core, and its Python and TypeScript workers) names them on the wire, so a
 * task the Java coordinator wrote matches this worker's {@link ResearchTask}
 * and the {@link Finding} it completes with is the one the coordinator awaits.
 * The space names an entry type by its schema name, and a registry is where
 * two codebases agree on one.
 */
public final class ResearchSchemas implements SchemaRegistry {

    /** The coordinator's names: its record classes under the default rule. */
    public static final String TASK = "ai.badmonkey.agentspaces.examples.fleet.ResearchFleet$ResearchTask#v1";
    public static final String FINDING = "ai.badmonkey.agentspaces.examples.fleet.ResearchFleet$Finding#v1";

    /** A unit of research work, as the coordinator writes it. */
    public record ResearchTask(String topic, int priority) {
    }

    /** A completed research result, as the coordinator reads it. */
    public record Finding(String topic, String summary, String worker) {
    }

    private final SimpleSchemaRegistry others = new SimpleSchemaRegistry();

    @Override
    public String register(Class<?> entryType) {
        return mapped(entryType).orElseGet(() -> others.register(entryType));
    }

    @Override
    public String schemaNameOf(Class<?> entryType) {
        return mapped(entryType).orElseGet(() -> others.schemaNameOf(entryType));
    }

    @Override
    public Optional<Class<?>> classFor(String schemaName) {
        if (TASK.equals(schemaName)) {
            return Optional.of(ResearchTask.class);
        }
        if (FINDING.equals(schemaName)) {
            return Optional.of(Finding.class);
        }
        return others.classFor(schemaName);
    }

    private static Optional<String> mapped(Class<?> entryType) {
        if (entryType == ResearchTask.class) {
            return Optional.of(TASK);
        }
        if (entryType == Finding.class) {
            return Optional.of(FINDING);
        }
        return Optional.empty();
    }
}
