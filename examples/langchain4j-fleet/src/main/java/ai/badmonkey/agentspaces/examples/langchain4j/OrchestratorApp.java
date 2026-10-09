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
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import dev.langchain4j.service.AiServices;

import java.time.Duration;
import java.util.Map;

/**
 * The orchestrator peer: no provider key, no provider dependency in use. Its
 * tools are the workers' AgentCards, live from the fleet, and its model is
 * the fleet's model service. It prints its plan and exits.
 */
public final class OrchestratorApp {

    private OrchestratorApp() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> document = Peers.document("orchestrator.yaml");
        try (FleetRuntime runtime = Peers.start(document)) {
            LangChain4jFleet fleet = Peers.fleet(runtime, document);
            System.out.println("orchestrator: waiting for the workers' cards");
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (fleet.catalog().current().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(250);
            }
            System.out.println("orchestrator: fleet tools " + fleet.catalog().current().stream().map(t -> t.name()).toList());
            Orchestrator orchestrator = AiServices.builder(Orchestrator.class)
                    .chatModel(fleet.chatModel())          // the fleet, served by the worker
                    .toolProvider(fleet.tools())           // the workers, as tools
                    .build();
            String plan = orchestrator.plan(args.length > 0 ? String.join(" ", args)
                    : "Plan the offsite: summarize 'team offsite logistics' in two sentences and price 3 kites.");
            System.out.println("\n" + plan);
        }
    }
}
