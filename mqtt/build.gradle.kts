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

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testCompileClasspath.get()
    runtimeClasspath += output + sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    resources.srcDir(rootProject.file("gradle/test-resources"))
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(libs.pulsar.io.common)
    implementation(libs.pulsar.io.core)
    implementation(libs.pulsar.functions.instance)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.commons.lang3)
    implementation(libs.guava)
    implementation(libs.hivemq.mqtt.client)

    add(integrationTest.implementationConfigurationName, libs.testcontainers)
}

tasks.register<Test>("integrationTest") {
    description = "Runs MQTT integration tests that require Docker."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter("test")
}
