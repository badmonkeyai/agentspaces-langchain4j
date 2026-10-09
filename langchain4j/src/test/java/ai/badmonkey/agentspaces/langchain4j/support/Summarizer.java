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

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;

/**
 * A worker on another peer: the fleet tool the orchestrator's model calls.
 * It completes in its take space (no {@code resultSpace}), which is where a
 * remote action awaits the result (spec §10.6).
 */
@AgentSpec(name = "summarizer", description = "Summarizes a topic into a short briefing", goals = {"summarize"})
public class Summarizer {
    @SpaceTake(space = "tasks", pollTimeout = "PT0.2S")
    public Summary summarize(Brief brief) {
        return new Summary(brief.topic(), "summary of " + brief.topic() + " in " + brief.sentences() + " sentences");
    }
}
