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

import ai.badmonkey.agentspaces.langchain4j.support.Fleets;
import ai.badmonkey.agentspaces.langchain4j.tools.FleetCapabilityTools;
import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.PropertiesLoader;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content swarm tools (srad/large-networks-bulk-data.md §7.2): one peer publishes a file
 * through {@code publish_content}, the other fetches it by root, reads its text, and lists and
 * describes what the group announced.
 */
class ContentToolsTest {

    private static FleetRuntime start(int port, int seedPort) {
        Map<String, Object> props = Fleets.fleet(port, seedPort);
        props.put("agentspaces.content.enabled", "true");
        props.put("agentspaces.content.piece-size", "65536");
        props.put("agentspaces.content.store.backends[0]", "memory");
        return FleetRuntime.start(PropertiesLoader.flat(props));
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as(name + " in " + json).isTrue();
        return m.group(1);
    }

    @Test
    @Timeout(120)
    void aModelPublishesFetchesReadsAndListsArtifacts(@TempDir Path dir) throws Exception {
        int seedPort = Fleets.freePort();
        try (FleetRuntime a = start(seedPort, 0); FleetRuntime b = start(Fleets.freePort(), seedPort)) {
            FleetCapabilityTools.Settings settings = FleetCapabilityTools.Settings.defaults().withContent(true)
                    .withFetchTimeout(Duration.ofSeconds(60));
            LangChain4jFleet fa = LangChain4jFleet.builder(a).capabilityTools(settings).build();
            LangChain4jFleet fb = LangChain4jFleet.builder(b).capabilityTools(settings).build();
            assertThat(fb.tools().provideTools(new ToolProviderRequest("m", UserMessage.from("x"))).aiServiceTools())
                    .extracting(t -> t.toolSpecification().name())
                    .containsExactlyInAnyOrderElementsOf(FleetCapabilityTools.CONTENT_TOOLS);

            StringBuilder text = new StringBuilder();
            while (text.length() < 3 * 65536 + 100) {
                text.append("the quick brown fox jumps over the lazy dog ").append(text.length()).append('\n');
            }
            Path file = dir.resolve("notes.txt");
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            String published = fa.capabilityTools().publishContent(file.toString(), "notes.txt");
            assertThat(published).contains("\"name\":\"notes.txt\"").contains("\"pieces\":4");
            String root = field(published, "root");
            assertThat(root).startsWith("b");

            assertThat(Fleets.await(Duration.ofSeconds(30),
                    () -> fb.capabilityTools().describeContent(root).contains("\"completeHolders\":1"))).isTrue();
            String fetched = fb.capabilityTools().fetchContent(root);
            assertThat(fetched).contains("\"complete\":true").contains("\"piecesVerified\":4");
            String read = fb.capabilityTools().readContent(root, 60);
            assertThat(read).contains("\"text\":\"the quick brown fox jumps over the lazy dog 44").contains("\"truncated\":true");
            assertThat(fb.capabilityTools().listContent()).contains(root).contains("notes.txt");
            assertThat(Fleets.await(Duration.ofSeconds(30),
                    () -> fa.capabilityTools().describeContent(root).contains("\"completeHolders\":2"))).isTrue();
            assertThat(fa.capabilityTools().readContent("bnotaroot", 10)).contains("error");
        }
    }
}
