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
package ai.badmonkey.agentspaces.runtime;

import ai.badmonkey.agentspaces.agent.AgentBinder;
import ai.badmonkey.agentspaces.agent.AgentIdentityFactory;
import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.api.spi.Embedder;
import ai.badmonkey.agentspaces.api.spi.SchemaRegistry;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration;
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import ai.badmonkey.agentspaces.spring.Authorizers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * A peer in plain Java from the same {@code agentspaces.*} configuration the
 * Spring Boot starter and the Micronaut library read. Every piece is built
 * by the starter's own build methods on {@link AgentSpacesAutoConfiguration},
 * which carry no Spring type in their signatures and run with no Spring on
 * the classpath (the Spring annotations on that class are unresolvable here,
 * and the JVM ignores them), so the transport, TLS, profile, group, space,
 * key-ring, and capability wiring stays in one place for every host. The
 * runtime then does what the starter's lifecycle does: ticks the node,
 * starts each {@link FleetParticipant}, and refreshes cards, participants,
 * and the OIDC token on the card cadence; {@link #close()} stops it all in
 * reverse.
 *
 * <pre>{@code
 * FleetRuntime runtime = FleetRuntime.builder(PropertiesLoader.yaml(Path.of("agentspaces.yaml")))
 *         .participant(modelServer)
 *         .start();
 * FleetContext fleet = runtime.context("research-fleet");
 * runtime.bind(new Summarizer());
 * }</pre>
 */
public final class FleetRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FleetRuntime.class);

    private final AgentSpacesProperties properties;
    private final InstantSource clock;
    private final PeerIdentity identity;
    private final PeerNode node;
    private final Authorizers authorizers;
    private final Embedder embedder;
    private final AgentIdentityFactory agentIdentities;
    private final AgentSpaces spaces;
    private final Authorizer authorizer;
    private final FleetScheduler scheduler;
    private final List<FleetParticipant> participants = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> refresher;
    private volatile boolean running;
    private boolean closed;

    private FleetRuntime(Builder builder) {
        this.properties = builder.properties;
        AgentSpacesAutoConfiguration wiring = new AgentSpacesAutoConfiguration();
        this.clock = builder.clock != null ? builder.clock : wiring.agentSpacesClock();
        this.identity = builder.identity != null ? builder.identity : wiring.agentSpacesIdentity(properties);
        this.node = wiring.agentSpacesNode(properties, identity);
        this.authorizers = wiring.agentSpacesAuthorizers(properties, identity, clock);
        this.embedder = builder.embedder != null ? builder.embedder : wiring.agentSpacesEmbedder();
        this.agentIdentities = wiring.agentSpacesAgentIdentities(properties, identity, clock);
        this.spaces = wiring.agentSpacesWithRegistry(properties, node, identity, authorizers, embedder, clock,
                agentIdentities, builder.schemaRegistry);
        this.authorizer = wiring.agentSpacesAuthorizer(authorizers, spaces);
        this.scheduler = builder.scheduler != null ? FleetScheduler.over(builder.scheduler)
                : FleetScheduler.virtual("agentspaces-runtime-");
        this.participants.addAll(builder.participants);
    }

    /** A runtime from the bound properties; {@link Builder#start()} builds and starts it. */
    public static Builder builder(AgentSpacesProperties properties) {
        return new Builder(properties);
    }

    /** Builds and starts a runtime with no options, for the simplest case. */
    public static FleetRuntime start(AgentSpacesProperties properties) {
        return builder(properties).start();
    }

    private synchronized void start() {
        if (running) {
            return;
        }
        List<FleetParticipant> started = new ArrayList<>();
        try {
            node.startTicking(Duration.ofMillis(properties.getTickMillis()));
            for (FleetParticipant participant : participants) {
                participant.start();
                started.add(participant);
            }
            Duration cardRefresh = Duration.ofMillis(properties.getCardRefreshMillis());
            refresher = scheduler.scheduleAtFixedRate(cardRefresh, cardRefresh, this::refresh);
            running = true;
        } catch (RuntimeException e) {
            // Nothing holds a reference to a runtime that failed to start: release it all here.
            Collections.reverse(started);
            for (FleetParticipant participant : started) {
                try {
                    participant.stop();
                } catch (RuntimeException stop) {
                    LOG.warn("stop of {} failed", participant, stop);
                }
            }
            release();
            throw e;
        }
        LOG.info("AgentSpaces peer {} running; groups {}", identity.peerId(), spaces.groupNames());
    }

    private void release() {
        try {
            spaces.close();
        } finally {
            try {
                node.close();
            } finally {
                scheduler.close();
            }
        }
    }

    /** One refresh tick: cards, participants, then the token hook; none may kill the schedule. */
    void refresh() {
        try {
            spaces.refreshCards();
        } catch (RuntimeException e) {
            LOG.warn("card refresh failed", e);
        }
        for (FleetParticipant participant : participants) {
            try {
                participant.refresh();
            } catch (RuntimeException e) {
                LOG.warn("refresh of {} failed", participant, e);
            }
        }
        try {
            authorizers.refreshToken();
        } catch (RuntimeException e) {
            LOG.warn("token refresh failed", e);
        }
    }

    /** The context of one joined group, the way this project's features work in it. */
    public FleetContext context(String groupName) {
        return new FleetContext(spaces, groupName, identity, authorizers.forGroup(groupName));
    }

    /** The context of the first configured group. */
    public FleetContext context() {
        if (properties.getGroups().isEmpty()) {
            throw new IllegalStateException("agentspaces.groups is empty: configure at least one group"
                    + " (name, founding or join, spaces)");
        }
        return context(properties.getGroups().get(0).getName());
    }

    /**
     * Binds an annotated object ({@code @AgentSpec}, {@code @SpaceTake}, and
     * the rest) into every group its spaces satisfy, through the facade.
     */
    public List<AgentBinder.Bound> bind(Object agent) {
        return spaces.bind(agent);
    }

    /**
     * Adds a participant; started now when the runtime runs, otherwise with
     * it. Participants stop in reverse order on close.
     */
    public synchronized FleetRuntime participant(FleetParticipant participant) {
        participants.add(Objects.requireNonNull(participant, "participant"));
        if (running) {
            participant.start();
        }
        return this;
    }

    public AgentSpaces spaces() {
        return spaces;
    }

    public PeerNode node() {
        return node;
    }

    public PeerIdentity identity() {
        return identity;
    }

    public Authorizers authorizers() {
        return authorizers;
    }

    /** The primary authorizer the profile selected. */
    public Authorizer authorizer() {
        return authorizer;
    }

    public Embedder embedder() {
        return embedder;
    }

    public InstantSource clock() {
        return clock;
    }

    public AgentSpacesProperties properties() {
        return properties;
    }

    /** The scheduler the runtime's periodic work runs on; participants share it. */
    public FleetScheduler scheduler() {
        return scheduler;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        boolean wasRunning = running;
        running = false;
        if (refresher != null) {
            refresher.cancel(false);
            refresher = null;
        }
        if (wasRunning) {
            List<FleetParticipant> reversed = new ArrayList<>(participants);
            Collections.reverse(reversed);
            for (FleetParticipant participant : reversed) {
                try {
                    participant.stop();
                } catch (RuntimeException e) {
                    LOG.warn("stop of {} failed", participant, e);
                }
            }
        }
        release();
    }

    /** The options a runtime starts with. */
    public static final class Builder {
        private final AgentSpacesProperties properties;
        private final List<FleetParticipant> participants = new ArrayList<>();
        private Embedder embedder;
        private SchemaRegistry schemaRegistry;
        private ScheduledExecutorService scheduler;
        private InstantSource clock;
        private PeerIdentity identity;

        private Builder(AgentSpacesProperties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
        }

        /** The embedder semantic discovery ranks with; the hashing default otherwise. */
        public Builder embedder(Embedder embedder) {
            this.embedder = embedder;
            return this;
        }

        /** A registry naming entry types by namespace (issue #16); the class-name default otherwise. */
        public Builder schemaRegistry(SchemaRegistry schemaRegistry) {
            this.schemaRegistry = schemaRegistry;
            return this;
        }

        /** The host's scheduler; the runtime makes and closes a virtual-thread one otherwise. */
        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /** The clock the fabric runs on; the system clock otherwise. */
        public Builder clock(InstantSource clock) {
            this.clock = clock;
            return this;
        }

        /** A peer identity built elsewhere; the keystore the properties name otherwise. */
        public Builder identity(PeerIdentity identity) {
            this.identity = identity;
            return this;
        }

        /** A participant to start after the node ticks, in the order added. */
        public Builder participant(FleetParticipant participant) {
            participants.add(Objects.requireNonNull(participant, "participant"));
            return this;
        }

        /** Builds the peer, joins its groups, and starts ticking. */
        public FleetRuntime start() {
            FleetRuntime runtime = new FleetRuntime(this);
            runtime.start();
            return runtime;
        }
    }
}
