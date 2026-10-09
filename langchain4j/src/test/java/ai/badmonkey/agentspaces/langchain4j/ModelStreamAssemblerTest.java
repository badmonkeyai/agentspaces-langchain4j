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

import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings.RestartMode;
import ai.badmonkey.agentspaces.langchain4j.model.FleetStreamRestartedException;
import ai.badmonkey.agentspaces.langchain4j.model.ModelStreamAssembler;
import ai.badmonkey.agentspaces.langchain4j.model.StreamRestartPolicy;
import ai.badmonkey.agentspaces.springai.model.wire.ModelChunk;
import ai.badmonkey.agentspaces.springai.model.wire.WireGeneration;
import ai.badmonkey.agentspaces.springai.model.wire.WireMessage;
import ai.badmonkey.agentspaces.springai.model.wire.WireUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelStreamAssemblerTest {

    private static ModelChunk chunk(int attempt, int seq, String text, boolean last) {
        return new ModelChunk("r", attempt, seq, List.of(new WireGeneration(
                new WireMessage("assistant", text, List.of(), List.of(), List.of(), Map.of()), last ? "STOP" : null)),
                last, last ? new WireUsage(1, 2, 3) : null, null, "peer");
    }

    private record Recorder(List<String> text, List<String> events) implements ModelStreamAssembler.Listener {
        @Override
        public void onChunk(ModelChunk chunk) {
            chunk.deltas().forEach(d -> text.add(d.output().text()));
        }

        @Override
        public void onRestart(int fromAttempt, int toAttempt) {
            events.add("restart " + fromAttempt + "->" + toAttempt);
        }

        @Override
        public void onComplete(ModelChunk last) {
            events.add("complete");
        }

        @Override
        public void onError(Throwable error) {
            events.add("error " + error.getClass().getSimpleName());
        }
    }

    @Test
    void reordersBySequenceAndCompletesOnTheLastChunk() {
        Recorder recorder = new Recorder(new ArrayList<>(), new ArrayList<>());
        ModelStreamAssembler assembler = new ModelStreamAssembler("r", StreamRestartPolicy.of(RestartMode.FAIL), recorder);
        assembler.accept(chunk(1, 2, "c", true));
        assembler.accept(chunk(1, 0, "a", false));
        assembler.accept(chunk(1, 0, "a", false)); // a duplicate delivery
        assembler.accept(chunk(1, 1, "b", false));
        assertThat(recorder.text()).containsExactly("a", "b", "c");
        assertThat(recorder.events()).containsExactly("complete");
        assertThat(assembler.isDone()).isTrue();
        assertThat(assembler.emittedText()).isEqualTo("abc");
    }

    @Test
    void aSecondAttemptFailsOrRestartsByPolicy() {
        Recorder failing = new Recorder(new ArrayList<>(), new ArrayList<>());
        ModelStreamAssembler fail = new ModelStreamAssembler("r", StreamRestartPolicy.of(RestartMode.FAIL), failing);
        fail.accept(chunk(1, 0, "par", false));
        fail.accept(chunk(2, 0, "again", false));
        assertThat(failing.events()).containsExactly("error " + FleetStreamRestartedException.class.getSimpleName());
        assertThat(fail.isDone()).isTrue();

        Recorder restarting = new Recorder(new ArrayList<>(), new ArrayList<>());
        ModelStreamAssembler restart = new ModelStreamAssembler("r", StreamRestartPolicy.of(RestartMode.RESTART), restarting);
        restart.accept(chunk(1, 0, "par", false));
        restart.accept(chunk(2, 0, "again", false));
        restart.accept(chunk(2, 1, "!", true));
        assertThat(restarting.events()).containsExactly("restart 1->2", "complete");
        assertThat(restarting.text()).containsExactly("par", "again", "!");
        assertThat(restart.attempt()).isEqualTo(2);
        assertThat(restart.emittedText()).isEqualTo("again!");
    }
}
