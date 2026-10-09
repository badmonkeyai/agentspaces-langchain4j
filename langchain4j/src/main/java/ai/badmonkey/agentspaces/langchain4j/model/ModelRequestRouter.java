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

import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.springai.model.wire.ModelRequest;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static ai.badmonkey.agentspaces.api.space.Matchers.in;

/** Which requests a server takes: by the models it serves, or by price under AUCTION. */
public interface ModelRequestRouter {

    Template<ModelRequest> template(ModelCatalog catalog);

    default OptionalDouble bid(ModelRequest request, ModelCatalog catalog) {
        return OptionalDouble.empty();
    }

    static ModelRequestRouter byModel() {
        return catalog -> {
            List<Object> names = new ArrayList<>(catalog.models());
            names.add("");
            return Template.of(ModelRequest.class).where("model", in(names.toArray()));
        };
    }

    static ModelRequestRouter byPrice(Map<String, Double> pricePerThousandTokens) {
        Map<String, Double> prices = Map.copyOf(pricePerThousandTokens);
        return new ModelRequestRouter() {
            @Override
            public Template<ModelRequest> template(ModelCatalog catalog) {
                return Template.of(ModelRequest.class);
            }

            @Override
            public OptionalDouble bid(ModelRequest request, ModelCatalog catalog) {
                String model = request.model().isBlank() ? catalog.defaultModel() : request.model();
                if (catalog.model(model).isEmpty()) {
                    return OptionalDouble.of(Double.POSITIVE_INFINITY);
                }
                return OptionalDouble.of(prices.getOrDefault(model, 1.0) * estimatedTokens(request) / 1000.0);
            }
        };
    }

    static double estimatedTokens(ModelRequest request) {
        long chars = 0;
        for (WireMessage message : request.messages()) {
            chars += message.text() == null ? 0 : message.text().length();
        }
        return Math.max(1.0, chars / 4.0);
    }
}
