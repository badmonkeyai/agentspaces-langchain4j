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
package ai.badmonkey.agentspaces.tools;

import ai.badmonkey.agentspaces.agent.remote.RemoteAction;
import ai.badmonkey.agentspaces.agent.remote.RemoteActions;
import ai.badmonkey.agentspaces.runtime.FleetContext;
import ai.badmonkey.agentspaces.runtime.FleetParticipant;
import ai.badmonkey.agentspaces.runtime.FleetScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The live catalogue of fleet tools, shared by every tool bridge (LangChain4j,
 * MCP): the foreign cards' invocable actions ({@code RemoteActions}),
 * filtered, named, with their schemas, cached for the refresh interval and
 * diffed on that cadence, telling every {@link #onChange(Consumer) listener}
 * when the set changes. Framework-free: a host registers it as a
 * {@link FleetParticipant} and forwards the change events where it likes.
 */
public class FleetToolCatalog implements FleetParticipant {

    private static final Logger LOG = LoggerFactory.getLogger(FleetToolCatalog.class);

    private final Supplier<List<FleetAction>> actions;
    private final FleetToolFilter filter;
    private final ToolNamingStrategy naming;
    private final ToolInvocation invocation;
    private final Duration refreshInterval;
    private final int max;
    private final List<Consumer<FleetToolsChangedEvent>> listeners = new CopyOnWriteArrayList<>();
    private final FleetScheduler scheduler;
    private final Clock clock;
    private List<FleetTool> snapshot = List.of();
    private Set<String> names = Set.of();
    private Instant snapshotAt = Instant.MIN;
    private ScheduledFuture<?> refresher;

    /** Over a fleet, with the filter and naming the settings describe. */
    public static FleetToolCatalog of(FleetContext fleet, ToolSettings settings, FleetScheduler scheduler) {
        return new FleetToolCatalog(fleet, settings, settings.filter(), settings.naming(), scheduler);
    }

    public FleetToolCatalog(FleetContext fleet, ToolSettings settings, FleetToolFilter filter,
                            ToolNamingStrategy naming, FleetScheduler scheduler) {
        this(() -> {
            List<FleetAction> all = new ArrayList<>();
            for (RemoteAction action : RemoteActions.over(fleet.group(), fleet.peerId()).available()) {
                all.add(FleetAction.of(action));
            }
            return all;
        }, settings, filter, naming, scheduler, Clock.systemUTC());
    }

    public FleetToolCatalog(Supplier<List<FleetAction>> actions, ToolSettings settings, FleetToolFilter filter,
                            ToolNamingStrategy naming, FleetScheduler scheduler, Clock clock) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.naming = Objects.requireNonNull(naming, "naming");
        this.invocation = new ToolInvocation(settings.timeout(), settings.onTimeout());
        this.refreshInterval = settings.refreshInterval();
        this.max = settings.max();
        this.scheduler = scheduler;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The invocation the bridges run tools through. */
    public ToolInvocation invocation() {
        return invocation;
    }

    /** Removes a change listener; closing twice is harmless. */
    @FunctionalInterface
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    /** Hears every change; the returned handle removes the listener. */
    public Handle onChange(Consumer<FleetToolsChangedEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    /** The current tools, refreshed when the snapshot is older than the refresh interval. */
    public List<FleetTool> current() {
        boolean stale;
        synchronized (this) {
            stale = !clock.instant().isBefore(snapshotAt.plus(refreshInterval));
        }
        if (stale) {
            return rebuild();
        }
        synchronized (this) {
            return snapshot;
        }
    }

    /**
     * Rebuilds the set now and tells the listeners if it differs. The
     * discovery scan runs outside the monitor, so {@link #current()} on
     * another thread never waits on it, and listeners hear the change after
     * the snapshot has been swapped, so a listener may read {@link #current()}.
     */
    public List<FleetTool> rebuild() {
        Map<String, FleetTool> built = new LinkedHashMap<>();
        for (FleetAction action : actions.get()) {
            if (!filter.include(action)) {
                continue;
            }
            if (built.size() >= max) {
                break;
            }
            String base = naming.toolName(action);
            String name = base;
            for (int i = 2; built.containsKey(name); i++) {
                String suffix = "_" + i;
                name = (base.length() + suffix.length() > ToolNamingStrategy.MAX_LENGTH
                        ? base.substring(0, ToolNamingStrategy.MAX_LENGTH - suffix.length())
                        : base) + suffix;
            }
            built.put(name, new FleetTool(name, FleetTool.describe(action),
                    RecordSchemas.of(action.inputType()), action));
        }
        List<FleetTool> fresh = List.copyOf(built.values());
        Set<String> freshNames = new LinkedHashSet<>(built.keySet());
        Set<String> added;
        Set<String> removed;
        synchronized (this) {
            added = new LinkedHashSet<>(freshNames);
            added.removeAll(names);
            removed = new LinkedHashSet<>(names);
            removed.removeAll(freshNames);
            snapshot = fresh;
            names = freshNames;
            snapshotAt = clock.instant();
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            LOG.debug("fleet tools changed: +{} -{}", added, removed);
            FleetToolsChangedEvent event = new FleetToolsChangedEvent(added, removed, fresh);
            for (Consumer<FleetToolsChangedEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException e) {
                    // A listener's failure must not kill the scheduled refresh.
                    LOG.warn("a fleet tools listener failed", e);
                }
            }
        }
        return fresh;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null && refresher == null) {
            refresher = scheduler.scheduleAtFixedRate(refreshInterval, refreshInterval, this::rebuild);
        }
    }

    @Override
    public void refresh() {
        rebuild();
    }

    @Override
    public synchronized void stop() {
        if (refresher != null) {
            refresher.cancel(false);
            refresher = null;
        }
    }
}
