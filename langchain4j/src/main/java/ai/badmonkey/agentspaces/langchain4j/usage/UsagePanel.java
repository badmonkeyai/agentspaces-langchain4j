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
package ai.badmonkey.agentspaces.langchain4j.usage;

import ai.badmonkey.agentspaces.console.ConsolePanel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The console's model-usage section: this peer by model and agent, the fleet by model. */
public class UsagePanel implements ConsolePanel {

    private final FleetUsage usage;

    public UsagePanel(FleetUsage usage) {
        this.usage = Objects.requireNonNull(usage, "usage");
    }

    @Override
    public String id() {
        return "model-usage";
    }

    @Override
    public String title() {
        return "Model usage (tokens)";
    }

    @Override
    public Object data() {
        Map<String, Object> fleet = new LinkedHashMap<>();
        usage.localByModel().keySet().forEach(model ->
                usage.fleetTotal(model).ifPresent(total -> fleet.put(model, Math.round(total))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("thisPeerByModel", usage.localByModel());
        data.put("thisPeerByAgent", usage.localByAgent());
        data.put("fleetByModel", fleet);
        return data;
    }
}
