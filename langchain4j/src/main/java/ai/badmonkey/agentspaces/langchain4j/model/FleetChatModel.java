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

import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.SpaceEvent;
import ai.badmonkey.agentspaces.api.space.Subscription;
import ai.badmonkey.agentspaces.api.space.TakenEntry;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.common.id.AgentId;
import ai.badmonkey.agentspaces.springai.model.wire.ModelCancel;
import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.ModelRequest;
import ai.badmonkey.agentspaces.springai.model.wire.ModelResponse;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import ai.badmonkey.agentspaces.springai.model.wire.WireToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import ai.badmonkey.agentspaces.runtime.FleetScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.badmonkey.agentspaces.api.space.Matchers.eq;

/**
 * Model access as a fleet service: a LangChain4j {@code ChatModel} (and,
 * through {@link FleetStreamingChatModel}, a {@code StreamingChatModel})
 * whose provider is the fleet. Each round-trip is
 * a {@code ModelRequest} written into the model space and answered by a
 * server peer that holds the credentials (the Spring AI module's
 * {@code ModelServer} or this module's), with the subscribe-then-write
 * correlation, trust through {@code MODEL_SERVE}, crash tolerance by lease,
 * chunked streaming with reorder and restart policy, and cancellation.
 * The caller executes tool calls with its own tools; the server never does.
 */
public class FleetChatModel implements ChatModel {

    private static final Duration POLL = Duration.ofMillis(200);

    private final Space space;
    private final Authorizer authorizer;
    private final String requester;
    private final String defaultModel;
    private final Duration timeout;
    private final StreamRestartPolicy restartPolicy;
    private final FleetScheduler scheduler;
    private final List<ChatModelListener> listeners;

    public FleetChatModel(Space space, Authorizer authorizer, String requester, String defaultModel,
                          Duration timeout, StreamRestartPolicy restartPolicy, FleetScheduler scheduler,
                          List<ChatModelListener> listeners) {
        this.space = Objects.requireNonNull(space, "space");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.defaultModel = defaultModel == null ? "" : defaultModel;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.restartPolicy = Objects.requireNonNull(restartPolicy, "restartPolicy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().modelName(defaultModel.isBlank() ? null : defaultModel).build();
    }

    // ---------------------------------------------------------------- one round-trip

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        ModelRequest request = request(chatRequest, false);
        Template<ModelResponse> template = responses(request.requestId());
        CompletableFuture<ModelResponse> answer = new CompletableFuture<>();
        try (Subscription subscription = space.notify(template, (SpaceEvent<ModelResponse> event) -> {
            if (event.kind() == SpaceEvent.Kind.WRITTEN && trusted(event.issuer(), event.entry().model())) {
                answer.complete(event.entry());
            }
        }, Lease.of(timeout.plusMinutes(1)))) {
            space.write(request, Lease.of(timeout.plusSeconds(30)));
            ModelResponse response = await(answer, template, Instant.now().plus(timeout))
                    .orElseThrow(() -> new FleetModelException("no trusted model server answered request "
                            + request.requestId() + " (model '" + request.model() + "') within " + timeout));
            retire(template);
            if (response.error() != null) {
                throw new FleetModelException("model server " + response.servedBy() + " failed: " + response.error());
            }
            return WireMapping.toResponse(response.generations(), response.usage(), response.model(),
                    response.requestId());
        }
    }

    // ---------------------------------------------------------------- streaming

    /** A streamed round-trip; {@link FleetStreamingChatModel} is the LangChain4j face of it. */
    public void stream(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        ModelRequest request = request(chatRequest, true);
        String id = request.requestId();
        AtomicBoolean closed = new AtomicBoolean();
        Template<ModelChunk> chunks = Template.of(ModelChunk.class).where("requestId", eq(id));
        StreamState state = new StreamState();
        ModelStreamAssembler assembler = new ModelStreamAssembler(id, restartPolicy, new ModelStreamAssembler.Listener() {
            @Override
            public void onChunk(ModelChunk chunk) {
                for (WireGeneration delta : chunk.deltas()) {
                    if (delta.output() == null) {
                        continue;
                    }
                    if (delta.output().text() != null && !delta.output().text().isEmpty()) {
                        state.text.append(delta.output().text());
                        handler.onPartialResponse(delta.output().text());
                    }
                    for (WireToolCall call : delta.output().toolCalls()) {
                        state.calls.add(call);
                    }
                }
            }

            @Override
            public void onRestart(int fromAttempt, int toAttempt) {
                state.text.setLength(0);
                state.calls.clear();
            }

            @Override
            public void onComplete(ModelChunk last) {
                int index = 0;
                for (WireToolCall call : state.calls) {
                    handler.onCompleteToolCall(new CompleteToolCall(index++, ToolExecutionRequest.builder()
                            .id(call.id()).name(call.name()).arguments(call.arguments()).build()));
                }
                handler.onCompleteResponse(WireMapping.toResponse(List.of(new WireGeneration(
                        new ai.badmonkey.agentspaces.springai.model.wire.WireMessage("assistant",
                                state.text.toString(), state.calls, List.of(), List.of(), java.util.Map.of()),
                        last.deltas().isEmpty() ? "STOP" : lastFinish(last))), last.usage(), null, id));
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        });
        Subscription subscription = space.notify(chunks, (SpaceEvent<ModelChunk> event) -> {
            if (event.kind() == SpaceEvent.Kind.WRITTEN && trusted(event.issuer(), request.model())) {
                assembler.accept(event.entry());
            }
        }, Lease.of(timeout.plusMinutes(1)));
        java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> readThroughRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> deadlineRef = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable finish = () -> {
            if (closed.compareAndSet(false, true)) {
                subscription.close();
                ScheduledFuture<?> poller = readThroughRef.get();
                if (poller != null) {
                    poller.cancel(false);
                }
                ScheduledFuture<?> limit = deadlineRef.get();
                if (limit != null) {
                    limit.cancel(false);
                }
            }
        };
        readThroughRef.set(scheduler.scheduleWithFixedDelay(Duration.ofMillis(250), Duration.ofMillis(250), () -> {
            for (var issued : space.readAllIssued(chunks, 1000)) {
                if (trusted(issued.issuer(), request.model())) {
                    assembler.accept(issued.entry());
                }
            }
            if (assembler.isDone()) {
                // The stream ended (complete, failed, or restarted and refused): stop polling now.
                finish.run();
                scheduler.schedule(Duration.ofSeconds(1), () -> retire(responses(id)));
            }
        }));
        deadlineRef.set(scheduler.schedule(timeout, () -> {
            if (!assembler.isDone()) {
                space.write(new ModelCancel(id, requester), Lease.of(Duration.ofMinutes(1)));
                assembler.fail(new FleetModelException("model stream " + id + " did not finish within " + timeout));
            }
            finish.run();
        }));
        space.write(request, Lease.of(timeout.plusSeconds(30)));
    }

    private static String lastFinish(ModelChunk last) {
        for (int i = last.deltas().size() - 1; i >= 0; i--) {
            if (last.deltas().get(i).finishReason() != null) {
                return last.deltas().get(i).finishReason();
            }
        }
        return "STOP";
    }

    private static final class StreamState {
        final StringBuilder text = new StringBuilder();
        final List<WireToolCall> calls = new java.util.ArrayList<>();
    }

    // ---------------------------------------------------------------- internals

    private ModelRequest request(ChatRequest chatRequest, boolean stream) {
        ChatRequestParameters parameters = chatRequest.parameters();
        String model = parameters != null && parameters.modelName() != null && !parameters.modelName().isBlank()
                ? parameters.modelName() : defaultModel;
        return new ModelRequest(UUID.randomUUID().toString(), model, WireMapping.toWire(chatRequest.messages()),
                WireMapping.toWire(parameters), WireMapping.toolDefinitions(chatRequest), stream, requester);
    }

    private Optional<ModelResponse> await(CompletableFuture<ModelResponse> answer,
                                          Template<ModelResponse> template, Instant deadline) {
        while (Instant.now().isBefore(deadline)) {
            try {
                return Optional.of(answer.get(POLL.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                for (var issued : space.readAllIssued(template, 16)) {
                    if (trusted(issued.issuer(), issued.entry().model())) {
                        return Optional.of(issued.entry());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FleetModelException("interrupted waiting for a model server");
            } catch (ExecutionException e) {
                throw new FleetModelException("model request failed: " + e.getCause());
            }
        }
        return Optional.empty();
    }

    private boolean trusted(AgentId issuer, String model) {
        return issuer != null && authorizer.permits(issuer, Authorizer.Operation.MODEL_SERVE, model == null ? "" : model);
    }

    private void retire(Template<ModelResponse> template) {
        try {
            Optional<? extends TakenEntry<ModelResponse>> taken =
                    space.take(template, Lease.of(Duration.ofSeconds(30)), Duration.ZERO);
            taken.ifPresent(space::complete);
        } catch (RuntimeException e) {
            // Best effort: the response's own lease collects it otherwise.
        }
    }

    private static Template<ModelResponse> responses(String requestId) {
        return Template.of(ModelResponse.class).where("requestId", eq(requestId));
    }
}
