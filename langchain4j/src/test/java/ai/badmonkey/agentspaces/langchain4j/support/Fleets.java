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
package ai.badmonkey.agentspaces.langchain4j.support;

import ai.badmonkey.agentspaces.runtime.FleetRuntime;
import ai.badmonkey.agentspaces.runtime.PropertiesLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Builds test runtimes the way the Spring integration test builds applications. */
public final class Fleets {

    private Fleets() {
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The it-fleet property set: one founded group, tasks, findings, and data spaces, plus extras. */
    public static Map<String, Object> fleet(int port, int seedPort, String... extraSpaces) {
        Map<String, Object> props = new HashMap<>();
        props.put("agentspaces.bind", "127.0.0.1:" + port);
        props.put("agentspaces.tick-millis", "200");
        props.put("agentspaces.groups[0].name", "it-fleet");
        props.put("agentspaces.groups[0].founding", "langchain4j-it-fleet-v1");
        String[] spaces = {"tasks", "findings", "data"};
        int i = 0;
        for (String space : spaces) {
            props.put("agentspaces.groups[0].spaces[" + i + "].name", space);
            props.put("agentspaces.groups[0].spaces[" + i + "].settle-window-millis", "100");
            i++;
        }
        for (String space : extraSpaces) {
            props.put("agentspaces.groups[0].spaces[" + i + "].name", space);
            props.put("agentspaces.groups[0].spaces[" + i + "].settle-window-millis", "100");
            i++;
        }
        if (seedPort > 0) {
            props.put("agentspaces.groups[0].seeds[0]", "127.0.0.1:" + seedPort);
        }
        return props;
    }

    public static FleetRuntime start(int port, int seedPort, String... extraSpaces) {
        return FleetRuntime.start(PropertiesLoader.flat(fleet(port, seedPort, extraSpaces)));
    }

    /** Polls until the condition holds or the timeout passes. */
    public static boolean await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
