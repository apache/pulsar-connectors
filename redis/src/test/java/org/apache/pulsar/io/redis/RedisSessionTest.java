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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import java.io.File;
import java.io.IOException;
import java.security.KeyStoreException;
import java.util.List;
import org.apache.pulsar.io.redis.sink.RedisSinkConfig;
import org.testng.annotations.Test;

/**
 * RedisSession test.
 */
@SuppressWarnings("deprecation")
public class RedisSessionTest {

    private static final String TEST_TRUSTSTORE_PASSWORD = "changeit";

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

    @Test
    public final void tlsVerifyPeerDefaultsToFullTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);

        RedisURI uri = buildSingleUri(config);
        assertEquals(uri.getVerifyMode(), SslVerifyMode.FULL);
    }

    @Test
    public final void tlsVerifyPeerNoneTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsVerifyPeer("NONE");

        RedisURI uri = buildSingleUri(config);
        assertEquals(uri.getVerifyMode(), SslVerifyMode.NONE);
    }

    @Test
    public final void tlsVerifyPeerCaTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsVerifyPeer("CA");

        RedisURI uri = buildSingleUri(config);
        assertEquals(uri.getVerifyMode(), SslVerifyMode.CA);
    }

    @Test
    public final void tlsVerifyPeerInvalidValueTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsVerifyPeer("bogus");

        expectThrows(IllegalArgumentException.class, () -> buildSingleUri(config));
    }

    @Test
    public final void tlsVerifyPeerIgnoredWhenTlsDisabledTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisTlsVerifyPeer("bogus");

        RedisURI uri = buildSingleUri(config);
        assertFalse(uri.isSsl());
    }

    @Test
    public final void buildSslOptionsDefaultWhenTruststoreNotSetTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);

        SslOptions sslOptions = RedisSession.buildSslOptions(config);
        assertNull(sslOptions.getTruststore());
    }

    @Test
    public final void buildSslOptionsWithTruststorePathOnlyTest() throws Exception {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsTrustStorePath(getFile("tls/redis-test-truststore.jks").getAbsolutePath());

        SslOptions sslOptions = RedisSession.buildSslOptions(config);
        // Lettuce 6.5.1 wires File-based truststores through an internal KeystoreAction and
        // never populates getTruststore()/getTruststorePassword(); createSslContextBuilder()
        // is the public API that actually exercises the wiring.
        assertNotNull(sslOptions.createSslContextBuilder());
    }

    @Test
    public final void buildSslOptionsWithTruststorePathAndPasswordTest() throws Exception {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsTrustStorePath(getFile("tls/redis-test-truststore.jks").getAbsolutePath());
        config.setRedisTlsTrustStorePassword(TEST_TRUSTSTORE_PASSWORD);

        SslOptions sslOptions = RedisSession.buildSslOptions(config);
        assertNotNull(sslOptions.createSslContextBuilder());

        // Negative check: a wrong password must fail to load the same file, proving the
        // password is genuinely passed through to Lettuce rather than ignored.
        RedisSinkConfig badConfig = new RedisSinkConfig();
        badConfig.setRedisHosts("localhost:6379");
        badConfig.setRedisUseTls(true);
        badConfig.setRedisTlsTrustStorePath(getFile("tls/redis-test-truststore.jks").getAbsolutePath());
        badConfig.setRedisTlsTrustStorePassword("wrong-password");
        SslOptions badSslOptions = RedisSession.buildSslOptions(badConfig);
        expectThrows(IOException.class, badSslOptions::createSslContextBuilder);
    }

    @Test
    public final void buildSslOptionsWithCorrectTrustStoreTypeTest() throws Exception {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsTrustStorePath(getFile("tls/redis-test-truststore.jks").getAbsolutePath());
        config.setRedisTlsTrustStorePassword(TEST_TRUSTSTORE_PASSWORD);
        // The fixture is actually PKCS12 content despite its .jks extension (see tls/README.md).
        config.setRedisTlsTrustStoreType("PKCS12");

        SslOptions sslOptions = RedisSession.buildSslOptions(config);
        assertNotNull(sslOptions.createSslContextBuilder());
    }

    @Test
    public final void buildSslOptionsWithUnknownTrustStoreTypeFailsTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsTrustStorePath(getFile("tls/redis-test-truststore.jks").getAbsolutePath());
        config.setRedisTlsTrustStorePassword(TEST_TRUSTSTORE_PASSWORD);
        config.setRedisTlsTrustStoreType("not-a-real-keystore-type");

        SslOptions sslOptions = RedisSession.buildSslOptions(config);
        // Negative check: an unrecognized keystore type must fail to load, proving the type is
        // genuinely passed through to Lettuce rather than ignored. KeyStore.getInstance(type)
        // throws KeyStoreException when no provider supports the given type.
        expectThrows(KeyStoreException.class, sslOptions::createSslContextBuilder);
    }

    @Test
    public final void buildSslOptionsWithNoTrustStoreTypeUsesJvmDefaultTest() throws Exception {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisUseTls(true);
        config.setRedisTlsTrustStorePath(getFile("tls/redis-test-truststore.jks").getAbsolutePath());
        config.setRedisTlsTrustStorePassword(TEST_TRUSTSTORE_PASSWORD);

        // No redisTlsTrustStoreType set: must still fall back to the JVM default and succeed,
        // matching pre-existing behavior before this field was introduced.
        SslOptions sslOptions = RedisSession.buildSslOptions(config);
        assertNotNull(sslOptions.createSslContextBuilder());
    }

    @Test
    public final void buildSslOptionsIgnoresTruststoreWhenTlsDisabledTest() {
        RedisSinkConfig config = new RedisSinkConfig();
        config.setRedisHosts("localhost:6379");
        config.setRedisTlsTrustStorePath("/tmp/fake-truststore.jks");

        SslOptions sslOptions = RedisSession.buildSslOptions(config);
        assertNull(sslOptions.getTruststore());
    }

    private static RedisURI buildSingleUri(RedisSinkConfig config) {
        List<RedisURI> uris = RedisSession.redisURIs(config.getHostAndPorts(), config);
        assertEquals(uris.size(), 1);
        return uris.get(0);
    }

    private File getFile(String name) {
        ClassLoader classLoader = getClass().getClassLoader();
        return new File(classLoader.getResource(name).getFile());
    }
}
