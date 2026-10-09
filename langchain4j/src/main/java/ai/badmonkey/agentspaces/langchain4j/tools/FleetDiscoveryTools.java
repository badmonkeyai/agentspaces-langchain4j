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

import ai.badmonkey.agentspaces.agent.capability.SemanticClient;
import ai.badmonkey.agentspaces.api.ad.AgentCard;
import ai.badmonkey.agentspaces.api.ad.AssetCard;
import ai.badmonkey.agentspaces.capabilities.semantic.SemanticDiscovery;
import ai.badmonkey.agentspaces.connect.DataQueryClient;
import ai.badmonkey.agentspaces.runtime.FleetContext;
import ai.badmonkey.agentspaces.langchain4j.LangChain4jSettings;
import ai.badmonkey.agentspaces.tools.FleetToolCatalog;
import ai.badmonkey.agentspaces.tools.FleetTool;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Tools the model uses to reason about the fleet itself, each switched on
 * separately by {@link LangChain4jSettings.DiscoveryTools}:
 * {@code find_fleet_agents} and {@code list_fleet_assets} over semantic
 * discovery, {@code fetch_fleet_data} over the connector SDK. Results are
 * capped at {@code max-result-chars} before they enter the model's context.
 */
public class FleetDiscoveryTools {

    /** Ranks the fleet's advertisements against a question: (question, limit) to matches. */
    public interface Search extends BiFunction<String, Integer, List<SemanticDiscovery.Match>> {
    }

    /** Reads a data asset: (asset, parameters) to the fetched result, if any arrived. */
    public interface Fetch extends BiFunction<String, Map<String, String>, Optional<DataQueryClient.Fetched>> {
    }

    private final Search search;
    private final Fetch fetch;
    private final FleetToolCatalog fleetTools;
    private final ObjectMapper json = new ObjectMapper();
    private final int maxResultChars;
    private final Set<String> enabled;

    public FleetDiscoveryTools(FleetContext fleet, LangChain4jSettings.DiscoveryTools settings, FleetToolCatalog fleetTools) {
        this(search(fleet, settings), fetch(fleet, settings), fleetTools, settings.maxResultChars(), enabled(settings));
    }

    public FleetDiscoveryTools(Search search, Fetch fetch, FleetToolCatalog fleetTools, int maxResultChars,
                               Set<String> enabled) {
        this.search = search;
        this.fetch = fetch;
        this.fleetTools = fleetTools;
        this.maxResultChars = maxResultChars;
        this.enabled = Set.copyOf(enabled);
    }

    private static Search search(FleetContext fleet, LangChain4jSettings.DiscoveryTools config) {
        if (!config.agents() && !config.assets()) {
            return null;
        }
        SemanticClient semantic = fleet.group().capability(SemanticClient.class);
        return (question, limit) -> semantic.remoteQuery(question, limit, config.timeout());
    }

    private static Fetch fetch(FleetContext fleet, LangChain4jSettings.DiscoveryTools config) {
        if (!config.data()) {
            return null;
        }
        DataQueryClient client = new DataQueryClient(
                fleet.requiredSpace(config.dataSpace(), "agentspaces.langchain4j.discovery-tools.data-space"),
                fleet.peerId().value(), fleet.authorizer());
        return (asset, parameters) -> client.fetch(asset, parameters, config.timeout());
    }

    static Set<String> enabled(LangChain4jSettings.DiscoveryTools config) {
        Set<String> names = new LinkedHashSet<>();
        if (config.agents()) {
            names.add("find_fleet_agents");
        }
        if (config.assets()) {
            names.add("list_fleet_assets");
        }
        if (config.data()) {
            names.add("fetch_fleet_data");
        }
        return names;
    }

    /** Whether a tool is switched on. */
    public boolean enabled(String toolName) {
        return enabled.contains(toolName);
    }

    /** The executor of one of this object's {@code @Tool} methods. */
    public ToolExecutor executor(String toolName) {
        for (Method method : getClass().getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && toolName.equals(tool.name())) {
                return new DefaultToolExecutor(this, method);
            }
        }
        throw new IllegalArgumentException("no discovery tool named " + toolName);
    }

    @Tool(name = "find_fleet_agents", value = "Finds agents in the peer-to-peer fleet whose advertised"
            + " skills match a question. Returns each agent's name, description, goals, input and output"
            + " types, the name of the tool that invokes it (if exposed), and a match score between 0 and 1.")
    public String findFleetAgents(@P("What you need done, in plain language") String question,
                                  @P(value = "The most agents to return, 1 to 20", required = false) Integer limit) {
        Map<String, List<String>> toolsByAgent = new LinkedHashMap<>();
        if (fleetTools != null) {
            for (FleetTool tool : fleetTools.current()) {
                toolsByAgent.computeIfAbsent(tool.action().agent().encoded(), k -> new ArrayList<>()).add(tool.name());
            }
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (SemanticDiscovery.Match match : search(question, limit)) {
            if (match.advertisement() instanceof AgentCard card) {
                Map<String, Object> agent = new LinkedHashMap<>();
                agent.put("agent", card.agent().localName());
                agent.put("description", card.description());
                agent.put("goals", card.goals());
                agent.put("consumes", simpleNames(card.consumes()));
                agent.put("produces", simpleNames(card.produces()));
                agent.put("tools", toolsByAgent.getOrDefault(card.agent().encoded(), List.of()));
                agent.put("score", Math.round(match.score() * 1000) / 1000.0);
                agents.add(agent);
            }
        }
        return render(agents);
    }

    @Tool(name = "list_fleet_assets", value = "Finds data assets that peers in the fleet serve, by meaning."
            + " Returns each asset's name (use it with fetch_fleet_data), its description, source URI, row"
            + " shape, freshness window, and a match score.")
    public String listFleetAssets(@P("What data you need, in plain language") String question,
                                  @P(value = "The most assets to return, 1 to 20", required = false) Integer limit) {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (SemanticDiscovery.Match match : search(question, limit)) {
            if (match.advertisement() instanceof AssetCard card) {
                Map<String, Object> asset = new LinkedHashMap<>();
                asset.put("asset", card.asset());
                asset.put("description", card.description());
                asset.put("uri", card.uri());
                asset.put("shape", card.shape());
                asset.put("freshness", card.freshness());
                asset.put("score", Math.round(match.score() * 1000) / 1000.0);
                assets.add(asset);
            }
        }
        return render(assets);
    }

    @Tool(name = "fetch_fleet_data", value = "Reads rows from a data asset a peer in the fleet serves. An"
            + " identical question asked recently is answered from the fleet's shared results without"
            + " touching the source. Returns the rows, whether they came from the shared results, and which"
            + " peer served them.")
    public String fetchFleetData(@P("The asset's name, as list_fleet_assets returns it") String asset,
                                 @P(value = "Query parameters as name-value pairs", required = false)
                                 Map<String, String> parameters) {
        if (fetch == null) {
            return render(Map.of("error", "fetch_fleet_data is not enabled on this node"));
        }
        Optional<DataQueryClient.Fetched> fetched = fetch.apply(asset,
                parameters == null ? Map.of() : Map.copyOf(parameters));
        if (fetched.isEmpty()) {
            return render(Map.of("error", "no peer answered for asset '" + asset + "' in time"));
        }
        DataQueryClient.Fetched result = fetched.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asset", result.result().asset());
        out.put("fromCache", result.fromCache());
        out.put("servedBy", result.result().servedBy());
        if (result.result().error() != null) {
            out.put("error", result.result().error());
        }
        out.put("rows", result.result().rows());
        return render(out);
    }

    private List<SemanticDiscovery.Match> search(String question, Integer limit) {
        if (search == null) {
            return List.of();
        }
        int bounded = limit == null ? 5 : Math.max(1, Math.min(20, limit));
        return search.apply(question, bounded);
    }

    private String render(Object value) {
        String text;
        try {
            text = json.writeValueAsString(value);
        } catch (JacksonException e) {
            text = String.valueOf(value);
        }
        if (text.length() <= maxResultChars) {
            return text;
        }
        return text.substring(0, maxResultChars - 60) + " ... [truncated at " + maxResultChars + " characters]";
    }

    private static List<String> simpleNames(List<String> schemas) {
        List<String> names = new ArrayList<>();
        for (String schema : schemas) {
            String type = schema.contains("#") ? schema.substring(0, schema.indexOf('#')) : schema;
            int cut = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
            names.add(cut >= 0 ? type.substring(cut + 1) : type);
        }
        return names;
    }
}
