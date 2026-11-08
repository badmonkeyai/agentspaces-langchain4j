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

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.api.space.Space;
import ai.badmonkey.agentspaces.api.spi.Authorizer;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.pdt.Pdt;
import ai.badmonkey.agentspaces.spring.Pdts;

import java.util.Objects;
import java.util.Optional;

/**
 * The one group a set of features works in, with the facade, the identity,
 * and the profile's authorizer beside it. {@code FleetRuntime.context(name)}
 * makes one per joined group.
 */
public class FleetContext {

    private final AgentSpaces spaces;
    private final String groupName;
    private final PeerIdentity identity;
    private final Authorizer authorizer;
    private final Pdts pdts;

    public FleetContext(AgentSpaces spaces, String groupName, PeerIdentity identity,
                        Authorizer authorizer) {
        this(spaces, groupName, identity, authorizer, new Pdts());
    }

    /**
     * A context that also hands out the group's persistent-tree facade (srad/agentspaces-pdt.md
     * PD-C-07, PD-C-09).
     *
     * @param pdts the holder the runtime's wiring registered each group's facade with
     */
    public FleetContext(AgentSpaces spaces, String groupName, PeerIdentity identity,
                        Authorizer authorizer, Pdts pdts) {
        this.spaces = Objects.requireNonNull(spaces, "spaces");
        this.groupName = Objects.requireNonNull(groupName, "groupName");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.pdts = Objects.requireNonNull(pdts, "pdts");
        if (!spaces.groupNames().contains(groupName)) {
            throw new IllegalStateException("agentspaces-runtime works in group '" + groupName
                    + "', which this node has not joined; joined groups: " + spaces.groupNames()
                    + ". Pass that group name to FleetRuntime.context(name).");
        }
    }

    public AgentSpaces spaces() {
        return spaces;
    }

    public AgentSpaces.GroupContext group() {
        return spaces.group(groupName);
    }

    public String groupName() {
        return groupName;
    }

    public PeerIdentity identity() {
        return identity;
    }

    public PeerId peerId() {
        return identity.peerId();
    }

    public Authorizer authorizer() {
        return authorizer;
    }

    /**
     * The group's content swarm, present when {@code agentspaces.content.enabled} is true.
     *
     * @return the swarm, or empty
     */
    public Optional<ai.badmonkey.agentspaces.content.Content> content() {
        return group().provider(ai.badmonkey.agentspaces.content.Content.TYPE)
                .filter(ai.badmonkey.agentspaces.content.Content.class::isInstance)
                .map(ai.badmonkey.agentspaces.content.Content.class::cast);
    }

    /**
     * The group's persistent-tree facade, present when {@code agentspaces.pdt.enabled} is true
     * (srad/agentspaces-pdt.md PD-C-02).
     *
     * @return the facade, or empty
     */
    public Optional<Pdt> pdt() {
        return pdts.find(groupName);
    }

    /**
     * The group's persistent-tree facade, or a failure naming the property that turns it on.
     *
     * @return the facade
     * @throws IllegalStateException when the group has none
     */
    public Pdt requiredPdt() {
        return pdt().orElseThrow(() -> new IllegalStateException("the persistent trees are not enabled in group '"
                + groupName + "' (agentspaces.pdt.enabled=true, with agentspaces.content.enabled=true)"));
    }

    /**
     * The named space of the group, or a startup failure naming the property
     * that asked for it and the YAML that would register it.
     */
    public Space requiredSpace(String spaceName, String property) {
        AgentSpaces.GroupContext group = group();
        if (!group.spaceNames().contains(spaceName)) {
            throw new IllegalStateException("agentspaces-runtime needs the space '" + spaceName
                    + "' (" + property + ") in group '" + groupName + "'. Add it under the group:\n"
                    + "  agentspaces:\n    groups:\n      - name: " + groupName + "\n        spaces:\n"
                    + "          - name: " + spaceName);
        }
        return group.space(spaceName);
    }
}
