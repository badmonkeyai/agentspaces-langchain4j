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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The no-framework rule: nothing from Spring or Micronaut resolves on this module's classpath. */
class NoFrameworkClasspathTest {

    @Test
    void noSpringAndNoMicronautClassResolves() {
        for (String name : new String[] {
                "org.springframework.context.SmartLifecycle",
                "org.springframework.context.annotation.Bean",
                "io.micronaut.context.ApplicationContext",
                "jakarta.inject.Singleton"}) {
            assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void theAutoconfigureBuildMethodsLoadWithoutSpring() throws Exception {
        // The class carries Spring annotations the JVM ignores when their types are absent.
        Class<?> wiring = Class.forName("ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration");
        assertThat(wiring.getConstructor().newInstance()).isNotNull();
        assertThat(com.fasterxml.jackson.databind.ObjectMapper.class.getPackage().getImplementationVersion())
                .as("Jackson from the AgentSpaces BOM").startsWith("2.");
    }
}
