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

The starter's replication settings come with it, under the same keys
(srad/replication-by-entry.md appendix D), and most peers leave them out:

- `agentspaces.gossip.audit` (default `true`) advertises `wire.ae=2`, so two
  peers that both advertise it compare a space by its fingerprinted trie and
  fetch only the entries that differ (SPEC annex I.6).
  `agentspaces.gossip.audit-sessions` (4), `.audit-budget` (1048576 bytes) and
  `.probe-hash-streams` (32) bound that work.
- `agentspaces.space.horizon` (default `per-entry`) advertises
  `wire.horizon=1`. Once every member of a space's view does, a withdrawn entry
  is forgotten by its own lease (SPEC §7.3). `legacy` keeps 2 x maxLease; set
  it on a peer that renews for an identity another peer renews at the same
  time.
- `agentspaces.space.renew-shortening-log` (default `true`) logs the first
  renewal that asks for less than its lease has left.
- An unknown key under `agentspaces.gossip` or `agentspaces.space` refuses at
  start and names the keys the block takes.

A renewal never shortens a lease (SPEC §7.2). The take-lease listener renews
a take for its full lease on each model round trip, so it is unaffected. Code
that renews by hand gets at least the extension from now: to end an entry
sooner, cancel it, and write it again with the shorter lease if it should stay.
