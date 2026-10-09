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

import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;

import java.util.Objects;
import java.util.TreeMap;

/**
 * Reorders a stream's chunks by sequence, follows a server restart by the
 * policy, and ends on the last chunk; the same automaton as the Spring AI
 * module's, since both read the same chunks.
 */
public final class ModelStreamAssembler {

    public interface Listener {
        void onChunk(ModelChunk chunk);

        void onRestart(int fromAttempt, int toAttempt);

        void onComplete(ModelChunk last);

        void onError(Throwable error);
    }

    private final String requestId;
    private final StreamRestartPolicy policy;
    private final Listener listener;
    private final TreeMap<Integer, ModelChunk> pending = new TreeMap<>();
    private final StringBuilder emitted = new StringBuilder();
    private int attempt;
    private int nextSeq;
    private boolean done;

    public ModelStreamAssembler(String requestId, StreamRestartPolicy policy, Listener listener) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized void accept(ModelChunk chunk) {
        if (done || !requestId.equals(chunk.requestId()) || chunk.attempt() < attempt) {
            return;
        }
        if (chunk.attempt() > attempt) {
            if (attempt != 0 && nextSeq > 0) {
                StreamRestartPolicy.Action action = policy.decide(requestId, attempt, chunk.attempt(),
                        emitted.toString());
                if (action == StreamRestartPolicy.Action.FAIL) {
                    done = true;
                    listener.onError(new FleetStreamRestartedException(requestId, attempt, chunk.attempt(),
                            emitted.toString()));
                    return;
                }
                listener.onRestart(attempt, chunk.attempt());
            }
            attempt = chunk.attempt();
            nextSeq = 0;
            pending.clear();
            emitted.setLength(0);
        }
        if (chunk.seq() < nextSeq) {
            return;
        }
        pending.putIfAbsent(chunk.seq(), chunk);
        while (!done && pending.containsKey(nextSeq)) {
            ModelChunk next = pending.remove(nextSeq++);
            for (WireGeneration delta : next.deltas()) {
                if (delta.output() != null && delta.output().text() != null) {
                    emitted.append(delta.output().text());
                }
            }
            listener.onChunk(next);
            if (next.last()) {
                done = true;
                if (next.error() != null) {
                    listener.onError(new FleetModelException("model stream " + requestId + " failed: " + next.error()));
                } else {
                    listener.onComplete(next);
                }
            }
        }
    }

    public synchronized void fail(Throwable error) {
        if (!done) {
            done = true;
            listener.onError(error);
        }
    }

    public synchronized boolean isDone() {
        return done;
    }

    public synchronized int attempt() {
        return attempt;
    }

    public synchronized String emittedText() {
        return emitted.toString();
    }
}
