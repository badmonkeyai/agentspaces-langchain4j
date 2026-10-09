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
package ai.badmonkey.agentspaces.examples.fleet;

import ai.badmonkey.agentspaces.api.ad.GroupAdvertisement;
import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.common.id.GroupId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.peering.membership.GroupMembership;
import ai.badmonkey.agentspaces.peering.node.GroupRuntime;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.peering.transport.TcpTransport;
import ai.badmonkey.agentspaces.space.replicated.ReplicatedSpace;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The research coordinator of the core's example 02, rebuilt here from the
 * core API alone so the cross-JVM test faces what the demo faces: a peer
 * assembled without this project, on plain TCP, naming its records by the
 * default rule. The records are nested in a class of this name in this
 * package on purpose: that is what gives them the wire names
 * {@code ai.badmonkey.agentspaces.examples.fleet.ResearchFleet$ResearchTask#v1}
 * and {@code ...$Finding#v1}, the names the Python and TypeScript workers
 * carry as literals and the LangChain4j worker maps through its registry.
 */
public final class ResearchFleet {

    public record ResearchTask(String topic, int priority) {
    }

    public record Finding(String topic, String summary, String worker) {
    }

    public record Peer(PeerIdentity identity, PeerNode node, GroupRuntime runtime, ReplicatedSpace space)
            implements AutoCloseable {
        @Override
        public void close() {
            space.close();
            node.close();
        }
    }

    private ResearchFleet() {
    }

    /** The demo group, derived from the founding document the workers share. */
    public static GroupAdvertisement fleetGroup() {
        GroupId groupId = GroupId.fromFounding("research-fleet-demo-v1".getBytes(StandardCharsets.UTF_8));
        return new GroupAdvertisement("aspace://" + groupId.value(), PeerIdentity.generate().peerId(), groupId,
                Instant.EPOCH, Duration.ofDays(365), "research-fleet", GroupAdvertisement.MembershipPolicy.OPEN,
                ConflictStrategyType.LEASE_RACE, GroupAdvertisement.GossipParameters.defaults());
    }

    /** One peer on TCP with the task space, ticking; the shape of example 02's {@code startPeer}. */
    public static Peer startPeer(String name, int port) throws Exception {
        PeerIdentity identity = PeerIdentity.generate();
        PeerNode node = PeerNode.builder(identity).build();
        node.listen(new TcpTransport(), "127.0.0.1:" + port);
        GroupRuntime runtime = node.joinGroup(fleetGroup(),
                new GroupMembership.Config(Duration.ofSeconds(30), Duration.ofSeconds(2), 2), List.of());
        ReplicatedSpace space = ReplicatedSpace.builder(runtime, "tasks", identity, name)
                .settleWindow(Duration.ofMillis(150))
                .build();
        node.startTicking(Duration.ofMillis(250));
        return new Peer(identity, node, runtime, space);
    }
}
