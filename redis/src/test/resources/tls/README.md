# TLS Fixture Provenance

The [redis-test-truststore.jks](./redis-test-truststore.jks) file was generated with the following
steps. It is not in a script because a script likely won't work the next time this file needs to be
updated. This file was copied out of convenience.

A throwaway self-signed certificate was generated (the private key was discarded; a truststore only
needs the certificate):
```shell
openssl req -x509 -newkey rsa:2048 -keyout ca.key -out cacert.crt -days 3650 -nodes \
  -subj "/CN=redis-io-connector-test"
```

The certificate was imported into a truststore (keytool's default store type, PKCS12 on modern JDKs,
was used; no `-storetype` was specified):
```shell
keytool -importcert -alias redis-test-ca -keystore redis-test-truststore.jks \
  -storepass changeit -file cacert.crt -noprompt
```

The store password is `changeit`.

```shell
rm ca.key cacert.crt
```

## redis-integration-server.key / redis-integration-server.crt / redis-integration-truststore.jks

These three files back `RedisSinkTlsIntegrationTest`, which runs a real `redis-server` (via
Testcontainers) with TLS enabled and connects to it, rather than only exercising `SslOptions` in
isolation. Unlike `redis-test-truststore.jks` above, this fixture's private key is needed to run the
server and is kept — do not regenerate `redis-test-truststore.jks` from these steps, it is
intentionally separate and its own key is intentionally gone.

A throwaway self-signed certificate was generated, this time keeping the private key so it can be
handed to `redis-server` as `--tls-key-file`/`--tls-cert-file`:
```shell
openssl req -x509 -newkey rsa:2048 -keyout redis-integration-server.key \
  -out redis-integration-server.crt -days 3650 -nodes -subj "/CN=localhost"
```
The CN is `localhost` because that's the host Testcontainers exposes the container's mapped port
under, and `redisTlsVerifyPeer=FULL` checks the certificate's hostname against it.

The certificate was imported into a truststore, again using keytool's default store type (PKCS12 on
modern JDKs; no `-storetype` was specified):
```shell
keytool -importcert -alias redis-integration-server -keystore redis-integration-truststore.jks \
  -storepass changeit -file redis-integration-server.crt -noprompt
```

The store password is `changeit`.

`redis-integration-server.key` and `redis-integration-server.crt` must stay world-readable
(`chmod 644`): Testcontainers copies them into the container where `redis-server` runs as a
non-root user, and a non-readable key fails with `Failed to load private key: ... Permission
denied` at container startup.
