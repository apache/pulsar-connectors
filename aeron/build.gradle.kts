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
    implementation(libs.pulsar.io.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.commons.lang3)
    // aeron-driver is required for the embedded media driver and depends on
    // aeron-client, which brings agrona in transitively. Aeron is not provided by
    // the function runtime, so it is bundled in the NAR.
    implementation(libs.aeron.driver)
    implementation(libs.aeron.client)

    // The container test drives records into a real broker to prove the full path.
    testImplementation(libs.testcontainers.pulsar)
    testImplementation(libs.pulsar.client)
    testImplementation(libs.pulsar.client.admin)
}

tasks.withType<Test>().configureEach {
    // Aeron maps its control-and-command file into memory and spins on it. The default
    // /dev/shm in CI containers is frequently too small for the driver's buffers, so
    // tests pin the driver directory under the Gradle temp dir instead.
    systemProperty("aeron.test.dir", layout.buildDirectory.dir("aeron-test").get().asFile.absolutePath)
}
