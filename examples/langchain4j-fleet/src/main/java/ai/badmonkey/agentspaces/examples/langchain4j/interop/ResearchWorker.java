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

import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.examples.langchain4j.interop.ResearchSchemas.Finding;
import ai.badmonkey.agentspaces.examples.langchain4j.interop.ResearchSchemas.ResearchTask;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jFleet;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings;
import ai.badmonkey.agentspaces.langchain4j.model.ModelCatalog;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.PropertiesLoader;
import ai.badmonkey.agentspaces.tools.ToolSettings;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The research fleet's LangChain4j worker, in its own JVM: a plain-Java peer
 * that dials the coordinator (example 02 in the core, or the cross-JVM test),
 * joins the research group as its own attested agent, takes
 * {@code ResearchTask}s under the claim lattice beside the Java, Python, and
 * TypeScript workers, researches each with a LangChain4j model whose take
 * lease the fleet renews, and completes it with the {@code Finding} the
 * coordinator awaits. It also serves its model to the fleet, so a keyless
 * peer anywhere calls a model that lives in this JVM.
 *
 * <p>{@code ResearchWorker <host> <port> [race|serve]}: {@code race} exits
 * once the space stays quiet, as the Python and TypeScript workers do;
 * {@code serve} runs until stopped. {@code OPENAI_API_KEY} swaps the
 * deterministic model for OpenAI.
 */
public final class ResearchWorker {

    public static final String WORKER = "langchain4j-worker";
    public static final String MODEL = "scripted";
    public static final String FOUNDING = "research-fleet-demo-v1";

    private ResearchWorker() {
    }

    /** Takes research tasks; the result completes the take in the task space. */
    @AgentSpec(name = "researcher", description = "Researches a topic with a LangChain4j model", goals = {"research"})
    public static final class Researcher {
        private final ChatModel model;
        final AtomicInteger completed = new AtomicInteger();
        volatile Instant lastCompleted = Instant.now();

        public Researcher(ChatModel model) {
            this.model = model;
        }

        @SpaceTake(space = "tasks", lease = "2s", pollTimeout = "PT2S")
        public Finding research(ResearchTask task) {
            String summary = model.chat(task.topic());
            completed.incrementAndGet();
            lastCompleted = Instant.now();
            System.out.println("  [" + WORKER + "] completed '" + task.topic() + "' (" + completed.get() + ")");
            return new Finding(task.topic(), summary, WORKER);
        }
    }

    /** The dial-only peer's configuration: the research group, by seed, as an attested agent. */
    public static Map<String, Object> properties(String host, int seedPort) {
        Map<String, Object> p = new HashMap<>();
        p.put("agentspaces.tick-millis", "250");
        // Plain TCP, as the core example and the Python and TypeScript workers speak; the
        // starter's default profile dials TLS, which a TcpTransport listener refuses.
        p.put("agentspaces.security.profile", "dev-local");
        p.put("agentspaces.identity.agent-keys", "subordinate");
        p.put("agentspaces.groups[0].name", "research-fleet");
        p.put("agentspaces.groups[0].founding", FOUNDING);
        p.put("agentspaces.groups[0].seeds[0]", host + ":" + seedPort);
        p.put("agentspaces.groups[0].spaces[0].name", "tasks");
        p.put("agentspaces.groups[0].spaces[0].settle-window-millis", "150");
        p.put("agentspaces.groups[0].spaces[1].name", "model-requests");
        p.put("agentspaces.groups[0].spaces[1].settle-window-millis", "100");
        return p;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7468;
        boolean race = args.length <= 2 || args[2].equals("race");
        ChatModel model = System.getenv("OPENAI_API_KEY") != null
                ? OpenAiChatModel.builder().apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4.1-mini").build()
                : new DeterministicModel(MODEL, "researched by langchain4j in JVM " + ProcessHandle.current().pid() + ": ");
        try (FleetRuntime runtime = FleetRuntime.builder(PropertiesLoader.flat(properties(host, port)))
                .schemaRegistry(new ResearchSchemas())
                .start()) {
            LangChain4jFleet fleet = LangChain4jFleet.builder(runtime)
                    .tools(ToolSettings.defaults())
                    .modelServer(LangChain4jSettings.ModelServer.on())
                    .build();
            Researcher researcher = new Researcher(fleet.listening(model));
            runtime.bind(researcher);
            if (model instanceof DeterministicModel deterministic) {
                fleet.modelServer(ModelCatalog.of(MODEL, deterministic, deterministic.streaming()));
            } else {
                fleet.modelServer(ModelCatalog.of("gpt-4.1-mini", model));
            }
            System.out.println(WORKER + " " + runtime.identity().peerId() + " joined via " + host + ":" + port
                    + " in JVM " + ProcessHandle.current().pid() + (race ? " (race)" : " (serve)"));
            if (race) {
                // Like the Python and TypeScript workers: leave once the space stays quiet after the first task.
                Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
                while (Instant.now().isBefore(deadline)) {
                    Thread.sleep(500);
                    if (researcher.completed.get() > 0
                            && Duration.between(researcher.lastCompleted, Instant.now()).compareTo(Duration.ofSeconds(16)) > 0) {
                        break;
                    }
                }
                System.out.println(WORKER + " done: " + researcher.completed.get() + " tasks");
                System.exit(researcher.completed.get() >= 1 ? 0 : 1);
            }
            Thread.currentThread().join();
        }
    }
}
