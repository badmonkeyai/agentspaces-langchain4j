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

import ai.badmonkey.agentspaces.agent.capability.AggregateClient;
import ai.badmonkey.agentspaces.agent.capability.VoteClient;
import ai.badmonkey.agentspaces.agent.content.Manifest;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import ai.badmonkey.agentspaces.content.Content;
import ai.badmonkey.agentspaces.content.Fetch;
import ai.badmonkey.agentspaces.content.PublishOptions;
import ai.badmonkey.agentspaces.runtime.FleetContext;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Layer 4 for a model (L3L4-COVERAGE.md §6.3): {@code @Tool} methods a
 * LangChain4j service calls to open a vote, cast a ballot, read a tally or
 * a decision, contribute to a push-sum epoch, and read its estimate, each
 * switched on separately by {@link Settings}. The model acts as this peer's
 * agent through the typed clients the fleet resolves, so a ballot is that
 * agent's attested record and a contribution joins the group's epoch.
 */
public final class FleetCapabilityTools {

    /** The content family's tool names (srad/large-networks-bulk-data.md §7.2). */
    public static final List<String> CONTENT_TOOLS = List.of("publish_content", "fetch_content", "read_content",
            "describe_content", "list_content");

    /**
     * Which tools are on; all off by default. {@code content} is the content swarm family, which
     * needs {@code agentspaces.content.enabled} on the runtime; {@code fetchTimeout} bounds
     * {@code fetch_content}.
     */
    public record Settings(boolean vote, boolean aggregate, boolean content, Duration ballotLease,
                           Duration settleTimeout, Duration fetchTimeout) {
        public Settings {
            Objects.requireNonNull(ballotLease, "ballotLease");
            Objects.requireNonNull(settleTimeout, "settleTimeout");
            Objects.requireNonNull(fetchTimeout, "fetchTimeout");
        }

        /** The two-family form of 0.4.0: content off, a ten-minute fetch timeout. */
        public Settings(boolean vote, boolean aggregate, Duration ballotLease, Duration settleTimeout) {
            this(vote, aggregate, false, ballotLease, settleTimeout, Duration.ofMinutes(10));
        }

        public static Settings defaults() {
            return new Settings(false, false, Duration.ofHours(1), Duration.ofSeconds(10));
        }

        /** Every family on. */
        public static Settings all() {
            return new Settings(true, true, true, Duration.ofHours(1), Duration.ofSeconds(10), Duration.ofMinutes(10));
        }

        public Settings withVote(boolean on) {
            return new Settings(on, aggregate, content, ballotLease, settleTimeout, fetchTimeout);
        }

        public Settings withAggregate(boolean on) {
            return new Settings(vote, on, content, ballotLease, settleTimeout, fetchTimeout);
        }

        public Settings withContent(boolean on) {
            return new Settings(vote, aggregate, on, ballotLease, settleTimeout, fetchTimeout);
        }

        public Settings withFetchTimeout(Duration timeout) {
            return new Settings(vote, aggregate, content, ballotLease, settleTimeout, timeout);
        }

        public boolean any() {
            return vote || aggregate || content;
        }
    }

    private final Supplier<VoteClient> vote;
    private final Supplier<AggregateClient> aggregate;
    private final Supplier<Content> content;
    private final Settings settings;
    private final Set<String> enabled;
    private final ObjectMapper json = new ObjectMapper();

    public FleetCapabilityTools(FleetContext fleet, Settings settings) {
        this(() -> fleet.group().capability(VoteClient.class), () -> fleet.group().capability(AggregateClient.class),
                () -> contentOf(fleet), settings);
    }

    public FleetCapabilityTools(Supplier<VoteClient> vote, Supplier<AggregateClient> aggregate, Settings settings) {
        this(vote, aggregate, () -> {
            throw new IllegalStateException("no content swarm was given to these tools");
        }, settings);
    }

    public FleetCapabilityTools(Supplier<VoteClient> vote, Supplier<AggregateClient> aggregate,
                                Supplier<Content> content, Settings settings) {
        this.vote = vote;
        this.aggregate = aggregate;
        this.content = content;
        this.settings = Objects.requireNonNull(settings, "settings");
        Set<String> names = new LinkedHashSet<>();
        if (settings.vote()) {
            names.addAll(List.of("propose_vote", "cast_ballot", "read_tally", "read_decision"));
        }
        if (settings.aggregate()) {
            names.addAll(List.of("contribute", "read_estimate"));
        }
        if (settings.content()) {
            names.addAll(CONTENT_TOOLS);
        }
        this.enabled = Set.copyOf(names);
    }

    /** The group's content swarm, when {@code agentspaces.content.enabled} built one. */
    public static Content contentOf(FleetContext fleet) {
        return fleet.group().provider(Content.TYPE)
                .filter(Content.class::isInstance)
                .map(Content.class::cast)
                .orElseThrow(() -> new IllegalStateException(
                        "the content swarm is not enabled in this group (agentspaces.content.enabled=true)"));
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
        throw new IllegalArgumentException("no capability tool named " + toolName);
    }

    @Tool(name = "propose_vote", value = "Opens a vote in the fleet: a proposal with an id, a question, the options"
            + " to choose from, and the number of distinct voters that closes it. Idempotent on the id.")
    public String proposeVote(@P("A short unique id for the proposal") String id,
                              @P("The question the fleet votes on") String question,
                              @P("The options, at least two") List<String> options,
                              @P("How many distinct voters close the vote") int quorum) {
        VoteClient client = vote.get();
        if (client.proposal(id).isEmpty()) {
            client.propose(id, question, options, quorum, Lease.of(settings.ballotLease()));
        }
        return render(proposal(client.proposal(id).orElseThrow()));
    }

    @Tool(name = "cast_ballot", value = "Casts this agent's ballot on an open proposal; one ballot per proposal counts.")
    public String castBallot(@P("The proposal's id") String id, @P("The option, exactly as proposed") String option) {
        VoteClient client = vote.get();
        Optional<VoteCapability.Proposal> proposal = client.proposal(id);
        if (proposal.isEmpty()) {
            return render(Map.of("error", "no proposal '" + id + "' is open here"));
        }
        if (!proposal.get().options().contains(option)) {
            return render(Map.of("error", "'" + option + "' is not one of " + proposal.get().options()));
        }
        client.castBallot(id, option, Lease.of(settings.ballotLease()));
        return render(Map.of("proposalId", id, "cast", option));
    }

    @Tool(name = "read_tally", value = "The votes per option on a proposal so far.")
    public String readTally(@P("The proposal's id") String id) {
        return render(Map.of("proposalId", id, "tally", vote.get().tally(id)));
    }

    @Tool(name = "read_decision", value = "The decision of a proposal once its quorum closed it: the winner and"
            + " the tally; or that it is still open.")
    public String readDecision(@P("The proposal's id") String id) {
        Optional<VoteCapability.Decision> decision = vote.get().decision(id);
        if (decision.isEmpty()) {
            return render(Map.of("proposalId", id, "open", true));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", id);
        out.put("winner", decision.get().winner());
        out.put("tally", decision.get().tally());
        return render(out);
    }

    @Tool(name = "contribute", value = "Contributes this peer's value to a fleet-wide average under an epoch id;"
            + " every peer that contributes under the same id is averaged together.")
    public String contribute(@P("The epoch id, shared by every contributor") String epoch,
                             @P("This peer's value") double value) {
        aggregate.get().start(epoch, value);
        return render(Map.of("epoch", epoch, "contributed", value));
    }

    @Tool(name = "read_estimate", value = "The fleet-wide average under an epoch id, waiting briefly for it to"
            + " settle; or that nothing is known yet.")
    public String readEstimate(@P("The epoch id") String epoch) {
        AggregateClient client = aggregate.get();
        OptionalDouble settled = client.awaitSettled(epoch, PushSumAggregate.Settle.after(2, 0.01),
                settings.settleTimeout());
        OptionalDouble value = settled.isPresent() ? settled : client.estimate(epoch);
        if (value.isEmpty()) {
            return render(Map.of("epoch", epoch, "known", false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("epoch", epoch);
        out.put("estimate", value.getAsDouble());
        out.put("settled", settled.isPresent());
        return render(out);
    }

    // ---- the content family (srad/large-networks-bulk-data.md §7.2)

    @Tool(name = "publish_content", value = "Publishes a file from this peer's disk as a fleet artifact: hashed in"
            + " pieces, announced to the group, and served to every member that fetches it. Returns the root,"
            + " the content id other members fetch by.")
    public String publishContent(@P("The path of the file on this peer") String path,
                                 @P("A display name for the artifact, or empty") String name) {
        try {
            PublishOptions options = content.get().publishOptions();
            if (name != null && !name.isBlank()) {
                options = options.withName(name);
            }
            return render(manifest(content.get().publish(Path.of(path), options)));
        } catch (IOException | RuntimeException e) {
            return render(Map.of("error", "publish failed: " + e.getMessage()));
        }
    }

    @Tool(name = "fetch_content", value = "Fetches a fleet artifact by its root from every member that holds it,"
            + " waiting up to the configured timeout; returns how much arrived and whether it is complete.")
    public String fetchContent(@P("The artifact's root (a content id starting with b)") String root) {
        try {
            Fetch fetch = content.get().fetch(root);
            boolean complete = fetch.await(settings.fetchTimeout());
            return render(progress(root, fetch, complete));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return render(Map.of("error", "interrupted"));
        } catch (RuntimeException e) {
            return render(Map.of("error", "fetch failed: " + e.getMessage()));
        }
    }

    @Tool(name = "read_content", value = "Reads a fetched fleet artifact as UTF-8 text, up to a number of"
            + " characters; fetch it first.")
    public String readContent(@P("The artifact's root") String root,
                              @P("The most characters to return") int maxChars) {
        try {
            Fetch fetch = content.get().fetch(root);
            if (!fetch.complete()) { // await(ZERO) waits without bound; a read never blocks
                return render(Map.of("root", root, "complete", false, "error", "not fetched yet; call fetch_content"));
            }
            try (SeekableByteChannel channel = fetch.open()) {
                int bound = (int) Math.min(Math.max(0, maxChars) * 4L, channel.size());
                ByteBuffer buffer = ByteBuffer.allocate(bound);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // drain
                }
                String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("root", root);
                out.put("size", channel.size());
                out.put("text", text.length() > maxChars ? text.substring(0, Math.max(0, maxChars)) : text);
                out.put("truncated", text.length() > maxChars || bound < channel.size());
                return render(out);
            }
        } catch (IOException | RuntimeException e) {
            return render(Map.of("error", "read failed: " + e.getMessage()));
        }
    }

    @Tool(name = "describe_content", value = "What the fleet knows about an artifact: its manifest and how many"
            + " members hold it in full or in part.")
    public String describeContent(@P("The artifact's root") String root) {
        Optional<Content.Description> described = content.get().describe(root);
        if (described.isEmpty()) {
            return render(Map.of("root", root, "known", false));
        }
        Map<String, Object> out = manifest(described.get().manifest());
        out.put("completeHolders", described.get().completeHolders());
        out.put("partialHolders", described.get().partialHolders());
        out.put("heldHere", described.get().held().cardinality());
        return render(out);
    }

    @Tool(name = "list_content", value = "The artifacts announced in this group: root, name, size, and media type"
            + " of each.")
    public String listContent() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Manifest m : content.get().list(100)) {
            out.add(manifest(m));
        }
        return render(out);
    }

    private static Map<String, Object> manifest(Manifest m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", m.root());
        out.put("name", m.name());
        out.put("size", m.size());
        out.put("pieces", m.pieces());
        out.put("mediaType", m.mediaType());
        return out;
    }

    private static Map<String, Object> progress(String root, Fetch fetch, boolean complete) {
        Fetch.Progress p = fetch.progress();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", root);
        out.put("complete", complete);
        out.put("piecesVerified", p.piecesVerified());
        out.put("pieces", p.pieces());
        out.put("bytesReceived", p.bytesReceived());
        out.put("sources", p.bytesBySource().size());
        return out;
    }

    private static Map<String, Object> proposal(VoteCapability.Proposal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", p.proposalId());
        out.put("question", p.question());
        out.put("options", p.options());
        out.put("quorum", p.quorum());
        return out;
    }

    private String render(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
