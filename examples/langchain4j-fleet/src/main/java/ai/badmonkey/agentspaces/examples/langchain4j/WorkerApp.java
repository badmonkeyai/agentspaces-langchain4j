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

import ai.badmonkey.agentspaces.langchain4j.LangChain4jFleet;
import ai.badmonkey.agentspaces.langchain4j.model.ModelCatalog;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import dev.langchain4j.model.chat.ChatModel;

import java.util.Map;

/**
 * The worker peer: binds the summarizer and the pricer, and serves its own
 * model to the fleet so the keyless orchestrator can call it. Needs
 * {@code OPENAI_API_KEY}; the test runs the same shape with a scripted model.
 */
public final class WorkerApp {

    private WorkerApp() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("OPENAI_API_KEY") == null) {
            System.err.println("set OPENAI_API_KEY for the worker's model (the test uses a scripted one)");
            System.exit(2);
        }
        Map<String, Object> document = Peers.document("worker.yaml");
        try (FleetRuntime runtime = Peers.start(document)) {
            LangChain4jFleet fleet = Peers.fleet(runtime, document);
            ChatModel model = Peers.openAiModel(fleet, "gpt-4.1-mini");
            runtime.bind(new Workers.Summarizer(model));
            runtime.bind(new Workers.Pricer());
            fleet.modelServer(ModelCatalog.of("gpt-4.1-mini", model, Peers.openAiStreamingModel("gpt-4.1-mini")));
            System.out.println("worker: summarizer and pricer bound; serving gpt-4.1-mini to the fleet; Ctrl-C to stop");
            Thread.currentThread().join();
        }
    }
}
