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

plugins {
    id("pulsar-connectors.java-conventions")
    id("pulsar-connectors.nar-conventions")
}
dependencies {
    implementation(libs.pulsar.io.common)
    implementation(libs.pulsar.io.core)
    implementation(libs.pulsar.functions.instance)
    implementation(libs.pulsar.client)
    implementation(libs.lettuce.core)
    implementation(libs.guava)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.commons.lang3)
    implementation(libs.commons.collections4)

    testImplementation(libs.embedded.redis)
    testImplementation(libs.testcontainers)
}

tasks.test {
    // Lettuce auto-detects Netty's io_uring transport and allocates a ring buffer per real
    // client connection. Sandboxes with a low memlock ulimit only have budget for about one
    // such allocation per JVM, which made RedisSinkTlsIntegrationTest's real Redis connections
    // (alongside RedisSinkTest's) flake intermittently. Falling back to epoll/NIO avoids that
    // native resource ceiling; it has no effect on production code, which never sets this
    // property.
    systemProperty("io.lettuce.core.iouring", "false")
}
