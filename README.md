# agentspaces-langchain4j

## Introducing AgentSpaces by Bad Monkey AI

**A peer-to-peer coordination fabric for agents, services, and the people who work with them.**

AgentSpaces is a P2P framework to quickly discover and join P2P networks, where agents, software services, and people share assets, queries, results, and coordinate. The framework helps you build fleets of agents, tie in other software, and coordinate across them with a replicated tuple space, quorum building, and more. Agents post work, claim tasks, publish results, vote on decisions, and recover from each other's failures. There is no broker, no registry, no scheduler, and no server to keep alive. Coordination is peer-to-peer, secured end to end, and replicated across the fleet.

## Quick start: build your first agent with LangChain4j

An agent is a method. Its parameter is the work it takes, and its return value is the next entry in the space. With LangChain4j the method sits on a plain class and calls a `ChatModel`: the space hands it one `Order` at a time under a lease, the model writes the packing note, and the returned `Shipment` completes the order. The sample uses no application framework, because `FleetRuntime` builds the peer from the same YAML the Spring and Micronaut hosts read.

**1. Add the dependencies.** The library, a LangChain4j model, and Jackson's YAML module, which the runtime uses to read its configuration and does not bring itself (pin Jackson with its BOM).

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-langchain4j</artifactId>
  <version>0.4.0</version>
</dependency>
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-open-ai</artifactId>
  <version>1.20.0</version>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.dataformat</groupId>
  <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

**2. Configure the peers.** The first instance founds the group and listens. The second names the first as its seed. The default `mtls` security profile secures every link.

```yaml
agentspaces:
  bind: 127.0.0.1:7701
  groups:
    - name: shop
      founding: shop-v1
      spaces:
        - name: orders
```

```yaml
agentspaces:
  bind: 127.0.0.1:7702
  groups:
    - name: shop
      founding: shop-v1
      seeds: ["127.0.0.1:7701"]
      spaces:
        - name: orders
```

**3. Write the agent.** Two records name the entries, `@AgentSpec` describes the agent, and `@SpaceTake` says what it takes and how long it may hold it. `fleet.listeners()` renews the take lease on every model round trip, so a long model loop keeps its order. The listings omit imports.

```java
public record Order(String orderId, String item) {
}

public record Shipment(String orderId, String item, String note) {
}
```

```java
@AgentSpec(name = "fulfiller", description = "Ships orders and writes a packing note")
public class Fulfiller {
    private final ChatModel model;

    public Fulfiller(ChatModel model) {
        this.model = model;
    }

    @SpaceTake(lease = "2m")
    public Shipment ship(Order order) {
        String note = model.chat("Write a one-line packing note for: " + order.item());
        return new Shipment(order.orderId(), order.item(), note);
    }
}
```

```java
public class ShopApp {

    public static void main(String[] args) throws Exception {
        try (FleetRuntime runtime = FleetRuntime.start(PropertiesLoader.yaml(Path.of(args[0])))) {
            LangChain4jFleet fleet = LangChain4jFleet.builder(runtime).build();
            ChatModel model = OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .modelName("gpt-4.1-mini")
                    .listeners(fleet.listeners())      // renews the take lease on every model round trip
                    .build();
            runtime.bind(new Fulfiller(model));

            if (args.length > 1) {                     // java ... second.yaml kite
                Space orders = runtime.context().group().space("orders");
                orders.write(new Order("o-1", args[1]), Lease.of(Duration.ofMinutes(10)));
                orders.read(Template.of(Shipment.class), Duration.ofSeconds(30)).ifPresent(System.out::println);
            } else {
                Thread.currentThread().join();
            }
        }
    }
}
```

**4. Run two instances.** Set `OPENAI_API_KEY`, then start the first instance, and a second one that joins through it, writes an order, and prints the shipment.

```
mvn -q compile exec:java -Dexec.mainClass=shop.ShopApp -Dexec.args="first.yaml"
mvn -q compile exec:java -Dexec.mainClass=shop.ShopApp -Dexec.args="second.yaml kite"
```

Either instance can take the order, because both run the same agent.

**5. Kill one.** Add `System.out.println("shipping " + order.orderId());` and `Thread.sleep(10_000);` at the top of `ship` (the method then needs `throws InterruptedException`), and shorten the lease to `@SpaceTake(lease = "30s")`. Start both instances, write an order from the second, and run `kill -9` on whichever instance prints `shipping`. If you killed the first instance, the second prints the shipment. If you killed the second, the first prints `shipping o-1` again when the lease lapses.

The sections below add the rest: fleet tools as a LangChain4j `ToolProvider`, chat memory in a replicated space, model access as a fleet service, and fleet-wide token usage.

## About agentspaces-langchain4j

AgentSpaces for LangChain4j applications on any JVM host. A command-line
agent, an embedded service, a batch job, or a service on a framework
AgentSpaces has no host library for joins a fleet and uses the fleet features
the Spring AI and Micronaut bridges offer (the MCP export stays with those two
hosts), with no application framework on the classpath: the fleet's AgentCards as a LangChain4j `ToolProvider`, a take
lease renewed per model round-trip and carried across threads, chat memory
in a replicated space, model access as a fleet service, real embeddings for
semantic discovery, and fleet-wide token usage. The wire records are
`agentspaces-model-wire`'s, so a plain-Java orchestrator calls a model a
Spring Boot or Micronaut peer serves, and resumes a conversation either of
them started.

Two artifacts:

| Artifact | What it is |
| --- | --- |
| `agentspaces-runtime` | `FleetRuntime`, a peer in plain Java from the same `agentspaces.*` configuration the other hosts read (YAML, a map, or flat keys), with the lifecycle, `FleetContext`, `FleetParticipant`, `TakePropagation`, and the framework-neutral tool catalogue (`FleetToolCatalog`, `FleetAction`, `RecordSchemas`, `ToolInvocation`) every tool bridge shares. No AI library in it. |
| `agentspaces-langchain4j` | `LangChain4jFleet`, the fleet on LangChain4j's extension points, and the classes behind it. |

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-langchain4j</artifactId>
  <version>0.4.0</version>
</dependency>
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.badmonkey.agentspaces:agentspaces-langchain4j:0.4.0")
}
```

## How to use AI with AgentSpaces

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

Source: [`examples/langchain4j-fleet/src/main/java/ai/badmonkey/agentspaces/examples/langchain4j/Workers.java`](examples/langchain4j-fleet/src/main/java/ai/badmonkey/agentspaces/examples/langchain4j/Workers.java).
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
[`examples/langchain4j-fleet/src/test/java/ai/badmonkey/agentspaces/examples/langchain4j/LangChain4jFleetTest.java`](examples/langchain4j-fleet/src/test/java/ai/badmonkey/agentspaces/examples/langchain4j/LangChain4jFleetTest.java),
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

## A peer in plain Java

```java
FleetRuntime runtime = FleetRuntime.start(PropertiesLoader.yaml(Path.of("agentspaces.yaml")));
runtime.bind(new Summarizer());                 // @AgentSpec and @SpaceTake, as anywhere

LangChain4jFleet fleet = LangChain4jFleet.builder(runtime)
        .tools()                                 // the fleet's cards as tools
        .modelClient(ModelClient.of("gpt-4.1-mini"))   // the fleet as the model
        .build();

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(fleet.chatModel())
        .toolProvider(fleet.tools())
        .build();
```

`agentspaces.yaml` carries the starter's keys (`bind`, `groups`, `spaces`,
`security`, ...) and this project's `tools` and `langchain4j` sections with
the Micronaut library's key names, so one file configures a peer under any
host. `PropertiesLoader.map` and `.flat` read the same keys from a map or
dotted properties. Every piece of the peer is built by the Spring starter's
own build methods, which carry no Spring type and run with no Spring on the
classpath; the enforcer rule and a test keep it that way. JDK 21.

## What each feature gives you

| Feature | How |
| --- | --- |
| Fleet tools | `fleet.tools()` is a `ToolProvider`: every call returns the catalogue's current actions (`fleet_<agent>_<InputType>`, JSON Schema from the input record), so the tool set is live. A call writes the input into the agent's take space and awaits the result. `ToolSettings`: prefix, include and exclude agents, `requireAttested`, timeout, `onTimeout`, refresh interval, max. |
| Discovery and data tools | `DiscoveryTools.all()` (or any of `agents`, `assets`, `data`): `find_fleet_agents`, `list_fleet_assets`, `fetch_fleet_data`, each off by default, added to the provider's result. |
| Crash-safe workers | `fleet.listeners()` carries the take-lease listener: pass it to a model builder's `listeners(...)`, or wrap a model with `fleet.listening(model)`. `TakePropagation.wrap(...)` and `.executor(...)` carry the take to other threads, so a renewal from a future or a parallel step still lands. |
| Chat memory | `Memory.on()`: `fleet.memory()` is a `ChatMemoryProvider` over a replicated space; a conversation a Spring AI or Micronaut worker started resumes here. |
| The model service | `ModelClient.of("model")`: `fleet.chatModel()` and `fleet.streamingChatModel()` route calls through the space to the peers that serve them. `ModelServer.on()` and `fleet.modelServer(ModelCatalog.of("model", chatModel, streamingModel))` serve this peer's models. Crash tolerance by lease, routing by model or by price, chunked streaming with reorder and restart policy, cancellation. |
| Embeddings | `FleetRuntime.builder(...).embedder(LangChain4jFleet.embedder(embeddingModel, "name"))` puts a real model behind semantic discovery, advertised on the capability. |
| Usage | `Usage.on()`: token usage as push-sum totals across the fleet, attributed entries in an optional metrics space, and a console panel (`fleet.usagePanel()`). |
| Content tools | `FleetCapabilityTools.Settings.defaults().withContent(true)` (with `agentspaces.content.enabled` on the runtime): `publish_content`, `fetch_content`, `read_content`, `describe_content`, `list_content`, so a model publishes a file as a fleet artifact and reads one another peer published, moved by the content swarm from every holder at once (SPEC v0.1.23 §8). A space entry above `agentspaces.content.escalate-above` travels as an artifact on its own. |

Every feature is off until its settings enable it, so a one-line facade never
widens the fleet's reach by accident. The fleet annotations and return
conventions the runtime passes through to the core binder (`@SpaceNotify`
with `on = EXPIRED`, `@BidFunction` on an AUCTION space, `@SpaceRef`,
`@Propose`, `@OnDecision` with `@Ballot` across two runtimes, `@OnEstimate`,
`@SpaceJoin`, `@SpaceReduce`, `@CapabilityRef`, and the `Tagged`, `Entries`,
`Motion`, and `Contribution` returns) are proven through `FleetRuntime.bind`
by `AnnotationPassthroughTest`, and `LangChain4jNotifyServiceTest` binds an
`AiServices` interface whose method carries `@SpaceNotify` beside
`@UserMessage`. The security notes of the Micronaut
bridge apply unchanged: tool descriptions are model input, `requireAttested`
admits only agents signing with a certified key, model requests are readable
by every peer admitted to the model space, and a caller accepts a response
only from an issuer the authorizer permits `MODEL_SERVE` for the model.
A renewed lease keeps a task; it does not make execution exactly-once: a
worker can finish a side effect and die before completing, and the retry
repeats it, so give consequential tools an idempotency key and check it.

## Layout

```
runtime/        agentspaces-runtime: ai.badmonkey.agentspaces.runtime, ai.badmonkey.agentspaces.tools
langchain4j/    agentspaces-langchain4j: ai.badmonkey.agentspaces.langchain4j (.tools, .worker, .memory, .model, .embed, .usage)
examples/langchain4j-fleet/   example 19: a worker and a keyless orchestrator, with a flow test
```

**Interoperability.** The wire is the core's, so a LangChain4j peer on
this library joins fleets with Spring AI, Micronaut, Python, and TypeScript
peers. `CrossJvmInteropTest` in example 19 spawns a LangChain4j worker as a
separate JVM and drives it from a coordinator in the test's JVM through
cards, tasks, a tool call, and a model call; the workspace's
`run-trilingual-demo.sh` races that worker against the Python and
TypeScript ones for a Java coordinator's tasks; and `MemoryAndWireTest`
reads the golden vectors the Spring AI and Micronaut model services share.

`mvn install` builds and tests both modules; `-Pexamples` adds the example;
`-Plangchain4j-1.22` verifies against the current LangChain4j release (the
default is 1.20.0, the line the Micronaut 5.2 platform resolves, so the
Micronaut adapter runs the same bytes). The Micronaut bridge,
`agentspaces-micronaut-langchain4j`, is wiring over this library.
