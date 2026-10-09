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
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jFleet;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings;
import ai.badmonkey.agentspaces.langchain4j.model.ModelCatalog;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.PropertiesLoader;
import ai.badmonkey.agentspaces.springai.model.wire.ModelUsage;
import ai.badmonkey.agentspaces.tools.ToolSettings;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example's shape in one JVM over TCP on free ports, with scripted
 * models: the orchestrator's plan comes from both fleet tools, its model
 * round-trips are served through the space by the worker, usage is recorded,
 * and a worker that dies mid-task leaves it for another to finish.
 */
class LangChain4jFleetTest {

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> peer(int port, int seedPort) {
        Map<String, Object> p = new HashMap<>();
        p.put("agentspaces.bind", "127.0.0.1:" + port);
        p.put("agentspaces.tick-millis", "200");
        p.put("agentspaces.groups[0].name", "ai-fleet");
        p.put("agentspaces.groups[0].founding", "langchain4j-ai-fleet-test-v1");
        p.put("agentspaces.groups[0].spaces[0].name", "tasks");
        p.put("agentspaces.groups[0].spaces[0].settle-window-millis", "100");
        p.put("agentspaces.groups[0].spaces[1].name", "model-requests");
        p.put("agentspaces.groups[0].spaces[1].settle-window-millis", "100");
        if (seedPort > 0) {
            p.put("agentspaces.groups[0].seeds[0]", "127.0.0.1:" + seedPort);
        }
        return p;
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

    @Test
    @Timeout(180)
    void theOrchestratorPlansWithBothFleetToolsOverTheFleetsModel() {
        int seedPort = freePort();
        List<ModelUsage> usage = new CopyOnWriteArrayList<>();
        try (FleetRuntime worker = FleetRuntime.start(PropertiesLoader.flat(peer(seedPort, 0)));
             FleetRuntime orchestrator = FleetRuntime.start(PropertiesLoader.flat(peer(freePort(), seedPort)))) {
            // The worker: one scripted model for its own summarizer, another it serves to the fleet.
            LangChain4jFleet serving = LangChain4jFleet.builder(worker)
                    .modelServer(LangChain4jSettings.ModelServer.on())
                    .usage(LangChain4jSettings.Usage.on()).usageSink(usage::add)
                    .build();
            ScriptedChatModel summarizerModel = new ScriptedChatModel("scripted").thenText("a tidy summary");
            worker.bind(new Workers.Summarizer(serving.listening(summarizerModel)));
            worker.bind(new Workers.Pricer());
            // The served model drives the orchestrator through both tools, then answers from their results.
            ScriptedChatModel servedModel = new ScriptedChatModel("scripted")
                    .then(request -> AiMessage.from(
                            ToolExecutionRequest.builder().id("c1").name("fleet_summarizer_Brief")
                                    .arguments("{\"topic\":\"offsite\",\"sentences\":2}").build()))
                    .then(request -> AiMessage.from(
                            ToolExecutionRequest.builder().id("c2").name("fleet_pricer_PriceRequest")
                                    .arguments("{\"item\":\"kite\",\"quantity\":3}").build()))
                    .then(request -> AiMessage.from("plan: " + request.messages().stream()
                            .filter(ToolExecutionResultMessage.class::isInstance)
                            .map(m -> ((ToolExecutionResultMessage) m).text()).toList()));
            serving.modelServer(ModelCatalog.of("scripted", servedModel, new ScriptedStreamingChatModel(servedModel)));

            // The orchestrator: keyless; tools and model from the fleet.
            LangChain4jFleet fleet = LangChain4jFleet.builder(orchestrator)
                    .tools(ToolSettings.builder().refreshInterval(Duration.ofMillis(500)).build())
                    .modelClient(LangChain4jSettings.ModelClient.of("scripted"))
                    .build();
            assertThat(await(Duration.ofSeconds(30), () -> fleet.catalog().current().size() == 2)).isTrue();
            assertThat(fleet.catalog().current()).extracting("name")
                    .containsExactlyInAnyOrder("fleet_summarizer_Brief", "fleet_pricer_PriceRequest");

            Orchestrator service = AiServices.builder(Orchestrator.class)
                    .chatModel(fleet.chatModel())
                    .toolProvider(fleet.tools())
                    .build();
            String plan = service.plan("Plan the offsite");
            assertThat(plan).startsWith("plan: ").contains("a tidy summary").contains("37.5");
            assertThat(servedModel.requests()).as("three round-trips served through the space").hasSize(3);
            assertThat(await(Duration.ofSeconds(10), () -> usage.size() >= 1)).as("the worker's summarizer call was recorded").isTrue();
            assertThat(usage).allSatisfy(u -> assertThat(u.model()).isEqualTo("scripted"));
        }
    }

    /** A pricer that never finishes: its lease lapses and the task reappears for another worker. */
    @AgentSpec(name = "pricer", description = "Prices an item order, slowly", goals = {"price orders"})
    public static class StuckPricer {
        final CountDownLatch entered = new CountDownLatch(1);

        @SpaceTake(space = "tasks", lease = "2s", pollTimeout = "PT0.3S")
        public Quote price(PriceRequest request) throws InterruptedException {
            entered.countDown();
            Thread.sleep(Duration.ofMinutes(5));
            return null;
        }
    }

    @Test
    @Timeout(120)
    void aWorkerThatDiesMidTaskLeavesItForAnother() throws Exception {
        int seedPort = freePort();
        try (FleetRuntime stuck = FleetRuntime.start(PropertiesLoader.flat(peer(seedPort, 0)));
             FleetRuntime healthy = FleetRuntime.start(PropertiesLoader.flat(peer(freePort(), seedPort)))) {
            StuckPricer stuckPricer = new StuckPricer();
            stuck.bind(stuckPricer);
            Space tasks = stuck.context().group().space("tasks");
            tasks.write(new PriceRequest("compass", 2), Lease.of(Duration.ofMinutes(5)));
            assertThat(stuckPricer.entered.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // A healthy pricer joins; the stuck one's two-second lease lapses and the task reappears.
            healthy.bind(new Workers.Pricer());
            Space view = healthy.context().group().space("tasks");
            assertThat(await(Duration.ofSeconds(60), () -> !view.readAll(Template.of(Quote.class), 10).isEmpty())).isTrue();
            assertThat(view.readAll(Template.of(Quote.class), 10)).singleElement()
                    .satisfies(q -> assertThat(q.total()).isEqualTo(18.0));
        }
    }
}
