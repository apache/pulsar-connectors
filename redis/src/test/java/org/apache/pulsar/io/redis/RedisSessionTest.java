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
package org.apache.pulsar.io.redis;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import io.lettuce.core.RedisURI;
import java.util.List;
import org.apache.pulsar.io.redis.sink.RedisSinkConfig;
import org.testng.annotations.Test;

/**
 * RedisSession test.
 */
@SuppressWarnings("deprecation")
public class RedisSessionTest {

    @Test
    public final void legacyPasswordOnlyAuthTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisPassword("fake@123");
        config.setRedisDatabase(1);

        RedisURI uri = buildSingleUri(config);
        assertNull(uri.getUsername());
        assertEquals(new String(uri.getPassword()), "fake@123");
        assertFalse(uri.isSsl());
        assertEquals(uri.getDatabase(), 1);
    }

    @Test
    public final void aclAuthWithUserAndPasswordTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUser("fake-user");
        config.setRedisPassword("fake@123");
        config.setRedisDatabase(0);

        RedisURI uri = buildSingleUri(config);
        assertEquals(uri.getUsername(), "fake-user");
        assertEquals(new String(uri.getPassword()), "fake@123");
    }

    @Test
    public final void noAuthWhenNoCredentialsSetTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisDatabase(0);

        RedisURI uri = buildSingleUri(config);
        assertNull(uri.getUsername());
        assertNull(uri.getPassword());
    }

    @Test
    public final void tlsDisabledByDefaultTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisPassword("fake@123");

        RedisURI uri = buildSingleUri(config);
        assertFalse(uri.isSsl());
    }

    @Test
    public final void tlsEnabledWhenConfiguredTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUser("fake-user");
        config.setRedisPassword("fake@123");
        config.setRedisUseTls(true);

        RedisURI uri = buildSingleUri(config);
        assertTrue(uri.isSsl());
        assertEquals(uri.getUsername(), "fake-user");
    }

    private static RedisURI buildSingleUri(RedisSinkConfig config) {
        List<RedisURI> uris = RedisSession.redisURIs(config.getHostAndPorts(), config);
        assertEquals(uris.size(), 1);
        return uris.get(0);
    }
}
