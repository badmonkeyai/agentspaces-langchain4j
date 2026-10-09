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
package ai.badmonkey.agentspaces.langchain4j.tools;

import ai.badmonkey.agentspaces.tools.FleetToolCatalog;
import ai.badmonkey.agentspaces.tools.FleetTool;
import ai.badmonkey.agentspaces.tools.ToolInvocation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * The fleet's AgentCards as LangChain4j tools: every {@code provideTools} call
 * returns the catalogue's current actions as {@code ToolSpecification}s with
 * executors that write the input into the task space and await the result,
 * plus the enabled discovery tools. LangChain4j asks on every service
 * invocation, so the tool set is live by construction. Attach it with
 * {@code AiServices.builder(...).toolProvider(...)}, or let a host apply it
 * to every service it builds; {@code discovery} may be null.
 */
public class FleetToolProvider implements ToolProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FleetToolProvider.class);

    private final FleetToolCatalog actions;
    private final FleetDiscoveryTools discovery;
    private final FleetCapabilityTools capabilities;

    public FleetToolProvider(FleetToolCatalog actions, FleetDiscoveryTools discovery) {
        this(actions, discovery, null);
    }

    public FleetToolProvider(FleetToolCatalog actions, FleetDiscoveryTools discovery, FleetCapabilityTools capabilities) {
        if (actions == null && discovery == null && capabilities == null) {
            throw new IllegalArgumentException("a FleetToolProvider needs a catalogue, discovery tools, capability tools, or some of them");
        }
        this.actions = actions;
        this.discovery = discovery;
        this.capabilities = capabilities;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult.Builder result = ToolProviderResult.builder();
        if (actions != null) {
            for (FleetTool tool : actions.current()) {
                result.add(specification(tool), executor(tool));
            }
        }
        if (discovery != null) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(discovery)) {
                if (discovery.enabled(spec.name())) {
                    result.add(spec, discovery.executor(spec.name()));
                }
            }
        }
        if (capabilities != null) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(capabilities)) {
                if (capabilities.enabled(spec.name())) {
                    result.add(spec, capabilities.executor(spec.name()));
                }
            }
        }
        return result.build();
    }

    /** The current fleet tool specifications, for callers that list rather than call. */
    public List<ToolSpecification> specifications() {
        return actions == null ? List.of() : actions.current().stream().map(FleetToolProvider::specification).toList();
    }

    static ToolSpecification specification(FleetTool tool) {
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(JsonSchemas.objectSchema(tool.inputSchema()))
                .build();
    }

    private ToolExecutor executor(FleetTool tool) {
        return new FleetToolExecutor(tool, actions.invocation());
    }

    /** Runs one fleet tool; a timeout is an error result or a failure per {@code on-timeout}. */
    record FleetToolExecutor(FleetTool tool, ToolInvocation invocation) implements ToolExecutor {

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            return invocation.call(tool.action(), request.arguments()).text();
        }

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
            try {
                ToolInvocation.Outcome outcome = invocation.call(tool.action(), request.arguments());
                return ToolExecutionResult.builder().resultText(outcome.text()).isError(outcome.error()).build();
            } catch (ToolInvocation.ToolInvocationException e) {
                LOG.debug("fleet tool {} failed", tool.name(), e);
                return ToolExecutionResult.builder().resultText(e.getMessage()).isError(true).build();
            }
        }
    }
}
