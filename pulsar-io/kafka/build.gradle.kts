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
    alias(libs.plugins.nar)
}


// KafkaBytesSource uses SchemaInfoImpl from pulsar-common, which is excluded from
// NAR runtimeClasspath by the global exclusion. Bundle it via a separate configuration
// since the NAR classloader's parent (rootClassLoader) only has java-instance.jar.
val narExtraDeps by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":pulsar-io:pulsar-io-common"))
    implementation(project(":pulsar-io:pulsar-io-core"))
    implementation(project(":pulsar-common"))
    implementation(project(":pulsar-client-original"))
    narExtraDeps(project(":pulsar-common"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.guava)
    implementation(libs.kafka.clients) {
        exclude(group = "org.bitbucket.b_c", module = "jose4j")
        exclude(group = "org.lz4", module = "lz4-java")
    }
    implementation(libs.kafka.schema.registry.client)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.jjwt.impl)
    implementation(libs.jjwt.jackson)

    testImplementation(libs.hamcrest)
    testImplementation(libs.awaitility)
    testImplementation(libs.bcpkix.jdk18on)
}

tasks.named<io.github.merlimat.gradle.nar.NarTask>("nar") {
    from(narExtraDeps) { into("META-INF/bundled-dependencies") }
    bundledDependencies.from(narExtraDeps)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
