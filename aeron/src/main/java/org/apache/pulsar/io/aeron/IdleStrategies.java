/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.aeron;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * Maps the {@code idleStrategy} config value onto an Agrona {@link IdleStrategy}.
 *
 * <p>The choice trades latency against CPU. {@code busyspin} never yields the core and gives
 * the lowest latency; {@code sleeping} parks the thread and is the cheapest. {@code backoff}
 * is the default because it spins briefly then degrades to parking, which behaves acceptably
 * both on a busy stream and on an idle one.
 */
public final class IdleStrategies {

    public static final String BACKOFF = "backoff";
    public static final String SLEEPING = "sleeping";
    public static final String YIELDING = "yielding";
    public static final String BUSYSPIN = "busyspin";

    // Immutable: supportedStrategies() hands this straight out, and a caller mutating it
    // would silently change what isSupported()/create() accept.
    private static final List<String> SUPPORTED = List.of(BACKOFF, SLEEPING, YIELDING, BUSYSPIN);

    // Agrona's conventional backoff defaults: spin, then yield, then park between 1us and 1ms.
    private static final long BACKOFF_MAX_SPINS = 100L;
    private static final long BACKOFF_MAX_YIELDS = 10L;
    private static final long BACKOFF_MIN_PARK_NS = 1_000L;
    private static final long BACKOFF_MAX_PARK_NS = TimeUnit.MILLISECONDS.toNanos(1);

    private static final long SLEEP_PERIOD_MS = 1L;

    private IdleStrategies() {
    }

    /**
     * @return the strategy names accepted by {@link #create(String)}
     */
    public static List<String> supportedStrategies() {
        return SUPPORTED;
    }

    public static boolean isSupported(String name) {
        return name != null && SUPPORTED.contains(name.toLowerCase(Locale.ROOT).trim());
    }

    /**
     * Creates a new {@link IdleStrategy}. Strategies are stateful, so each polling thread
     * must be given its own instance.
     *
     * @throws IllegalArgumentException if the name is not recognised
     */
    public static IdleStrategy create(String name) {
        if (name == null) {
            throw new IllegalArgumentException("idleStrategy must not be null");
        }
        switch (name.toLowerCase(Locale.ROOT).trim()) {
            case BACKOFF:
                return new BackoffIdleStrategy(
                        BACKOFF_MAX_SPINS, BACKOFF_MAX_YIELDS, BACKOFF_MIN_PARK_NS, BACKOFF_MAX_PARK_NS);
            case SLEEPING:
                return new SleepingMillisIdleStrategy(SLEEP_PERIOD_MS);
            case YIELDING:
                return new YieldingIdleStrategy();
            case BUSYSPIN:
                return new BusySpinIdleStrategy();
            default:
                throw new IllegalArgumentException(
                        "Unknown idleStrategy '" + name + "', expected one of " + SUPPORTED);
        }
    }
}
