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
