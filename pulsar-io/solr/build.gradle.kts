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
dependencies {
    implementation(project(":pulsar-io:pulsar-io-common"))
    implementation(project(":pulsar-io:pulsar-io-core"))
    implementation(project(":pulsar-functions:pulsar-functions-instance"))
    implementation(project(":pulsar-client-original"))
    implementation(libs.solr.solrj)
    implementation(libs.guava)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.commons.lang3)
    implementation(libs.commons.collections4)

    // Solr 9.x requires Jetty 10.x — force Jetty 10 for test deps to avoid
    // conflicts with Pulsar's Jetty 12 which has incompatible class locations.
    // Use enforcedPlatform to override Gradle's default highest-version-wins resolution.
    testImplementation(enforcedPlatform("org.eclipse.jetty:jetty-bom:10.0.24"))
    testImplementation(libs.solr.test.framework)
    testImplementation(libs.solr.core)
}
