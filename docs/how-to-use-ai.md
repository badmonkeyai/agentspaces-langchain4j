# How to use AI with AgentSpaces

Written for: LangChain4j developers who build with agentspaces-langchain4j.

At the end of this section you will have two plain-Java workers that take
work from a shared space, one of them answering with a LangChain4j model, an
`AiServices` orchestrator on another peer that holds no provider key and gets
both its tools and its model from the fleet, and a worker whose death
mid-task hands the task to another worker. No application framework is on
the classpath: `FleetRuntime` builds the peer from the same `agentspaces.*`
keys the Spring and Micronaut hosts read. Every snippet is cut from
checked-in code that runs under the build gate; the file and the test are
named beside each one, and imports and Javadoc are elided.

**Build an agent.** A worker is a class with `@AgentSpec` and one
`@SpaceTake` method; the parameter is what it takes, the return value is the
result it completes the take with. The summarizer holds any LangChain4j
`ChatModel`; the pricer needs none.

```java
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
```

Source: [`examples/langchain4j-fleet/src/main/java/ai/badmonkey/agentspaces/examples/langchain4j/Workers.java`](../examples/langchain4j-fleet/src/main/java/ai/badmonkey/agentspaces/examples/langchain4j/Workers.java).
`WorkerApp` binds both with `runtime.bind(new Workers.Summarizer(model))` and
`runtime.bind(new Workers.Pricer())` after `FleetRuntime.start(...)` reads
`worker.yaml`.

**Share work through the space, from a peer with no key.** The worker peer
serves its model to the fleet; the orchestrator peer gets a `ToolProvider`
whose tools are the workers' cards and a `ChatModel` whose provider is the
fleet, and hands both to an ordinary `AiServices` interface (`Orchestrator`,
a `@SystemMessage` and one `plan(@UserMessage String)` method). Each model
round-trip is a `ModelRequest` entry the worker takes; each tool call is a
task the matching worker takes.

```java
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
    Orchestrator service = AiServices.builder(Orchestrator.class)
            .chatModel(fleet.chatModel())
            .toolProvider(fleet.tools())
            .build();
    String plan = service.plan("Plan the offsite");
    assertThat(plan).startsWith("plan: ").contains("a tidy summary").contains("37.5");
    assertThat(servedModel.requests()).as("three round-trips served through the space").hasSize(3);
}
```

`peer(port, seedPort)` is the starter's keys as a flat map (`agentspaces.bind`,
`groups[0].name`, `groups[0].founding`, `groups[0].seeds[0]`, and the `tasks`
and `model-requests` spaces), the same keys `worker.yaml` and
`orchestrator.yaml` carry. Source:
[`examples/langchain4j-fleet/src/test/java/ai/badmonkey/agentspaces/examples/langchain4j/LangChain4jFleetTest.java`](../examples/langchain4j-fleet/src/test/java/ai/badmonkey/agentspaces/examples/langchain4j/LangChain4jFleetTest.java),
test `theOrchestratorPlansWithBothFleetToolsOverTheFleetsModel`; run with
`mvn -q -o -Pexamples test -pl examples/langchain4j-fleet -Dtest=LangChain4jFleetTest -Dsurefire.failIfNoSpecifiedTests=false`
(two peers over loopback TCP on free ports, scripted models, no key). The same
shape against a real provider is `WorkerApp` and `OrchestratorApp` in the
example, run from `examples/langchain4j-fleet` with `OPENAI_API_KEY` set for
the worker only:
`mvn -q compile exec:java -Dexec.mainClass=ai.badmonkey.agentspaces.examples.langchain4j.WorkerApp`,
then the same with `OrchestratorApp`.

**Survive a crash.** A worker that stops making progress stops renewing its
take lease. When it dies, the lease lapses and the task reappears for a
healthy worker on another peer, which completes it once; the writer never
retries and no queue redelivers.

```java
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
```

Source: the same `LangChain4jFleetTest`, test
`aWorkerThatDiesMidTaskLeavesItForAnother`. For a worker whose model loop is
the long part, pass `fleet.listeners()` to the model builder or wrap the model
with `fleet.listening(model)`: the take-lease listener renews the lease on
every model round-trip, so progress keeps the task and a two-second lease is
enough. A renewed lease keeps a task; it does not make a side effect
exactly-once, so give consequential tools an idempotency key.
