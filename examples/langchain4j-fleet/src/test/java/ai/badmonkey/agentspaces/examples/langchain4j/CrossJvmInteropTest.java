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

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet;
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.Finding;
import ai.badmonkey.agentspaces.examples.fleet.ResearchFleet.ResearchTask;
import ai.badmonkey.agentspaces.examples.langchain4j.interop.ResearchSchemas;
import ai.badmonkey.agentspaces.examples.langchain4j.interop.ResearchWorker;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jFleet;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.PropertiesLoader;
import ai.badmonkey.agentspaces.tools.ToolSettings;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interoperability across JVMs and codebases. This JVM runs two peers: the
 * research coordinator of the core's example 02, rebuilt from the core API
 * alone on plain TCP with its own record classes and the default naming
 * ({@link ResearchFleet}), and a keyless orchestrator on this library that
 * joins through it. {@link ResearchWorker} runs as a separate JVM that dials
 * the coordinator. Four things cross the process boundary: the worker's
 * AgentCard (seen by the orchestrator as a fleet tool), the coordinator's
 * tasks the worker takes and the findings it completes them with under the
 * coordinator's wire names, a tool call an {@code AiService} here makes to
 * it, and a model call served by the model that lives over there.
 */
class CrossJvmInteropTest {

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /**
     * The orchestrator on this library: plain TCP, joining the research group
     * through the coordinator, and listening itself so the worker, which
     * learns of it through membership, can dial it for cards and results.
     */
    private static Map<String, Object> orchestrator(int seedPort, int ownPort) {
        Map<String, Object> p = new HashMap<>();
        p.put("agentspaces.bind", "127.0.0.1:" + ownPort);
        p.put("agentspaces.tick-millis", "200");
        p.put("agentspaces.security.profile", "dev-local");
        p.put("agentspaces.groups[0].name", "research-fleet");
        p.put("agentspaces.groups[0].founding", ResearchWorker.FOUNDING);
        p.put("agentspaces.groups[0].seeds[0]", "127.0.0.1:" + seedPort);
        p.put("agentspaces.groups[0].spaces[0].name", "tasks");
        p.put("agentspaces.groups[0].spaces[0].settle-window-millis", "150");
        p.put("agentspaces.groups[0].spaces[1].name", "model-requests");
        p.put("agentspaces.groups[0].spaces[1].settle-window-millis", "100");
        return p;
    }

    /** The worker as a child JVM on this test's classpath. */
    private static Process spawnWorker(int port) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java, "-cp", classpath,
                ResearchWorker.class.getName(), "127.0.0.1", String.valueOf(port), "serve"));
        return new ProcessBuilder(command).inheritIO().start();
    }

    @Test
    @Timeout(180)
    void aLangChain4jWorkerInAnotherJvmTakesTasksServesToolCallsAndServesItsModel() throws Exception {
        int port = freePort();
        try (ResearchFleet.Peer coordinator = ResearchFleet.startPeer("coordinator", port);
             FleetRuntime orchestratorPeer = FleetRuntime.builder(PropertiesLoader.flat(orchestrator(port, freePort())))
                     .schemaRegistry(new ResearchSchemas()).start()) {
            Process worker = spawnWorker(port);
            try {
                LangChain4jFleet fleet = LangChain4jFleet.builder(orchestratorPeer)
                        .tools(ToolSettings.builder().refreshInterval(Duration.ofMillis(500)).build())
                        .modelClient(LangChain4jSettings.ModelClient.of(ResearchWorker.MODEL))
                        .build();

                // 1. The other JVM's card arrives and is a tool here.
                assertThat(await(Duration.ofSeconds(60), () -> !fleet.catalog().current().isEmpty()))
                        .as("the worker's card reaches this JVM").isTrue();
                assertThat(fleet.catalog().current()).extracting("name").containsExactly("fleet_researcher_ResearchTask");
                assertThat(fleet.catalog().current().get(0).action().issuer()).isNotEqualTo(orchestratorPeer.identity().peerId());

                // 2. Tasks the core-API coordinator writes under its own names are completed over there.
                Space tasks = coordinator.space();
                for (int i = 1; i <= 3; i++) {
                    tasks.write(new ResearchTask("topic-" + i, i), Lease.of(Duration.ofMinutes(30)));
                }
                assertThat(await(Duration.ofSeconds(60), () -> tasks.readAll(Template.of(Finding.class), 10).size() >= 3)).isTrue();
                List<Finding> findings = tasks.readAll(Template.of(Finding.class), 10);
                assertThat(findings).hasSize(3).allSatisfy(f -> {
                    assertThat(f.worker()).isEqualTo(ResearchWorker.WORKER);
                    assertThat(f.summary()).startsWith("researched by langchain4j in JVM ").contains(f.topic());
                });
                assertThat(findings.get(0).summary()).doesNotContain("JVM " + ProcessHandle.current().pid() + ":");
                assertThat(tasks.readAllIssued(Template.of(Finding.class), 10)).allSatisfy(issued -> {
                    assertThat(issued.issuer().peer()).isNotEqualTo(coordinator.identity().peerId());
                    assertThat(issued.issuer().peer()).isNotEqualTo(orchestratorPeer.identity().peerId());
                });
                // The orchestrator reads the same findings through its registry's mapping.
                assertThat(await(Duration.ofSeconds(30), () -> orchestratorPeer.context().group().space("tasks")
                        .readAll(Template.of(ResearchSchemas.Finding.class), 10).size() >= 3)).isTrue();

                // 3. An AiService here calls the worker there as a tool.
                ScriptedChatModel orchestratorModel = new ScriptedChatModel("orchestrator")
                        .then(request -> AiMessage.from(ToolExecutionRequest.builder().id("c1")
                                .name("fleet_researcher_ResearchTask")
                                .arguments("{\"topic\":\"interop\",\"priority\":1}").build()))
                        .then(request -> AiMessage.from("plan: " + request.messages().stream()
                                .filter(ToolExecutionResultMessage.class::isInstance)
                                .map(m -> ((ToolExecutionResultMessage) m).text()).toList()));
                Orchestrator service = AiServices.builder(Orchestrator.class)
                        .chatModel(orchestratorModel).toolProvider(fleet.tools()).build();
                String plan = service.plan("Research interop");
                assertThat(plan).startsWith("plan: ").contains("researched by langchain4j in JVM ").contains("interop")
                        .contains(ResearchWorker.WORKER);

                // 4. A model call from this keyless JVM is served by the model over there.
                ChatResponse response = fleet.chatModel().chat(ChatRequest.builder()
                        .messages(UserMessage.from("hello")).build());
                assertThat(response.aiMessage().text()).startsWith("researched by langchain4j in JVM ").endsWith(": hello");
                assertThat(response.modelName()).isEqualTo(ResearchWorker.MODEL);
                assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(12);
            } finally {
                worker.destroy();
                if (!worker.waitFor(15, TimeUnit.SECONDS)) {
                    worker.destroyForcibly();
                }
            }
        }
    }
}
