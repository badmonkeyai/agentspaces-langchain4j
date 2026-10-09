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
package ai.badmonkey.agentspaces.langchain4j.worker;

import ai.badmonkey.agentspaces.agent.TakeContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import ai.badmonkey.agentspaces.runtime.FleetScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

/**
 * Keeps a worker's take alive while its model loop runs: on every request,
 * the in-flight {@link TakeContext} (the thread-local the binder sets for a
 * {@code @SpaceTake} method) is renewed and a ticker renews it again every
 * {@code stream-renew-interval} until the response or error arrives, so a
 * short lease suits a long loop and a dead worker's task reappears soon.
 * Pass it to the model builder's {@code listeners(...)}, or wrap a model
 * with {@code ListeningChatModel}; {@code TakePropagation} carries the take
 * to other threads.
 */
public class TakeLeaseListener implements ChatModelListener {

    private static final Logger LOG = LoggerFactory.getLogger(TakeLeaseListener.class);
    private static final String TICKER = "agentspaces.take-lease.ticker";

    private final FleetScheduler scheduler;
    private final Duration renewInterval;
    private final Supplier<Optional<TakeContext>> current;

    public TakeLeaseListener(FleetScheduler scheduler, Duration renewInterval) {
        this(scheduler, renewInterval, TakeContext::current);
    }

    public TakeLeaseListener(FleetScheduler scheduler, Duration renewInterval,
                             Supplier<Optional<TakeContext>> current) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renewInterval = Objects.requireNonNull(renewInterval, "renewInterval");
        this.current = Objects.requireNonNull(current, "current");
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        current.get().ifPresent(take -> {
            renew(take);
            ScheduledFuture<?> ticker = scheduler.scheduleAtFixedRate(renewInterval, renewInterval,
                    () -> renew(take));
            context.attributes().put(TICKER, ticker);
        });
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        stop(context.attributes().get(TICKER));
        current.get().ifPresent(TakeLeaseListener::renew);
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        stop(context.attributes().get(TICKER));
    }

    private static void stop(Object ticker) {
        if (ticker instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    private static void renew(TakeContext take) {
        try {
            take.renew();
        } catch (RuntimeException e) {
            LOG.debug("take lease renewal failed", e);
        }
    }
}
