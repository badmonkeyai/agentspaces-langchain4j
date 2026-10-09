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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The YAML reader, in a class of its own so {@link PropertiesLoader} links
 * against the optional YAML module only when a YAML method runs.
 */
final class YamlDocuments {

    private YamlDocuments() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(InputStream in) {
        try {
            Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
            return doc == null ? new LinkedHashMap<>() : doc;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot parse YAML", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Reader reader) {
        try {
            Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(reader, Map.class);
            return doc == null ? new LinkedHashMap<>() : doc;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot parse YAML", e);
        }
    }
}
