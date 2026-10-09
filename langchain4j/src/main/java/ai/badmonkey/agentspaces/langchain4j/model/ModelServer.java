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
package ai.badmonkey.agentspaces.langchain4j.model;

import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.SpaceEvent;
import ai.badmonkey.agentspaces.api.space.Subscription;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.runtime.FleetParticipant;
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace;
import ai.badmonkey.agentspaces.springai.model.wire.ModelAttempt;
import ai.badmonkey.agentspaces.springai.model.wire.ModelCancel;
import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.ModelRequest;
import ai.badmonkey.agentspaces.springai.model.wire.ModelResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolCall;
import ai.badmonkey.agentspaces.springai.model.wire.WireUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import ai.badmonkey.agentspaces.runtime.FleetScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * Serves fleet model requests with this peer's LangChain4j models: takes
 * {@code ModelRequest}s the router admits, renews its take while the provider
 * runs, answers with a {@code ModelResponse} (or chunks for a streamed
 * request, batched by the chunking policy, cancellable), and records each
 * attempt so a restarted request carries a higher attempt number. The same
 * protocol as the Spring AI module's server, so callers on either framework
 * are served alike.
 */
public class ModelServer implements FleetParticipant {

    private static final Logger LOG = LoggerFactory.getLogger(ModelServer.class);

    /** The durations and limits a server works with. */
    public record Settings(Duration lease, Duration chunkLease, Duration responseLease, int concurrency) {
        public Settings {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(chunkLease, "chunkLease");
            Objects.requireNonNull(responseLease, "responseLease");
            if (concurrency < 1) {
                throw new IllegalArgumentException("concurrency must be positive");
            }
        }
    }

    private final Space space;
    private final ModelCatalog catalog;
    private final ModelRequestRouter router;
    private final StreamChunkingPolicy chunking;
    private final FleetScheduler scheduler;
    private final String servedBy;
    private final Settings settings;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final AtomicLong served = new AtomicLong();
    private volatile boolean running;

    public ModelServer(Space space, ModelCatalog catalog, ModelRequestRouter router,
                       StreamChunkingPolicy chunking, FleetScheduler scheduler, String servedBy,
                       Settings settings) {
        this.space = Objects.requireNonNull(space, "space");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.router = Objects.requireNonNull(router, "router");
        this.chunking = Objects.requireNonNull(chunking, "chunking");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.servedBy = Objects.requireNonNull(servedBy, "servedBy");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** How many requests this server has answered. */
    public long served() {
        return served.get();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (router.bid(new ModelRequest("probe", "", List.of(), null, List.of(), false, ""), catalog).isPresent()) {
            if (!(space instanceof ReplicatedSpace replicated)
                    || replicated.strategy() != ConflictStrategyType.AUCTION) {
                throw new IllegalStateException("price routing needs the requests space '" + space.name()
                        + "' to run AUCTION: set strategy: AUCTION on it under agentspaces.groups[].spaces");
            }
            replicated.bidFunction(entry -> entry instanceof ModelRequest request
                    ? router.bid(request, catalog).orElse(Double.POSITIVE_INFINITY)
                    : Double.POSITIVE_INFINITY);
        }
        running = true;
        Template<ModelRequest> template = router.template(catalog);
        for (int i = 0; i < settings.concurrency(); i++) {
            workers.add(Thread.ofVirtual().name("model-server-" + i).start(() -> loop(template)));
        }
        LOG.info("model server {} serving {}", servedBy, catalog.models());
    }

    @Override
    public synchronized void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        workers.clear();
    }

    public boolean isRunning() {
        return running;
    }

    private void loop(Template<ModelRequest> template) {
        while (running) {
            Optional<TakenEntry<ModelRequest>> taken;
            try {
                taken = space.take(template, Lease.of(settings.lease()), Duration.ofSeconds(1));
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                LOG.warn("model-server: take failed", e);
                continue;
            }
            taken.ifPresent(this::serve);
        }
    }

    private void serve(TakenEntry<ModelRequest> taken) {
        ModelRequest request = taken.entry();
        int attempt = nextAttempt(request.requestId());
        space.write(new ModelAttempt(request.requestId(), attempt, servedBy), Lease.of(Duration.ofMinutes(10)));
        String model = request.model().isBlank() ? catalog.defaultModel() : request.model();
        Duration renewEvery = settings.lease().dividedBy(3);
        ScheduledFuture<?> renewer = scheduler.scheduleAtFixedRate(renewEvery, renewEvery, () -> {
            try {
                taken.renew(settings.lease());
            } catch (RuntimeException e) {
                LOG.debug("model-server: renewal failed", e);
            }
        });
        try {
            Optional<ChatModel> chat = catalog.model(model);
            if (chat.isEmpty()) {
                answer(taken, failure(request, model, attempt, "model '" + model + "' is not served by " + servedBy));
                return;
            }
            ChatRequest chatRequest = WireMapping.fromWire(request.messages(), request.options(), model, request.tools());
            Optional<StreamingChatModel> streaming = catalog.streamingModel(model);
            if (request.stream() && streaming.isPresent()) {
                stream(taken, request, model, attempt, streaming.get(), chatRequest);
            } else {
                ChatResponse response = chat.get().chat(chatRequest);
                ModelResponse full = new ModelResponse(request.requestId(), model, WireMapping.generations(response),
                        WireMapping.usage(response), null, servedBy, attempt);
                if (request.stream()) {
                    // No streaming model: one chunk carries the whole answer.
                    space.write(new ModelChunk(request.requestId(), attempt, 0, full.generations(), true,
                            full.usage(), null, servedBy), Lease.of(settings.chunkLease()));
                }
                answer(taken, full);
            }
        } catch (RuntimeException e) {
            if (!running) {
                return; // shutting down: leave the take to lapse for another server
            }
            LOG.warn("model-server: request {} failed", request.requestId(), e);
            answer(taken, failure(request, model, attempt, e.toString()));
        } finally {
            renewer.cancel(false);
        }
    }

    private void stream(TakenEntry<ModelRequest> taken, ModelRequest request, String model, int attempt,
                        StreamingChatModel chat, ChatRequest chatRequest) {
        StreamWriter writer = new StreamWriter(request.requestId(), model, attempt);
        Subscription cancels = space.notify(Template.of(ModelCancel.class)
                        .where("requestId", eq(request.requestId())),
                (SpaceEvent<ModelCancel> event) -> {
                    if (event.kind() == SpaceEvent.Kind.WRITTEN) {
                        writer.cancel();
                    }
                }, Lease.of(settings.lease().plusMinutes(1)));
        ScheduledFuture<?> flusher = scheduler.scheduleAtFixedRate(chunking.interval(), chunking.interval(),
                writer::flushIfDue);
        try {
            chat.chat(chatRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    writer.addText(partialResponse);
                }

                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    writer.addToolCall(completeToolCall);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    writer.complete(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    writer.fail(error);
                }
            });
            writer.done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // shutting down mid-stream: the take lapses, the caller sees the restart
        } finally {
            flusher.cancel(false);
            cancels.close();
        }
        if (running) {
            answer(taken, writer.response());
        }
    }

    private void answer(TakenEntry<ModelRequest> taken, ModelResponse response) {
        space.complete(taken, response, Lease.of(settings.responseLease()));
        served.incrementAndGet();
    }

    private ModelResponse failure(ModelRequest request, String model, int attempt, String error) {
        return new ModelResponse(request.requestId(), model, List.of(), null, error, servedBy, attempt);
    }

    private int nextAttempt(String requestId) {
        return space.readAll(Template.of(ModelAttempt.class).where("requestId", eq(requestId)), 100).stream()
                .mapToInt(ModelAttempt::attempt).max().orElse(0) + 1;
    }

    /** One stream's batching, aggregation, and ending, guarded by its own lock. */
    private final class StreamWriter {
        private final String requestId;
        private final String model;
        private final int attempt;
        private final List<WireGeneration> buffer = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final Map<String, WireToolCall> toolCalls = new LinkedHashMap<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private int seq;
        private Instant lastFlush = Instant.now();
        private WireUsage usage;
        private String finishReason;
        private String error;

        StreamWriter(String requestId, String model, int attempt) {
            this.requestId = requestId;
            this.model = model;
            this.attempt = attempt;
        }

        synchronized void addText(String delta) {
            if (cancelled.get() || finished.get()) {
                return;
            }
            buffer.add(new WireGeneration(new WireMessage("assistant", delta, List.of(), List.of(), List.of(), Map.of()),
                    null));
            text.append(delta);
            if (chunking.flush(buffer.size(), Duration.between(lastFlush, Instant.now()))) {
                write(false);
            }
        }

        synchronized void addToolCall(CompleteToolCall call) {
            if (cancelled.get() || finished.get()) {
                return;
            }
            var request = call.toolExecutionRequest();
            WireToolCall wire = new WireToolCall(request.id(), "function", request.name(), request.arguments());
            toolCalls.put(wire.id() == null ? String.valueOf(toolCalls.size()) : wire.id(), wire);
            buffer.add(new WireGeneration(new WireMessage("assistant", "", List.of(wire), List.of(), List.of(),
                    Map.of()), null));
        }

        synchronized void flushIfDue() {
            if (!finished.get() && chunking.flush(buffer.size(), Duration.between(lastFlush, Instant.now()))) {
                write(false);
            }
        }

        void complete(ChatResponse response) {
            synchronized (this) {
                usage = WireMapping.usage(response);
                finishReason = WireMapping.finishReason(response.finishReason());
                if (finishReason == null) {
                    finishReason = toolCalls.isEmpty() ? "STOP" : "TOOL_CALLS";
                }
                buffer.add(new WireGeneration(null, finishReason));
            }
            finish();
        }

        void fail(Throwable failure) {
            synchronized (this) {
                error = failure.toString();
            }
            finish();
        }

        void cancel() {
            cancelled.set(true);
            finish();
        }

        void finish() {
            if (finished.compareAndSet(false, true)) {
                synchronized (this) {
                    write(true);
                }
                done.countDown();
            }
        }

        private void write(boolean last) {
            space.write(new ModelChunk(requestId, attempt, seq++, List.copyOf(buffer), last,
                    last ? usage : null, last ? error : null, servedBy), Lease.of(settings.chunkLease()));
            buffer.clear();
            lastFlush = Instant.now();
        }

        synchronized ModelResponse response() {
            WireMessage message = new WireMessage("assistant", text.toString(),
                    new ArrayList<>(toolCalls.values()), List.of(), List.of(), Map.of());
            return new ModelResponse(requestId, model, List.of(new WireGeneration(message, finishReason)),
                    usage, error, servedBy, attempt);
        }
    }
}
