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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsAndClasspathTest {

    @Test
    void settingsBindFromTheSharedKeysAndDefaultOtherwise() {
        LangChain4jSettings s = LangChain4jSettings.from(Map.of(
                "discovery-tools", Map.of("agents", true, "timeout", "5s"),
                "take-lease", Map.of("stream-renew-interval", "2s"),
                "memory", Map.of("enabled", "true", "conversation-id", "explicit", "max-messages", 7),
                "model-client", Map.of("enabled", true, "model", "gpt-4.1-mini", "stream", Map.of("on-restart", "restart")),
                "model-server", Map.of("enabled", true, "models", List.of("gpt-4.1-mini"), "routing", "price",
                        "prices", Map.of("gpt-4.1-mini", 0.4), "concurrency", 2),
                "embedder", Map.of("model", "text-embedding-3-small"),
                "usage", Map.of("enabled", true, "metrics-space", "metrics")));
        assertThat(s.discoveryTools().agents()).isTrue();
        assertThat(s.discoveryTools().assets()).isFalse();
        assertThat(s.discoveryTools().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(s.takeLease().enabled()).isTrue();
        assertThat(s.takeLease().streamRenewInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(s.memory().enabled()).isTrue();
        assertThat(s.memory().conversationId()).isEqualTo(LangChain4jSettings.ConversationIdMode.EXPLICIT);
        assertThat(s.memory().maxMessages()).isEqualTo(7);
        assertThat(s.memory().space()).isEqualTo("conversations");
        assertThat(s.modelClient().model()).isEqualTo("gpt-4.1-mini");
        assertThat(s.modelClient().onRestart()).isEqualTo(LangChain4jSettings.RestartMode.RESTART);
        assertThat(s.modelServer().routing()).isEqualTo(LangChain4jSettings.Routing.PRICE);
        assertThat(s.modelServer().prices()).containsEntry("gpt-4.1-mini", 0.4);
        assertThat(s.modelServer().concurrency()).isEqualTo(2);
        assertThat(s.modelServer().lease()).isEqualTo(Duration.ofMinutes(2));
        assertThat(s.embedder().model()).isEqualTo("text-embedding-3-small");
        assertThat(s.usage().metricsSpace()).isEqualTo("metrics");
        assertThat(LangChain4jSettings.defaults().memory().enabled()).isFalse();
        assertThat(LangChain4jSettings.from(Map.of())).isEqualTo(LangChain4jSettings.defaults());
    }

    @Test
    void unknownKeysFailWhereTheyAreRead() {
        assertThatThrownBy(() -> LangChain4jSettings.from(Map.of("memory", Map.of("enabld", true))))
                .hasMessageContaining("agentspaces.langchain4j.memory").hasMessageContaining("enabld");
        assertThatThrownBy(() -> LangChain4jSettings.from(Map.of("memroy", Map.of())))
                .hasMessageContaining("memroy");
    }

    @Test
    void noSpringAndNoMicronautClassResolves() {
        for (String name : new String[] {"org.springframework.context.SmartLifecycle",
                "io.micronaut.context.ApplicationContext", "jakarta.inject.Singleton"}) {
            assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
        }
    }
}
