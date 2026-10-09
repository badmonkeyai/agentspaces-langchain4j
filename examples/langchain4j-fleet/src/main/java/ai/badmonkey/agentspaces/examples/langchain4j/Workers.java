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
package ai.badmonkey.agentspaces.examples.langchain4j;

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import dev.langchain4j.model.chat.ChatModel;

import java.util.Objects;

/**
 * The worker peer's two agents, bound by the runtime. Each completes in its
 * take space, which is where the orchestrator's fleet tool awaits the result.
 */
public final class Workers {

    private Workers() {
    }

    /** Summarizes with the peer's own model; a short lease the take-lease listener keeps alive. */
    @AgentSpec(name = "summarizer", description = "Summarizes a topic into a short briefing", goals = {"summarize"})
    public static class Summarizer {
        private final ChatModel model;

        public Summarizer(ChatModel model) {
            this.model = Objects.requireNonNull(model, "model");
        }

        @SpaceTake(space = "tasks", lease = "2m", pollTimeout = "PT0.3S")
        public Summary summarize(Brief brief) {
            String text = model.chat("Summarize '" + brief.topic() + "' in " + brief.sentences() + " sentences.");
            return new Summary(brief.topic(), text);
        }
    }

    /** Prices without a model at all. */
    @AgentSpec(name = "pricer", description = "Prices an item order from the catalogue", goals = {"price orders"})
    public static class Pricer {
        @SpaceTake(space = "tasks", pollTimeout = "PT0.3S")
        public Quote price(PriceRequest request) {
            double unit = switch (request.item()) {
                case "kite" -> 12.5;
                case "compass" -> 9.0;
                default -> 4.25;
            };
            return new Quote(request.item(), request.quantity(), unit * request.quantity());
        }
    }
}
