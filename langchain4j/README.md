# agentspaces-langchain4j (library)

`LangChain4jFleet` and the classes behind it. Build one per peer over a
`FleetRuntime` (its participants register with the runtime) or over a bare
`FleetContext` and `FleetScheduler` (the host starts `participants()`).

| Package | Classes |
| --- | --- |
| `tools` | `FleetToolProvider` (a `ToolProvider`), `FleetDiscoveryTools` (`@Tool` methods), `JsonSchemas` |
| `worker` | `TakeLeaseListener` (a `ChatModelListener`), `ListeningChatModel` |
| `memory` | `SpaceChatMemoryStore` (a `ChatMemoryStore`), `FleetChatMemoryProvider`, `ConversationIdResolver` |
| `model` | `FleetChatModel`, `FleetStreamingChatModel`, `ModelServer`, `ModelCatalog`, `ModelRequestRouter`, `StreamChunkingPolicy`, `StreamRestartPolicy`, `ModelStreamAssembler`, `WireMapping` |
| `embed` | `LangChain4jEmbedder` (the core `Embedder` SPI over an `EmbeddingModel`) |
| `usage` | `FleetUsageListener`, `UsageSink`, `FleetUsage`, `FleetUsagePublisher`, `UsagePanel` |

`LangChain4jSettings` holds every feature's settings as records with
defaults (`DiscoveryTools`, `TakeLease`, `Memory`, `ModelClient`,
`ModelServer`, `Embedder`, `Usage`), built in code or bound from the
`langchain4j` section of the shared YAML with `LangChain4jSettings.from(map)`.
The replaceable decisions are interfaces with defaults: `FleetToolFilter`,
`ToolNamingStrategy`, `ConversationIdResolver`, `ModelCatalog`,
`ModelRequestRouter`, `StreamChunkingPolicy`, `StreamRestartPolicy`,
`UsageSink`.

The protocol on the wire is `agentspaces-model-wire`'s and is byte-compatible
with the Spring AI and Micronaut model services; `MemoryAndWireTest` reads
the shared golden vectors.
