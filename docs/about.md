# About agentspaces-langchain4j

Written for: LangChain4j developers who build with agentspaces-langchain4j.

**A peer-to-peer coordination fabric for agents, services, and the people who work with them.**

AgentSpaces is a P2P framework to quickly discover and join P2P networks, where agents, software services, and people share assets, queries, results, and coordinate. The framework helps you build fleets of agents, tie in other software, and coordinate across them with a replicated tuple space, quorum building, and more. Agents post work, claim tasks, publish results, vote on decisions, and recover from each other's failures. There is no broker, no registry, no scheduler, and no server to keep alive. Coordination is peer-to-peer, secured end to end, and replicated across the fleet.

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
  <version>0.6.0</version>
</dependency>
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.badmonkey.agentspaces:agentspaces-langchain4j:0.6.0")
}
```
