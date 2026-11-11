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
package ai.badmonkey.agentspaces.examples.langchain4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build hides {@code OPENAI_API_KEY} from the test JVM and from the worker JVM that
 * {@link CrossJvmInteropTest} spawns (Surefire's {@code excludedEnvironmentVariables}), so the worker
 * serves its scripted model whatever key the shell exports.
 */
class NoProviderKeyTest {

    @Test
    void aTestSeesNoOpenAiKey() {
        // a boolean, so a failure never prints the key
        assertThat(System.getenv("OPENAI_API_KEY") != null).as("OPENAI_API_KEY is set").isFalse();
    }
}
