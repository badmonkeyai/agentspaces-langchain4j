/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.langchain4j;

import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.api.spi.Embedder;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.langchain4j.embed.LangChain4jEmbedder;
import ai.badmonkey.agentspaces.langchain4j.memory.ConversationIdResolver;
import ai.badmonkey.agentspaces.langchain4j.memory.FleetChatMemoryProvider;
import ai.badmonkey.agentspaces.langchain4j.memory.SpaceChatMemoryStore;
import ai.badmonkey.agentspaces.langchain4j.model.FleetChatModel;
import ai.badmonkey.agentspaces.langchain4j.model.FleetStreamingChatModel;
import ai.badmonkey.agentspaces.langchain4j.model.ModelCatalog;
import ai.badmonkey.agentspaces.langchain4j.model.ModelRequestRouter;
import ai.badmonkey.agentspaces.langchain4j.model.ModelServer;
import ai.badmonkey.agentspaces.langchain4j.model.StreamChunkingPolicy;
import ai.badmonkey.agentspaces.langchain4j.model.StreamRestartPolicy;
import ai.badmonkey.agentspaces.langchain4j.tools.FleetCapabilityTools;
import ai.badmonkey.agentspaces.langchain4j.tools.FleetDiscoveryTools;
import ai.badmonkey.agentspaces.langchain4j.tools.FleetToolProvider;
import ai.badmonkey.agentspaces.langchain4j.usage.FleetUsage;
import ai.badmonkey.agentspaces.langchain4j.usage.FleetUsageListener;
import ai.badmonkey.agentspaces.langchain4j.usage.FleetUsagePublisher;
import ai.badmonkey.agentspaces.langchain4j.usage.UsagePanel;
import ai.badmonkey.agentspaces.langchain4j.usage.UsageSink;
import ai.badmonkey.agentspaces.langchain4j.worker.ListeningChatModel;
import ai.badmonkey.agentspaces.langchain4j.worker.TakeLeaseListener;
import ai.badmonkey.agentspaces.runtime.FleetContext;
import ai.badmonkey.agentspaces.runtime.FleetParticipant;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.FleetScheduler;
import ai.badmonkey.agentspaces.tools.FleetToolCatalog;
import ai.badmonkey.agentspaces.tools.FleetToolFilter;
import ai.badmonkey.agentspaces.tools.ToolNamingStrategy;
import ai.badmonkey.agentspaces.tools.ToolSettings;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The fleet on LangChain4j's extension points, built once per peer and
 * handed to {@code AiServices}: the tool provider, the listeners, the chat
 * memory, the fleet model client, and the model server. Each feature is
 * built only when its settings enable it, so a one-line facade never widens
 * the fleet's reach by accident. An {@code AiServices} interface whose methods
 * carry the fleet annotations ({@code @SpaceTake}, {@code @Ballot}, ...)
 * beside LangChain4j's binds as a worker through {@code FleetRuntime.bind},
 * since the binder reads annotations through the proxy's interface. Built over a {@link FleetRuntime}, it
 * registers its participants (the tool catalogue, the model server, the
 * usage publisher) with the runtime; built over a bare {@link FleetContext},
 * the host starts {@link #participants()} itself.
 *
 * <pre>{@code
 * LangChain4jFleet fleet = LangChain4jFleet.builder(runtime)
 *         .tools(ToolSettings.defaults())
 *         .build();
 * Assistant assistant = AiServices.builder(Assistant.class)
 *         .chatModel(fleet.listening(openAiModel))
 *         .toolProvider(fleet.tools())
 *         .build();
 * }</pre>
 */
public final class LangChain4jFleet {

    private final FleetContext fleet;
    private final FleetScheduler scheduler;
    private final LangChain4jSettings settings;
    private final FleetToolCatalog catalog;
    private final FleetDiscoveryTools discoveryTools;
    private final FleetCapabilityTools capabilityTools;
    private final FleetToolProvider tools;
    private final TakeLeaseListener takeLease;
    private final FleetUsage usage;
    private final FleetUsageListener usageListener;
    private final FleetUsagePublisher usagePublisher;
    private final SpaceChatMemoryStore memoryStore;
    private final FleetChatMemoryProvider memory;
    private final FleetChatModel chatModel;
    private final FleetStreamingChatModel streamingChatModel;
    private final List<FleetParticipant> participants = new ArrayList<>();
    private final Consumer<FleetParticipant> registrar;

    private LangChain4jFleet(Builder b) {
        this.fleet = b.fleet;
        this.scheduler = b.scheduler;
        this.settings = new LangChain4jSettings(b.discoveryTools, b.takeLease, b.memory, b.modelClient,
                b.modelServer, b.embedder, b.usage);
        this.registrar = b.registrar;

        // Tools.
        if (b.tools != null) {
            FleetToolFilter filter = b.toolFilter != null ? b.toolFilter : b.tools.filter();
            ToolNamingStrategy naming = b.toolNaming != null ? b.toolNaming : b.tools.naming();
            this.catalog = new FleetToolCatalog(fleet, b.tools, filter, naming, scheduler);
            participants.add(catalog);
        } else {
            this.catalog = null;
        }
        this.discoveryTools = b.discoveryTools.any() ? new FleetDiscoveryTools(fleet, b.discoveryTools, catalog) : null;
        this.capabilityTools = b.capabilityTools.any() ? new FleetCapabilityTools(fleet, b.capabilityTools) : null;
        this.tools = catalog != null || discoveryTools != null || capabilityTools != null
                ? new FleetToolProvider(catalog, discoveryTools, capabilityTools) : null;

        // Listeners.
        this.takeLease = b.takeLease.enabled() ? new TakeLeaseListener(scheduler, b.takeLease.streamRenewInterval()) : null;
        if (b.usage.enabled()) {
            PushSumAggregate aggregate = fleet.group().provider(PushSumAggregate.TYPE)
                    .filter(PushSumAggregate.class::isInstance).map(PushSumAggregate.class::cast).orElse(null);
            this.usage = new FleetUsage(aggregate,
                    b.usage.metricsSpace().isBlank() ? null
                            : fleet.requiredSpace(b.usage.metricsSpace(), "agentspaces.langchain4j.usage.metrics-space"),
                    b.usage.entryLease(), b.usage.publishInterval(), Clock.systemUTC());
            UsageSink sink = b.usageSink != null ? b.usageSink : usage;
            this.usageListener = new FleetUsageListener(sink, fleet.peerId().value());
            this.usagePublisher = new FleetUsagePublisher(usage, scheduler, b.usage.publishInterval());
            participants.add(usagePublisher);
        } else {
            this.usage = null;
            this.usageListener = null;
            this.usagePublisher = null;
        }

        // Memory.
        if (b.memory.enabled()) {
            this.memoryStore = new SpaceChatMemoryStore(
                    fleet.requiredSpace(b.memory.space(), "agentspaces.langchain4j.memory.space"), b.memory.ttl());
            ConversationIdResolver resolver = b.conversationIdResolver != null ? b.conversationIdResolver
                    : ConversationIdResolver.of(b.memory.conversationId());
            this.memory = new FleetChatMemoryProvider(memoryStore, resolver, b.memory.maxMessages());
        } else {
            this.memoryStore = null;
            this.memory = null;
        }

        // The model client.
        if (b.modelClient.enabled()) {
            StreamRestartPolicy restart = b.restartPolicy != null ? b.restartPolicy
                    : StreamRestartPolicy.of(b.modelClient.onRestart());
            this.chatModel = new FleetChatModel(
                    fleet.requiredSpace(b.modelClient.space(), "agentspaces.langchain4j.model-client.space"),
                    fleet.authorizer(), fleet.peerId().value(), b.modelClient.model(), b.modelClient.timeout(),
                    restart, scheduler, listeners());
            this.streamingChatModel = new FleetStreamingChatModel(chatModel);
        } else {
            this.chatModel = null;
            this.streamingChatModel = null;
        }

        participants.forEach(registrar);
    }

    /** Over a runtime: its first group, its scheduler, and its participant list. */
    public static Builder builder(FleetRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new Builder(runtime.context(), runtime.scheduler(), runtime::participant);
    }

    /** Over a runtime, in a named group. */
    public static Builder builder(FleetRuntime runtime, String groupName) {
        Objects.requireNonNull(runtime, "runtime");
        return new Builder(runtime.context(groupName), runtime.scheduler(), runtime::participant);
    }

    /** Over a bare context and scheduler; the host starts {@link #participants()} itself. */
    public static Builder builder(FleetContext fleet, FleetScheduler scheduler) {
        return new Builder(fleet, scheduler, participant -> { });
    }

    /** A LangChain4j embedding model behind the core {@code Embedder} SPI, for {@code FleetRuntime.Builder.embedder}. */
    public static Embedder embedder(EmbeddingModel model, LangChain4jSettings.Embedder settings) {
        return new LangChain4jEmbedder(model, settings.model(), settings.cacheSize());
    }

    /** A LangChain4j embedding model behind the core {@code Embedder} SPI, with the default cache. */
    public static Embedder embedder(EmbeddingModel model, String modelName) {
        return embedder(model, new LangChain4jSettings.Embedder(modelName, LangChain4jSettings.Embedder.defaults().cacheSize()));
    }

    public FleetContext fleet() {
        return fleet;
    }

    public LangChain4jSettings settings() {
        return settings;
    }

    /** The participants this facade built, for a host that starts them itself. */
    public List<FleetParticipant> participants() {
        return List.copyOf(participants);
    }

    /** The tool catalogue; present when tools were enabled. */
    public FleetToolCatalog catalog() {
        return require(catalog, "tools", "builder.tools(ToolSettings)");
    }

    /** The fleet's tools (and the enabled discovery tools) as a LangChain4j {@code ToolProvider}. */
    public FleetToolProvider tools() {
        return require(tools, "tools", "builder.tools(ToolSettings) or builder.discoveryTools(...)");
    }

    /** The discovery tools, for {@code AiServices.tools(...)}; present when any is enabled. */
    public FleetDiscoveryTools discoveryTools() {
        return require(discoveryTools, "discovery tools", "builder.discoveryTools(DiscoveryTools)");
    }

    /** The layer 4 tools (votes, push-sum), for {@code AiServices.tools(...)}; present when any is enabled. */
    public FleetCapabilityTools capabilityTools() {
        return require(capabilityTools, "capability tools", "builder.capabilityTools(FleetCapabilityTools.Settings)");
    }

    /** The listeners this peer's models should carry: the take-lease and usage listeners that are enabled. */
    public List<ChatModelListener> listeners() {
        List<ChatModelListener> all = new ArrayList<>();
        if (takeLease != null) {
            all.add(takeLease);
        }
        if (usageListener != null) {
            all.add(usageListener);
        }
        return List.copyOf(all);
    }

    /** The take-lease listener, when enabled. */
    public TakeLeaseListener takeLeaseListener() {
        return require(takeLease, "take lease", "builder.takeLease(TakeLease)");
    }

    /** A model wrapped with {@link #listeners()}, for a model built without them. */
    public ChatModel listening(ChatModel model) {
        return new ListeningChatModel(model, listeners());
    }

    /** The chat memory provider in the replicated space; present when memory was enabled. */
    public FleetChatMemoryProvider memory() {
        return require(memory, "memory", "builder.memory(Memory)");
    }

    /** The chat memory store behind {@link #memory()}. */
    public SpaceChatMemoryStore memoryStore() {
        return require(memoryStore, "memory", "builder.memory(Memory)");
    }

    /** The fleet as a {@code ChatModel}; present when the model client was enabled. */
    public FleetChatModel chatModel() {
        return require(chatModel, "model client", "builder.modelClient(ModelClient)");
    }

    /** The fleet as a {@code StreamingChatModel}; present when the model client was enabled. */
    public FleetStreamingChatModel streamingChatModel() {
        return require(streamingChatModel, "model client", "builder.modelClient(ModelClient)");
    }

    /**
     * A model server over this peer's models, started with the runtime (or
     * returned for the host to start). Warns when the peer lacks
     * {@code MODEL_SERVE} for a served model, since callers would ignore its
     * answers.
     */
    public ModelServer modelServer(ModelCatalog catalog) {
        LangChain4jSettings.ModelServer server = settings.modelServer();
        for (String model : catalog.models()) {
            if (!fleet.authorizer().permits(fleet.peerId(), Authorizer.Operation.MODEL_SERVE, model)) {
                LoggerFactory.getLogger(ModelServer.class).warn("this peer is not permitted MODEL_SERVE for '{}';"
                        + " callers will ignore its answers (agentspaces.security.grants.model-serve)", model);
            }
        }
        ModelRequestRouter router = server.routing() == LangChain4jSettings.Routing.PRICE
                ? ModelRequestRouter.byPrice(server.prices()) : ModelRequestRouter.byModel();
        ModelServer built = new ModelServer(
                fleet.requiredSpace(server.space(), "agentspaces.langchain4j.model-server.space"),
                catalog, router, StreamChunkingPolicy.of(server.chunkInterval(), server.chunkMaxDeltas()), scheduler,
                fleet.peerId().value(),
                new ModelServer.Settings(server.lease(), server.chunkLease(), server.responseLease(), server.concurrency()));
        participants.add(built);
        registrar.accept(built);
        return built;
    }

    /** One model served under one name. */
    public ModelServer modelServer(String modelName, ChatModel model) {
        return modelServer(ModelCatalog.of(modelName, model));
    }

    /** The fleet-wide usage aggregate; present when usage was enabled. */
    public FleetUsage usage() {
        return require(usage, "usage", "builder.usage(Usage)");
    }

    /** A console panel over the usage aggregate, for a peer that runs the fleet console. */
    public UsagePanel usagePanel() {
        return new UsagePanel(usage());
    }

    private static <T> T require(T value, String feature, String how) {
        if (value == null) {
            throw new IllegalStateException("the " + feature + " feature is off on this LangChain4jFleet; enable it with " + how);
        }
        return value;
    }

    /** What the facade builds; every feature is off until enabled. */
    public static final class Builder {
        private final FleetContext fleet;
        private final FleetScheduler scheduler;
        private final Consumer<FleetParticipant> registrar;
        private ToolSettings tools;
        private FleetToolFilter toolFilter;
        private ToolNamingStrategy toolNaming;
        private LangChain4jSettings.DiscoveryTools discoveryTools = LangChain4jSettings.DiscoveryTools.defaults();
        private FleetCapabilityTools.Settings capabilityTools = FleetCapabilityTools.Settings.defaults();
        private LangChain4jSettings.TakeLease takeLease = LangChain4jSettings.TakeLease.defaults();
        private LangChain4jSettings.Memory memory = LangChain4jSettings.Memory.defaults();
        private ConversationIdResolver conversationIdResolver;
        private LangChain4jSettings.ModelClient modelClient = LangChain4jSettings.ModelClient.defaults();
        private StreamRestartPolicy restartPolicy;
        private LangChain4jSettings.ModelServer modelServer = LangChain4jSettings.ModelServer.defaults();
        private LangChain4jSettings.Embedder embedder = LangChain4jSettings.Embedder.defaults();
        private LangChain4jSettings.Usage usage = LangChain4jSettings.Usage.defaults();
        private UsageSink usageSink;

        private Builder(FleetContext fleet, FleetScheduler scheduler, Consumer<FleetParticipant> registrar) {
            this.fleet = Objects.requireNonNull(fleet, "fleet");
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            this.registrar = registrar;
        }

        /** Every feature from one bound settings record (tools stay separate, under {@code agentspaces.tools}). */
        public Builder settings(LangChain4jSettings settings) {
            this.discoveryTools = settings.discoveryTools();
            this.takeLease = settings.takeLease();
            this.memory = settings.memory();
            this.modelClient = settings.modelClient();
            this.modelServer = settings.modelServer();
            this.embedder = settings.embedder();
            this.usage = settings.usage();
            return this;
        }

        /** The fleet's cards as tools, with these settings. */
        public Builder tools(ToolSettings settings) {
            this.tools = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /** The fleet's cards as tools, with the default settings. */
        public Builder tools() {
            return tools(ToolSettings.defaults());
        }

        public Builder toolFilter(FleetToolFilter filter) {
            this.toolFilter = filter;
            return this;
        }

        public Builder toolNaming(ToolNamingStrategy naming) {
            this.toolNaming = naming;
            return this;
        }

        public Builder discoveryTools(LangChain4jSettings.DiscoveryTools settings) {
            this.discoveryTools = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /** The vote and push-sum tools a model may call; both off by default. */
        public Builder capabilityTools(FleetCapabilityTools.Settings settings) {
            this.capabilityTools = Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder takeLease(LangChain4jSettings.TakeLease settings) {
            this.takeLease = Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder memory(LangChain4jSettings.Memory settings) {
            this.memory = Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder conversationIdResolver(ConversationIdResolver resolver) {
            this.conversationIdResolver = resolver;
            return this;
        }

        public Builder modelClient(LangChain4jSettings.ModelClient settings) {
            this.modelClient = Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder restartPolicy(StreamRestartPolicy policy) {
            this.restartPolicy = policy;
            return this;
        }

        public Builder modelServer(LangChain4jSettings.ModelServer settings) {
            this.modelServer = Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder embedder(LangChain4jSettings.Embedder settings) {
            this.embedder = Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder usage(LangChain4jSettings.Usage settings) {
            this.usage = Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder usageSink(UsageSink sink) {
            this.usageSink = sink;
            return this;
        }

        public LangChain4jFleet build() {
            return new LangChain4jFleet(this);
        }
    }
}
