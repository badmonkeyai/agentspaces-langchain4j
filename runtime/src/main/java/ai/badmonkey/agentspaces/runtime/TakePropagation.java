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

import ai.badmonkey.agentspaces.agent.TakeContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Carries the in-flight take to another thread. The binder sets
 * {@link TakeContext} as a thread-local on the thread that runs a
 * {@code @SpaceTake} method, and a listener that renews the lease reads it
 * there; work the application moves elsewhere (a future, a streaming
 * callback, a parallel step) loses it. {@link #wrap(Runnable)} and
 * {@link #wrap(Callable)} capture the current take and bind it on the
 * executing thread; {@link #executor(ExecutorService)} does so for every
 * submission, and put back whatever the executing thread had bound before. A
 * lease carried this way keeps the task; it does not make a repeated side
 * effect safe.
 */
public final class TakePropagation {

    private TakePropagation() {
    }

    /** The take on this thread, if any. */
    public static Optional<TakeContext> current() {
        return TakeContext.current();
    }

    /** Runs the task with this thread's take bound on the executing thread. */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Optional<TakeContext> take = TakeContext.current();
        if (take.isEmpty()) {
            return task;
        }
        TakeContext context = take.get();
        return () -> {
            TakeContext previous = TakeContext.peek();
            TakeContext.bind(context);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /** Calls the task with this thread's take bound on the executing thread. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        Optional<TakeContext> take = TakeContext.current();
        if (take.isEmpty()) {
            return task;
        }
        TakeContext context = take.get();
        return () -> {
            TakeContext previous = TakeContext.peek();
            TakeContext.bind(context);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    /** Back to what the executing thread had: its own take (a caller-runs executor), or nothing. */
    private static void restore(TakeContext previous) {
        if (previous != null) {
            TakeContext.bind(previous);
        } else {
            TakeContext.unbind();
        }
    }

    /** An executor that binds the submitting thread's take on every task it runs. */
    public static ExecutorService executor(ExecutorService delegate) {
        return new Propagating(Objects.requireNonNull(delegate, "delegate"));
    }

    private static final class Propagating extends AbstractExecutorService {
        private final ExecutorService delegate;

        Propagating(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(wrap(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
