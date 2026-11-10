# A peer in plain Java

Written for: LangChain4j developers who build with agentspaces-langchain4j.

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
