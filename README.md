# agentspaces-langchain4j

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
  <version>0.3.0</version>
</dependency>
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.badmonkey.agentspaces:agentspaces-langchain4j:0.3.0")
}
```

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
