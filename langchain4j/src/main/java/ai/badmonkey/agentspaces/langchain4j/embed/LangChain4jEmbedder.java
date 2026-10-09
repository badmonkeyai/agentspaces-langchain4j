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
package ai.badmonkey.agentspaces.langchain4j.embed;

import ai.badmonkey.agentspaces.api.spi.Embedder;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A LangChain4j {@code EmbeddingModel} behind the core {@code Embedder} SPI:
 * {@code float[]} to {@code double[]}, L2-normalized, cached by the text's
 * content hash (semantic discovery re-embeds every advertisement per query),
 * with the identity {@code langchain4j:<model>} advertised on the capability
 * so peers rank alike.
 */
public class LangChain4jEmbedder implements Embedder {

    private final EmbeddingModel model;
    private final String identity;
    private final Map<String, double[]> cache;
    private volatile int dimensions = -1;

    public LangChain4jEmbedder(EmbeddingModel model, String modelName, int cacheSize) {
        this.model = Objects.requireNonNull(model, "model");
        this.identity = "langchain4j:" + (modelName == null || modelName.isBlank()
                ? model.getClass().getSimpleName() : modelName);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                return size() > cacheSize;
            }
        };
    }

    @Override
    public double[] embed(String text) {
        String key = hash(text);
        synchronized (cache) {
            double[] cached = cache.get(key);
            if (cached != null) {
                return cached.clone();
            }
        }
        float[] raw = model.embed(text).content().vector();
        double[] vector = new double[raw.length];
        double norm = 0;
        for (int i = 0; i < raw.length; i++) {
            vector[i] = raw[i];
            norm += vector[i] * vector[i];
        }
        if (norm > 0) {
            double scale = 1.0 / Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        dimensions = vector.length;
        synchronized (cache) {
            cache.put(key, vector);
        }
        return vector.clone();
    }

    @Override
    public int dimensions() {
        int known = dimensions;
        if (known < 0) {
            known = model.dimension();
            dimensions = known;
        }
        return known;
    }

    @Override
    public String identity() {
        return identity;
    }

    @Override
    public boolean normalized() {
        return true;
    }

    public int cached() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
