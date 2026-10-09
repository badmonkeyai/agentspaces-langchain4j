# agentspaces-runtime

A peer in plain Java, and the framework-neutral tool catalogue, for hosts
that have no Spring and no Micronaut.

- `FleetRuntime.builder(properties).embedder(..).schemaRegistry(..).participant(..).scheduler(..).start()`:
  the peer from the starter's `AgentSpacesProperties`, built by the starter's
  own build methods (the autoconfigure jar carries no Spring class; the
  Spring names it compiled against are provided-scope stubs), then the node
  ticking, each `FleetParticipant` started in order, and the card refresh and
  OIDC token re-read on the card cadence. `context(group)` gives the
  `FleetContext` a feature works in; `bind(agent)` binds an annotated object;
  `close()` stops it all in reverse.
- `PropertiesLoader.yaml(path)`, `.map(tree)`, `.flat(dottedKeys)`: the
  `agentspaces.*` configuration in any of its forms, with kebab-case keys and
  durations in ISO-8601 or the short form; unknown keys fail at startup.
  `tree(path)` returns the whole document for sections other libraries read.
  YAML needs `jackson-dataformat-yaml` (optional).
- `FleetScheduler`: the host's `ScheduledExecutorService`, or one over
  virtual threads the runtime closes.
- `TakePropagation`: `wrap(Runnable)`, `wrap(Callable)`, `executor(ExecutorService)`
  carry the in-flight take to another thread so a lease renewal from there
  lands. A carried lease keeps the task; it does not make a repeated side
  effect safe.
- `tools`: `FleetToolCatalog` (the live catalogue over `RemoteActions`,
  filtered, named, cached, diffed, with `onChange` listeners), `FleetAction`,
  `FleetTool`, `FleetToolFilter`, `ToolNamingStrategy`, `RecordSchemas`
  (JSON Schema from a record), `ToolInvocation` (strict parsing, invocation,
  timeout outcomes), `ToolSettings`.

The `tools` section of the shared YAML (`prefix`, `include-agents`,
`exclude-agents`, `require-attested`, `timeout`, `on-timeout`,
`refresh-interval`, `max`) binds with `ToolSettings.from(map)`.
