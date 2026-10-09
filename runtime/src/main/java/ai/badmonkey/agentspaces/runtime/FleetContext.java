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

import java.util.Objects;

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

    public FleetContext(AgentSpaces spaces, String groupName, PeerIdentity identity,
                        Authorizer authorizer) {
        this.spaces = Objects.requireNonNull(spaces, "spaces");
        this.groupName = Objects.requireNonNull(groupName, "groupName");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
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
