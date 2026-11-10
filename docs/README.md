# agentspaces-langchain4j documents

Written for: anyone who read the README and wants the rest of the story.

The README is short by design: what this is, the quick start, and how to add it to a build. Everything else lives here, one topic per document.

| Document | Written for | What it holds |
|---|---|---|
| [About agentspaces-langchain4j](about.md) | LangChain4j developers who build with agentspaces-langchain4j | What AgentSpaces is, and what this project adds to it. |
| [How to use AI with AgentSpaces](how-to-use-ai.md) | LangChain4j developers who build with agentspaces-langchain4j | Tested walk-throughs that build an agent, hand it work from another peer, and survive a crash. |
| [A peer in plain Java](a-peer-in-plain-java.md) | LangChain4j developers who build with agentspaces-langchain4j | LangChain4jFleet fleet = LangChain4jFleet.builder(runtime) .tools() // the fleet's cards as tools .modelClient(ModelClient.of("gpt-4.1-mini")) // the. |
| [What each feature gives you](what-each-feature-gives-you.md) | LangChain4j developers who build with agentspaces-langchain4j | Every feature is off until its settings enable it, so a one-line facade never widens the fleet's reach by accident. |
| [Layout](layout.md) | LangChain4j developers who build with agentspaces-langchain4j | Interoperability. The wire is the core's, so a LangChain4j peer on this library joins fleets with Spring AI, Micronaut, Python, and TypeScript peers. |
