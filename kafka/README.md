<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# Kafka connector: sink keys

`KafkaBytesSink` forwards Pulsar message keys and values as raw bytes using Kafka's
`ByteArraySerializer` for both fields.

- String keys retain their UTF-8 bytes, so existing Kafka consumers using
  `StringDeserializer` continue to work.
- Binary keys set with Pulsar's `keyBytes(byte[])` are sent as their original
  bytes, rather than the Base64 text returned by Pulsar's `getKey()`.
- An absent key remains `null`; an empty key remains an empty byte array.
- For records without an underlying Pulsar message, the optional String record
  key is encoded as UTF-8.

Keys encoded by a Pulsar `KeyValue` schema with `SEPARATED` encoding are stored in
message key metadata and are forwarded as bytes too. The sink does not convert
schemas: configure Kafka consumers with a deserializer compatible with the key's
wire encoding. For example, Pulsar `Schema.INT32` keys can be read using Kafka's
`IntegerDeserializer`. Inline `KeyValue` payloads are forwarded as payload bytes;
the sink does not split them into Kafka key/value fields.

As with value serialization, the byte sink sets its key serializer itself;
`keySerializerClass` does not override this behavior. No new configuration is
required to forward binary keys.
