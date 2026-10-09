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

import ai.badmonkey.agentspaces.runtime.Props;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The settings of every feature, as records with defaults, built in code or
 * bound from the {@code agentspaces.langchain4j} subtree with the Micronaut
 * library's key names, so one YAML fragment configures a peer under any
 * host. Each feature is off until its settings enable it.
 */
public record LangChain4jSettings(DiscoveryTools discoveryTools, TakeLease takeLease, Memory memory,
                                  ModelClient modelClient, ModelServer modelServer, Embedder embedder, Usage usage) {

    public LangChain4jSettings {
        Objects.requireNonNull(discoveryTools, "discoveryTools");
        Objects.requireNonNull(takeLease, "takeLease");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(modelClient, "modelClient");
        Objects.requireNonNull(modelServer, "modelServer");
        Objects.requireNonNull(embedder, "embedder");
        Objects.requireNonNull(usage, "usage");
    }

    public static LangChain4jSettings defaults() {
        return new LangChain4jSettings(DiscoveryTools.defaults(), TakeLease.defaults(), Memory.defaults(),
                ModelClient.defaults(), ModelServer.defaults(), Embedder.defaults(), Usage.defaults());
    }

    /** From the {@code agentspaces.langchain4j} subtree; unknown keys fail. */
    public static LangChain4jSettings from(Map<String, ?> tree) {
        Props p = Props.of(tree, "agentspaces.langchain4j");
        LangChain4jSettings settings = new LangChain4jSettings(
                DiscoveryTools.from(p.sub("discovery-tools")), TakeLease.from(p.sub("take-lease")),
                Memory.from(p.sub("memory")), ModelClient.from(p.sub("model-client")),
                ModelServer.from(p.sub("model-server")), Embedder.from(p.sub("embedder")), Usage.from(p.sub("usage")));
        p.sub("tools"); // the Micronaut library's enabled and auto-apply switches; accepted, ignored here
        p.rejectUnknown();
        return settings;
    }

    /** How a conversation is named for the chat memory. */
    public enum ConversationIdMode {
        /** The in-flight take's entry id. */
        TAKE,
        /** The in-flight take's agent name. */
        AGENT,
        /** The application passes the id itself. */
        EXPLICIT
    }

    /** What a stream does when its server dies and another attempt begins. */
    public enum RestartMode {
        FAIL,
        RESTART
    }

    /** How a server chooses the requests it takes. */
    public enum Routing {
        MODEL,
        PRICE
    }

    /** The three discovery tools, each off by default, and their limits. */
    public record DiscoveryTools(boolean agents, boolean assets, boolean data, String dataSpace, Duration timeout,
                                 int maxResultChars) {
        public DiscoveryTools {
            Objects.requireNonNull(dataSpace, "dataSpace");
            Objects.requireNonNull(timeout, "timeout");
        }

        public static DiscoveryTools defaults() {
            return new DiscoveryTools(false, false, false, "data", Duration.ofSeconds(10), 8000);
        }

        /** All three on. */
        public static DiscoveryTools all() {
            return new DiscoveryTools(true, true, true, "data", Duration.ofSeconds(10), 8000);
        }

        public boolean any() {
            return agents || assets || data;
        }

        public DiscoveryTools withAgents(boolean on) {
            return new DiscoveryTools(on, assets, data, dataSpace, timeout, maxResultChars);
        }

        public DiscoveryTools withAssets(boolean on) {
            return new DiscoveryTools(agents, on, data, dataSpace, timeout, maxResultChars);
        }

        public DiscoveryTools withData(boolean on) {
            return new DiscoveryTools(agents, assets, on, dataSpace, timeout, maxResultChars);
        }

        public DiscoveryTools withDataSpace(String space) {
            return new DiscoveryTools(agents, assets, data, space, timeout, maxResultChars);
        }

        public DiscoveryTools withTimeout(Duration value) {
            return new DiscoveryTools(agents, assets, data, dataSpace, value, maxResultChars);
        }

        public DiscoveryTools withMaxResultChars(int value) {
            return new DiscoveryTools(agents, assets, data, dataSpace, timeout, value);
        }

        static DiscoveryTools from(Props p) {
            DiscoveryTools d = defaults();
            DiscoveryTools out = new DiscoveryTools(p.bool("agents", d.agents), p.bool("assets", d.assets),
                    p.bool("data", d.data), p.string("data-space", d.dataSpace), p.duration("timeout", d.timeout),
                    p.integer("max-result-chars", d.maxResultChars));
            p.rejectUnknown();
            return out;
        }
    }

    /** The take-lease listener: whether the facade includes it, and the streaming renew interval. */
    public record TakeLease(boolean enabled, Duration streamRenewInterval) {
        public TakeLease {
            Objects.requireNonNull(streamRenewInterval, "streamRenewInterval");
        }

        public static TakeLease defaults() {
            return new TakeLease(true, Duration.ofSeconds(10));
        }

        static TakeLease from(Props p) {
            TakeLease d = defaults();
            TakeLease out = new TakeLease(p.bool("enabled", d.enabled),
                    p.duration("stream-renew-interval", d.streamRenewInterval));
            p.rejectUnknown();
            return out;
        }
    }

    /** The chat memory store and provider in a replicated space. */
    public record Memory(boolean enabled, String space, Duration ttl, ConversationIdMode conversationId,
                         int maxMessages) {
        public Memory {
            Objects.requireNonNull(space, "space");
            Objects.requireNonNull(ttl, "ttl");
            Objects.requireNonNull(conversationId, "conversationId");
        }

        public static Memory defaults() {
            return new Memory(false, "conversations", Duration.ofHours(24), ConversationIdMode.TAKE, 20);
        }

        /** On, in the default space. */
        public static Memory on() {
            return defaults().withEnabled(true);
        }

        public Memory withEnabled(boolean on) {
            return new Memory(on, space, ttl, conversationId, maxMessages);
        }

        public Memory withSpace(String value) {
            return new Memory(enabled, value, ttl, conversationId, maxMessages);
        }

        public Memory withTtl(Duration value) {
            return new Memory(enabled, space, value, conversationId, maxMessages);
        }

        public Memory withConversationId(ConversationIdMode mode) {
            return new Memory(enabled, space, ttl, mode, maxMessages);
        }

        public Memory withMaxMessages(int value) {
            return new Memory(enabled, space, ttl, conversationId, value);
        }

        static Memory from(Props p) {
            Memory d = defaults();
            Memory out = new Memory(p.bool("enabled", d.enabled), p.string("space", d.space),
                    p.duration("ttl", d.ttl), p.enumeration("conversation-id", ConversationIdMode.class, d.conversationId),
                    p.integer("max-messages", d.maxMessages));
            p.bool("auto-apply", false);
            p.rejectUnknown();
            return out;
        }
    }

    /** The fleet model client: the model space, the default model, the timeout, and the restart policy. */
    public record ModelClient(boolean enabled, String space, String model, Duration timeout, RestartMode onRestart) {
        public ModelClient {
            Objects.requireNonNull(space, "space");
            model = model == null ? "" : model;
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(onRestart, "onRestart");
        }

        public static ModelClient defaults() {
            return new ModelClient(false, "model-requests", "", Duration.ofSeconds(120), RestartMode.FAIL);
        }

        /** On, for one default model. */
        public static ModelClient of(String model) {
            return defaults().withEnabled(true).withModel(model);
        }

        public ModelClient withEnabled(boolean on) {
            return new ModelClient(on, space, model, timeout, onRestart);
        }

        public ModelClient withSpace(String value) {
            return new ModelClient(enabled, value, model, timeout, onRestart);
        }

        public ModelClient withModel(String value) {
            return new ModelClient(enabled, space, value, timeout, onRestart);
        }

        public ModelClient withTimeout(Duration value) {
            return new ModelClient(enabled, space, model, value, onRestart);
        }

        public ModelClient withOnRestart(RestartMode mode) {
            return new ModelClient(enabled, space, model, timeout, mode);
        }

        static ModelClient from(Props p) {
            ModelClient d = defaults();
            Props stream = p.sub("stream");
            ModelClient out = new ModelClient(p.bool("enabled", d.enabled), p.string("space", d.space),
                    p.string("model", d.model), p.duration("timeout", d.timeout),
                    stream.enumeration("on-restart", RestartMode.class, d.onRestart));
            stream.rejectUnknown();
            p.rejectUnknown();
            return out;
        }
    }

    /** The fleet model server: the model space, routing, and the leases and chunking it works with. */
    public record ModelServer(boolean enabled, String space, List<String> models, Routing routing,
                              Map<String, Double> prices, Duration lease, Duration chunkInterval, int chunkMaxDeltas,
                              Duration chunkLease, Duration responseLease, int concurrency) {
        public ModelServer {
            Objects.requireNonNull(space, "space");
            models = List.copyOf(models);
            Objects.requireNonNull(routing, "routing");
            prices = Map.copyOf(prices);
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(chunkInterval, "chunkInterval");
            Objects.requireNonNull(chunkLease, "chunkLease");
            Objects.requireNonNull(responseLease, "responseLease");
        }

        public static ModelServer defaults() {
            return new ModelServer(false, "model-requests", List.of(), Routing.MODEL, Map.of(), Duration.ofMinutes(2),
                    Duration.ofMillis(100), 32, Duration.ofMinutes(2), Duration.ofMinutes(10), 4);
        }

        /** On, in the default space, routing by model. */
        public static ModelServer on() {
            return defaults().withEnabled(true);
        }

        public ModelServer withEnabled(boolean on) {
            return new ModelServer(on, space, models, routing, prices, lease, chunkInterval, chunkMaxDeltas, chunkLease,
                    responseLease, concurrency);
        }

        public ModelServer withSpace(String value) {
            return new ModelServer(enabled, value, models, routing, prices, lease, chunkInterval, chunkMaxDeltas,
                    chunkLease, responseLease, concurrency);
        }

        public ModelServer withModels(List<String> value) {
            return new ModelServer(enabled, space, value, routing, prices, lease, chunkInterval, chunkMaxDeltas,
                    chunkLease, responseLease, concurrency);
        }

        public ModelServer withPriceRouting(Map<String, Double> pricePerThousandTokens) {
            return new ModelServer(enabled, space, models, Routing.PRICE, pricePerThousandTokens, lease, chunkInterval,
                    chunkMaxDeltas, chunkLease, responseLease, concurrency);
        }

        public ModelServer withLease(Duration value) {
            return new ModelServer(enabled, space, models, routing, prices, value, chunkInterval, chunkMaxDeltas,
                    chunkLease, responseLease, concurrency);
        }

        public ModelServer withChunking(Duration interval, int maxDeltas) {
            return new ModelServer(enabled, space, models, routing, prices, lease, interval, maxDeltas, chunkLease,
                    responseLease, concurrency);
        }

        public ModelServer withChunkLease(Duration value) {
            return new ModelServer(enabled, space, models, routing, prices, lease, chunkInterval, chunkMaxDeltas, value,
                    responseLease, concurrency);
        }

        public ModelServer withResponseLease(Duration value) {
            return new ModelServer(enabled, space, models, routing, prices, lease, chunkInterval, chunkMaxDeltas,
                    chunkLease, value, concurrency);
        }

        public ModelServer withConcurrency(int value) {
            return new ModelServer(enabled, space, models, routing, prices, lease, chunkInterval, chunkMaxDeltas,
                    chunkLease, responseLease, value);
        }

        static ModelServer from(Props p) {
            ModelServer d = defaults();
            ModelServer out = new ModelServer(p.bool("enabled", d.enabled), p.string("space", d.space),
                    p.strings("models"), p.enumeration("routing", Routing.class, d.routing), p.doubles("prices"),
                    p.duration("lease", d.lease), p.duration("chunk-interval", d.chunkInterval),
                    p.integer("chunk-max-deltas", d.chunkMaxDeltas), p.duration("chunk-lease", d.chunkLease),
                    p.duration("response-lease", d.responseLease), p.integer("concurrency", d.concurrency));
            p.map("model-beans"); // a host with named beans reads this; accepted here
            p.rejectUnknown();
            return out;
        }
    }

    /** The embedder identity and cache for a LangChain4j embedding model behind semantic discovery. */
    public record Embedder(String model, int cacheSize) {
        public Embedder {
            model = model == null ? "" : model;
        }

        public static Embedder defaults() {
            return new Embedder("", 4096);
        }

        static Embedder from(Props p) {
            Embedder d = defaults();
            Embedder out = new Embedder(p.string("model", d.model), p.integer("cache-size", d.cacheSize));
            p.string("type", "hashing"); // a host's switch between hashing and langchain4j; accepted here
            p.rejectUnknown();
            return out;
        }
    }

    /** Fleet-wide token usage: the aggregate, an optional metrics space, and the publish cadence. */
    public record Usage(boolean enabled, String metricsSpace, Duration entryLease, Duration publishInterval) {
        public Usage {
            metricsSpace = metricsSpace == null ? "" : metricsSpace;
            Objects.requireNonNull(entryLease, "entryLease");
            Objects.requireNonNull(publishInterval, "publishInterval");
        }

        public static Usage defaults() {
            return new Usage(false, "", Duration.ofHours(24), Duration.ofSeconds(30));
        }

        /** On, with no metrics space. */
        public static Usage on() {
            return defaults().withEnabled(true);
        }

        public Usage withEnabled(boolean on) {
            return new Usage(on, metricsSpace, entryLease, publishInterval);
        }

        public Usage withMetricsSpace(String value) {
            return new Usage(enabled, value, entryLease, publishInterval);
        }

        public Usage withEntryLease(Duration value) {
            return new Usage(enabled, metricsSpace, value, publishInterval);
        }

        public Usage withPublishInterval(Duration value) {
            return new Usage(enabled, metricsSpace, entryLease, value);
        }

        static Usage from(Props p) {
            Usage d = defaults();
            Usage out = new Usage(p.bool("enabled", d.enabled), p.string("metrics-space", d.metricsSpace),
                    p.duration("entry-lease", d.entryLease), p.duration("publish-interval", d.publishInterval));
            p.rejectUnknown();
            return out;
        }
    }
}
