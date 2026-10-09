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

import java.time.Duration;

/** When a server flushes buffered deltas into a chunk: by count or by time. */
public interface StreamChunkingPolicy {

    boolean flush(int buffered, Duration sinceLastFlush);

    Duration interval();

    static StreamChunkingPolicy of(Duration interval, int maxDeltas) {
        return new StreamChunkingPolicy() {
            @Override
            public boolean flush(int buffered, Duration sinceLastFlush) {
                return buffered > 0 && (buffered >= maxDeltas || sinceLastFlush.compareTo(interval) >= 0);
            }

            @Override
            public Duration interval() {
                return interval;
            }
        };
    }
}
