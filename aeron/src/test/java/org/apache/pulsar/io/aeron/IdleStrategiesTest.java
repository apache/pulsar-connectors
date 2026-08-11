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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the {@code idleStrategy} config value to Agrona {@link IdleStrategy} mapping.
 */
public class IdleStrategiesTest {

    @DataProvider(name = "strategies")
    public static Object[][] strategies() {
        return new Object[][]{
                {IdleStrategies.BACKOFF, BackoffIdleStrategy.class},
                {IdleStrategies.SLEEPING, SleepingMillisIdleStrategy.class},
                {IdleStrategies.YIELDING, YieldingIdleStrategy.class},
                {IdleStrategies.BUSYSPIN, BusySpinIdleStrategy.class},
        };
    }

    @Test(dataProvider = "strategies")
    public void testCreateMapsNameToStrategy(String name, Class<?> expected) {
        assertThat(IdleStrategies.create(name)).isInstanceOf(expected);
    }

    @Test(dataProvider = "strategies")
    public void testNamesAreCaseAndWhitespaceInsensitive(String name, Class<?> expected) {
        assertThat(IdleStrategies.create("  " + name.toUpperCase(java.util.Locale.ROOT) + " "))
                .isInstanceOf(expected);
    }

    @Test
    public void testEachCallReturnsAFreshInstance() {
        // Idle strategies carry mutable spin/yield state, so two polling threads must never
        // be handed the same object.
        IdleStrategy first = IdleStrategies.create(IdleStrategies.BACKOFF);
        IdleStrategy second = IdleStrategies.create(IdleStrategies.BACKOFF);

        assertThat(first).isNotSameAs(second);
    }

    @Test
    public void testUnknownNameIsRejected() {
        assertThatThrownBy(() -> IdleStrategies.create("spin-forever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown idleStrategy 'spin-forever'");
    }

    @Test
    public void testNullNameIsRejected() {
        assertThatThrownBy(() -> IdleStrategies.create(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testIsSupported() {
        assertThat(IdleStrategies.isSupported(IdleStrategies.BACKOFF)).isTrue();
        assertThat(IdleStrategies.isSupported("BUSYSPIN")).isTrue();
        assertThat(IdleStrategies.isSupported("spin-forever")).isFalse();
        assertThat(IdleStrategies.isSupported(null)).isFalse();
    }

    @Test
    public void testSupportedStrategiesListsAllFour() {
        assertThat(IdleStrategies.supportedStrategies())
                .containsExactlyInAnyOrder(IdleStrategies.BACKOFF, IdleStrategies.SLEEPING,
                        IdleStrategies.YIELDING, IdleStrategies.BUSYSPIN);
    }
}
