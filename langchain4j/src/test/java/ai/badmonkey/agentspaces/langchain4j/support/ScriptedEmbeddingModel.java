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
package ai.badmonkey.agentspaces.langchain4j.support;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/** A two-dimensional embedding from the text's length and vowel count. */
public class ScriptedEmbeddingModel implements EmbeddingModel {
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return Response.from(segments.stream().map(s -> {
            String text = s.text();
            float vowels = text.replaceAll("[^aeiouAEIOU]", "").length();
            return Embedding.from(new float[] {text.length(), vowels});
        }).toList());
    }

    @Override
    public int dimension() {
        return 2;
    }
}
