# agentspaces-langchain4j

**AgentSpaces for LangChain4j applications on any JVM host**, from Bad Monkey AI. A command-line agent, an embedded service, or a batch job joins a peer-to-peer fleet with no application framework, and its LangChain4j code calls the fleet's agents as tools and its models as a fleet service.

## Quick start: build your first agent with LangChain4j

An agent is a method. Its parameter is the work it takes, and its return value is the next entry in the space. With LangChain4j the method sits on a plain class and calls a `ChatModel`: the space hands it one `Order` at a time under a lease, the model writes the packing note, and the returned `Shipment` completes the order. The sample uses no application framework, because `FleetRuntime` builds the peer from the same YAML the Spring and Micronaut hosts read.

**1. Add the dependencies.** The library, a LangChain4j model, and Jackson's YAML module, which the runtime uses to read its configuration and does not bring itself (pin Jackson with its BOM).

```xml
<dependency>
  <groupId>ai.badmonkey.agentspaces</groupId>
  <artifactId>agentspaces-langchain4j</artifactId>
  <version>0.6.0</version>
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

The [documents](docs/README.md) add the rest: fleet tools as a LangChain4j `ToolProvider`, chat memory in a replicated space, model access as a fleet service, and fleet-wide token usage.

## What it is

- `FleetRuntime`: a peer from the same `agentspaces.*` configuration the Spring starter reads, with no framework on the class path.
- The fleet's agent cards as a LangChain4j `ToolProvider`; a take's lease renewed on every model round trip.
- Chat memory in a replicated space, model access as a fleet service, and real embeddings for semantic discovery.
- The wire records are shared with Spring AI and Micronaut, so a plain-Java orchestrator calls a model a Spring peer serves.
- Java 21; LangChain4j 1.x.

## Add it to your build

The quick start's first step is the whole build: `ai.badmonkey.agentspaces:agentspaces-langchain4j:0.6.0` from Maven Central, a LangChain4j model, and `jackson-dataformat-yaml`, which the runtime uses to read its configuration. [About agentspaces-langchain4j](docs/about.md) lists the two artifacts, and [Layout](docs/layout.md) the modules.

## Where to read next

1. **[The developer guide](https://www.badmonkey.ai/agentspaces/developer-guide.html#langchain4j)**: its LangChain4j chapter covers the runtime, the tools, the model wire, and the other hosts.
2. **[The examples](examples/)**: example 19 runs an orchestrator and two workers on plain Java.
3. **[The documents](docs/README.md)**: everything this README leaves out, one topic per document, each with the reader it is written for.

## License

Apache License 2.0, Copyright 2026 Bad Monkey, Inc. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Contributions are welcome under the Developer Certificate of Origin; see [CONTRIBUTING.md](CONTRIBUTING.md). Report a vulnerability privately, as [SECURITY.md](SECURITY.md) describes.
