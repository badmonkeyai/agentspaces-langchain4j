# What each feature gives you

Written for: LangChain4j developers who build with agentspaces-langchain4j.

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
| PDT tools | `FleetCapabilityTools.Settings.defaults().withPdt(true)` (with `agentspaces.pdt.enabled` and `agentspaces.content.enabled` on the runtime): `pdt_read`, `pdt_assoc`, `pdt_history`, so a model reads a path of a persistent map ref, puts a value at a path as a new version every member merges, and lists the versions; `pdt_pin` asks the group's content stores to hold a root and `pdt_store_status` shows the stores that hold it, with a store started by `agentspaces.store.enabled` on any runtime. `FleetContext.pdt()` hands out the group's `Pdt` facade to plain Java. |

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
