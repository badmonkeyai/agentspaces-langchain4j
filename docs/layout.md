# Layout

Written for: LangChain4j developers who build with agentspaces-langchain4j.

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
