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

import ai.badmonkey.agentspaces.agent.AgentBinder;
import ai.badmonkey.agentspaces.agent.Entries;
import ai.badmonkey.agentspaces.agent.Tagged;
import ai.badmonkey.agentspaces.agent.annotation.AgentSpec;
import ai.badmonkey.agentspaces.agent.annotation.Ballot;
import ai.badmonkey.agentspaces.agent.annotation.BidFunction;
import ai.badmonkey.agentspaces.agent.annotation.CapabilityRef;
import ai.badmonkey.agentspaces.agent.annotation.OnDecision;
import ai.badmonkey.agentspaces.agent.annotation.OnEstimate;
import ai.badmonkey.agentspaces.agent.annotation.Part;
import ai.badmonkey.agentspaces.agent.annotation.Propose;
import ai.badmonkey.agentspaces.agent.annotation.SpaceJoin;
import ai.badmonkey.agentspaces.agent.annotation.SpaceNotify;
import ai.badmonkey.agentspaces.agent.annotation.SpaceReduce;
import ai.badmonkey.agentspaces.agent.annotation.SpaceRef;
import ai.badmonkey.agentspaces.agent.annotation.SpaceTake;
import ai.badmonkey.agentspaces.agent.capability.AggregateClient;
import ai.badmonkey.agentspaces.agent.capability.Contribution;
import ai.badmonkey.agentspaces.agent.capability.Motion;
import ai.badmonkey.agentspaces.agent.capability.VoteClient;
import ai.badmonkey.agentspaces.agent.join.Joined;
import ai.badmonkey.agentspaces.agent.reduce.Reductions;
import ai.badmonkey.agentspaces.api.space.Lease;
import ai.badmonkey.agentspaces.api.space.Matchers;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.space.SpaceEvent;
import ai.badmonkey.agentspaces.api.space.Template;
import ai.badmonkey.agentspaces.capabilities.aggregate.PushSumAggregate;
import ai.badmonkey.agentspaces.capabilities.vote.VoteCapability;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Every annotation and return convention this project passes through to the
 * core binder, proven through {@code FleetRuntime.bind(...)} on two runtimes
 * joined over TCP under the {@code dev-local} profile with the default
 * capabilities (vote over the registered {@code votes} space, push-sum
 * aggregate) on. Each outcome is read back from a space.
 */
class AnnotationPassthroughTest {

    private static final Lease HOUR = Lease.of(Duration.ofHours(1));
    private static final Duration WAIT = Duration.ofSeconds(30);

    private static FleetRuntime a;
    private static FleetRuntime b;

    private final List<AgentBinder.Bound> bound = new ArrayList<>();

    @BeforeAll
    static void startTwoRuntimes() {
        int seedPort = Fleets.freePort();
        a = FleetRuntime.start(PropertiesLoader.flat(props(seedPort, 0)));
        b = FleetRuntime.start(PropertiesLoader.flat(props(Fleets.freePort(), seedPort)));
        assertThat(Fleets.await(WAIT, () -> sees(a, b) && sees(b, a))).as("both peers are members").isTrue();
    }

    @AfterAll
    static void stopTwoRuntimes() {
        if (b != null) {
            b.close();
        }
        if (a != null) {
            a.close();
        }
    }

    @AfterEach
    void unbind() {
        bound.forEach(AgentBinder.Bound::close);
    }

    /** The it-fleet set plus this test's spaces; {@code auction} runs the AUCTION strategy. */
    private static Map<String, Object> props(int port, int seedPort) {
        Map<String, Object> props = Fleets.fleet(port, seedPort, "votes", "cues", "auction", "intake", "ledger", "readings");
        props.put("agentspaces.security.profile", "dev-local");
        props.put(spaceKey(props, "auction", "strategy"), "AUCTION");
        props.put(spaceKey(props, "auction", "settle-window-millis"), "1000");
        return props;
    }

    private static String spaceKey(Map<String, Object> props, String space, String property) {
        return props.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".name") && space.equals(e.getValue()))
                .map(e -> e.getKey().replace(".name", "." + property))
                .findFirst().orElseThrow();
    }

    private static boolean sees(FleetRuntime self, FleetRuntime other) {
        return self.context().group().runtime().membership().member(other.identity().peerId()).isPresent();
    }

    private static Space space(FleetRuntime runtime, String name) {
        return runtime.context().group().space(name);
    }

    private void bind(FleetRuntime runtime, Object... agents) {
        for (Object agent : agents) {
            List<AgentBinder.Bound> handles = runtime.bind(agent);
            assertThat(handles).as(agent.getClass().getSimpleName() + " binds into one group").hasSize(1);
            bound.addAll(handles);
        }
    }

    // ------------------------------------------------ @SpaceNotify, Tagged, Entries

    public record Reminder(String id) {
    }

    public record Escalation(String id) {
    }

    public record Task(String id) {
    }

    public record Done(String id) {
    }

    public record Brief(String id) {
    }

    public record QuoteRequest(String id, int expected) {
    }

    public record QuoteTask(String id, String shop) {
    }

    @AgentSpec(name = "escalator", description = "Escalates lapsed reminders", goals = {"deadline"})
    public static class Escalator {
        @SpaceNotify(space = "tasks", on = SpaceEvent.Kind.EXPIRED)
        public Escalation escalate(Reminder reminder) {
            return new Escalation(reminder.id());
        }
    }

    @AgentSpec(name = "labeller", description = "Tags what it finishes", goals = {"label"})
    public static class Labeller {
        @SpaceNotify(space = "tasks")
        public Tagged<Done> label(Task task) {
            return Tagged.of(new Done(task.id()), "via", "notify");
        }
    }

    @AgentSpec(name = "forker", description = "Forks a brief into a request and tasks", goals = {"fork"})
    public static class Forker {
        @SpaceNotify(space = "tasks", produces = {QuoteRequest.class, QuoteTask.class})
        public Entries fork(Brief brief) {
            return Entries.of(new QuoteRequest(brief.id(), 2),
                    new QuoteTask(brief.id(), "north"), new QuoteTask(brief.id(), "east"));
        }
    }

    @Test
    @Timeout(60)
    void aNotifyFiresOnExpiryAndWritesTaggedAndForkedReturns() {
        bind(a, new Escalator(), new Labeller(), new Forker());
        Space tasks = space(a, "tasks");
        tasks.write(new Reminder("r1"), Lease.of(Duration.ofMillis(300)));
        tasks.write(new Task("t1"), HOUR);
        tasks.write(new Brief("b1"), HOUR);

        assertThat(Fleets.await(WAIT, () -> !tasks.readAll(Template.of(Escalation.class), 10).isEmpty())).isTrue();
        assertThat(tasks.readAll(Template.of(Escalation.class), 10)).extracting(Escalation::id).containsExactly("r1");
        assertThat(Fleets.await(WAIT, () -> !tasks.readAllEntries(
                Template.of(Done.class).whereTag("via", Matchers.eq("notify")), 10).isEmpty())).isTrue();
        assertThat(Fleets.await(WAIT, () -> tasks.readAll(Template.of(QuoteTask.class), 10).size() == 2
                && tasks.readAll(Template.of(QuoteRequest.class), 10).size() == 1)).isTrue();
    }

    // ------------------------------------------------ @SpaceRef

    public record Job(String topic) {
    }

    public record Progress(String topic, String state) {
    }

    @AgentSpec(name = "worker", description = "Works with an injected handle", goals = {"work"})
    public static class Worker {
        @SpaceRef("findings")
        Space findings;

        @SpaceTake(space = "tasks", pollTimeout = "PT0.2S")
        public Progress work(Job job) {
            findings.write(new Progress(job.topic(), "in flight"), HOUR);
            return new Progress(job.topic(), "done");
        }
    }

    @Test
    @Timeout(60)
    void aSpaceRefHandleWritesMidMethodIntoAnotherSpace() {
        Worker worker = new Worker();
        bind(a, worker);
        assertThat(worker.findings).isNotNull();
        space(b, "tasks").write(new Job("offsite"), HOUR);

        Space findings = space(a, "findings");
        assertThat(Fleets.await(WAIT, () -> !findings.readAll(Template.of(Progress.class), 10).isEmpty())).isTrue();
        assertThat(findings.readAll(Template.of(Progress.class), 10)).containsExactly(new Progress("offsite", "in flight"));
        assertThat(Fleets.await(WAIT, () -> !space(a, "tasks").readAll(Template.of(Progress.class), 10).isEmpty())).isTrue();
        assertThat(space(a, "tasks").readAll(Template.of(Progress.class), 10)).containsExactly(new Progress("offsite", "done"));
    }

    // ------------------------------------------------ @BidFunction on an AUCTION space

    public record Lot(String topic) {
    }

    public record Award(String topic, String who) {
    }

    @AgentSpec(name = "pricey", description = "Bids high", goals = {"bid"})
    public static class Pricey {
        final AtomicInteger bids = new AtomicInteger();

        @BidFunction(space = "auction")
        public double bid(Lot lot) {
            bids.incrementAndGet();
            return 100;
        }

        @SpaceTake(space = "auction", pollTimeout = "PT0.2S")
        public Award work(Lot lot) {
            return new Award(lot.topic(), "pricey");
        }
    }

    @AgentSpec(name = "cheap", description = "Bids low", goals = {"bid"})
    public static class Cheap {
        @BidFunction(space = "auction")
        public double bid(Lot lot) {
            return 1;
        }

        @SpaceTake(space = "auction", pollTimeout = "PT0.2S")
        public Award work(Lot lot) {
            return new Award(lot.topic(), "cheap");
        }
    }

    @Test
    @Timeout(90)
    void theCheaperBidFunctionWinsTheAuctionAcrossTwoRuntimes() {
        Pricey pricey = new Pricey();
        bind(a, pricey);
        bind(b, new Cheap());
        Space auction = space(a, "auction");
        auction.write(new Lot("lot-1"), HOUR);

        assertThat(Fleets.await(WAIT, () -> !auction.readAll(Template.of(Award.class), 10).isEmpty())).isTrue();
        assertThat(auction.readAll(Template.of(Award.class), 10)).containsExactly(new Award("lot-1", "cheap"));
        assertThat(pricey.bids.get()).as("the losing bid function priced the lot").isPositive();
    }

    // ------------------------------------------------ @Propose, Motion, @CapabilityRef, @Ballot, @OnDecision

    public record Cue(String caseId, String text) {
    }

    public record Ask(String caseId, String text) {
    }

    public record Verdict(String proposalId, String winner) {
    }

    @AgentSpec(name = "proposer", description = "Opens a vote per cue", goals = {"open"})
    public static class Proposer {
        @Propose(space = "cues", vote = "votes", prefix = "case:", key = "caseId",
                options = {"approve", "deny"}, quorum = 1)
        public String open(Cue cue) {
            return cue.text();
        }
    }

    @AgentSpec(name = "mover", description = "Opens a vote by motion", goals = {"open"})
    public static class Mover {
        @SpaceNotify(space = "cues")
        public Motion move(Ask ask) {
            return Motion.in("votes", "case:" + ask.caseId(), ask.text(), List.of("approve", "deny"), 1, HOUR);
        }
    }

    @AgentSpec(name = "panelist", description = "Approves everything", goals = {"vote"})
    public static class Panelist {
        @Ballot(space = "votes", prefix = "case:")
        public String judge(VoteCapability.Proposal proposal) {
            return "approve";
        }
    }

    @AgentSpec(name = "recorder", description = "Records decisions", goals = {"record"})
    public static class Recorder {
        @CapabilityRef
        VoteClient votes;

        @OnDecision(space = "votes", prefix = "case:")
        public Verdict record(VoteCapability.Decision decision) {
            return new Verdict(decision.proposalId(), decision.winner());
        }
    }

    @Test
    @Timeout(90)
    void proposalsOpenedThreeWaysAreCastOnTheOtherRuntimeAndDecidedHere() {
        Recorder recorder = new Recorder();
        bind(a, new Proposer(), new Mover(), recorder);
        bind(b, new Panelist());
        assertThat(recorder.votes).as("@CapabilityRef injected the VoteClient").isNotNull();

        Space cues = space(a, "cues");
        cues.write(new Cue("c1", "ship?"), HOUR);
        cues.write(new Ask("a1", "merge?"), HOUR);
        recorder.votes.propose("case:k1", "client?", List.of("approve", "deny"), 1, HOUR);

        Space votes = space(a, "votes");
        assertThat(Fleets.await(WAIT, () -> votes.readAll(Template.of(Verdict.class), 10).size() == 3)).isTrue();
        assertThat(votes.readAll(Template.of(VoteCapability.Proposal.class), 10)).extracting(VoteCapability.Proposal::proposalId)
                .containsExactlyInAnyOrder("case:c1", "case:a1", "case:k1");
        assertThat(votes.readAll(Template.of(Verdict.class), 10))
                .containsExactlyInAnyOrder(new Verdict("case:c1", "approve"), new Verdict("case:a1", "approve"),
                        new Verdict("case:k1", "approve"));
    }

    // ------------------------------------------------ Contribution and @OnEstimate

    public record Reading(String topic, double value) {
    }

    public record Report(String epoch, double value, int ticks) {
    }

    @AgentSpec(name = "sensor", description = "Contributes readings", goals = {"sense"})
    public static class Sensor {
        @SpaceNotify(space = "readings")
        public Contribution feel(Reading reading) {
            return Contribution.to(reading.topic(), reading.value());
        }
    }

    @AgentSpec(name = "supervisor", description = "Reports settled estimates", goals = {"report"})
    public static class Supervisor {
        @OnEstimate(prefix = "backlog", settleTicks = 3, tolerance = 0.01, resultSpace = "readings")
        public Report report(PushSumAggregate.Estimate estimate) {
            return new Report(estimate.epochId(), estimate.value(), estimate.ticks());
        }
    }

    @Test
    @Timeout(90)
    void aContributionJoinsTheEpochAndOnEstimateReportsWhenItSettles() {
        bind(a, new Sensor());
        b.context().group().capability(AggregateClient.class).start("backlog", 20.0);
        Space readings = space(a, "readings");
        readings.write(new Reading("backlog", 40.0), HOUR);
        AggregateClient aggregate = a.context().group().capability(AggregateClient.class);
        assertThat(Fleets.await(WAIT, () -> aggregate.estimate("backlog").isPresent())).as("the Contribution joined").isTrue();
        bind(a, new Supervisor());

        assertThat(Fleets.await(WAIT, () -> !readings.readAll(Template.of(Report.class), 10).isEmpty())).isTrue();
        Report report = readings.readAll(Template.of(Report.class), 10).get(0);
        assertThat(report.epoch()).isEqualTo("backlog");
        assertThat(report.value()).isCloseTo(30.0, within(1.0));
        assertThat(report.ticks()).isGreaterThanOrEqualTo(3);
    }

    // ------------------------------------------------ @SpaceJoin LOCAL

    public record Fraud(String claimId, int score) {
    }

    public record Coverage(String claimId, boolean covered) {
    }

    public record Note(String claimId, String text) {
    }

    public record Assessment(String claimId, int score, boolean covered, String note) {
    }

    @AgentSpec(name = "assembler", description = "Joins the findings per claim", goals = {"assemble"})
    public static class Assembler {
        @SpaceJoin(space = "intake", key = "claimId", mode = SpaceJoin.Mode.LOCAL,
                parts = {@Part(Fraud.class), @Part(Coverage.class), @Part(value = Note.class, optional = true)})
        public Assessment assemble(Joined joined) {
            return new Assessment(joined.key(), joined.get(Fraud.class).score(), joined.get(Coverage.class).covered(),
                    joined.find(Note.class).map(Note::text).orElse(""));
        }
    }

    @Test
    @Timeout(60)
    void aLocalJoinFiresPerKeyWithAnOptionalPartWhenPresent() {
        bind(a, new Assembler());
        Space intake = space(a, "intake");
        intake.write(new Fraud("c1", 10), HOUR);
        intake.write(new Coverage("c1", true), HOUR);
        intake.write(new Note("c2", "late shipment"), HOUR);
        intake.write(new Fraud("c2", 7), HOUR);
        intake.write(new Coverage("c2", false), HOUR);

        assertThat(Fleets.await(WAIT, () -> intake.readAll(Template.of(Assessment.class), 10).size() == 2)).isTrue();
        assertThat(intake.readAll(Template.of(Assessment.class), 10)).containsExactlyInAnyOrder(
                new Assessment("c1", 10, true, ""), new Assessment("c2", 7, false, "late shipment"));
    }

    // ------------------------------------------------ @SpaceReduce LEASED

    public record Payment(String account, long cents) {
    }

    public record Balance(String account, long cents, int payments) {
    }

    @AgentSpec(name = "teller", description = "Keeps a running balance per account", goals = {"balance"})
    public static class Teller {
        @SpaceReduce(space = "ledger", key = "account", mode = SpaceReduce.Mode.LEASED, lease = "2s", pollTimeout = "100ms")
        public Balance apply(Balance balance, Payment payment) {
            long cents = (balance == null ? 0 : balance.cents()) + payment.cents();
            int count = (balance == null ? 0 : balance.payments()) + 1;
            return new Balance(payment.account(), cents, count);
        }
    }

    @Test
    @Timeout(60)
    void aLeasedReduceFoldsTwoElementsIntoOneAccumulator() {
        bind(a, new Teller());
        Space ledger = space(a, "ledger");
        ledger.write(new Payment("acct", 5), HOUR);
        ledger.write(new Payment("acct", 7), HOUR);

        assertThat(Fleets.await(WAIT, () -> Reductions.current(ledger, Balance.class, "teller.apply", "acct")
                .map(e -> e.value().payments() == 2).orElse(false))).isTrue();
        assertThat(Reductions.current(ledger, Balance.class, "teller.apply", "acct").orElseThrow().value())
                .isEqualTo(new Balance("acct", 12, 2));
        assertThat(Fleets.await(WAIT, () -> ledger.readAll(Template.of(Payment.class), 10).isEmpty()))
                .as("both elements were consumed").isTrue();
    }
}
