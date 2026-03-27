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

// Access the version catalog directly (this module is not in the subprojects block)
val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).orElseThrow()
fun ver(alias: String) = libs.findVersion(alias).orElseThrow().requiredVersion

val pulsarVersion = ver("pulsar")
val kafkaVersion = ver("kafka-client")
val debeziumVersion = ver("debezium")

dependencies {
    // Pulsar integration test infrastructure (test-jar with PulsarCluster, PulsarContainer, etc.)
    testImplementation(lib("pulsar-integration-tests")) {
        artifact {
            classifier = "tests"
            type = "jar"
        }
    }

    // Pulsar client libraries needed by tests
    testImplementation(lib("pulsar-client"))
    testImplementation(lib("pulsar-client-api"))
    testImplementation(lib("pulsar-common"))
    testImplementation(lib("pulsar-broker"))

    // Connector client libraries for sink/source testing
    testImplementation(lib("cassandra-driver")) {
        exclude(group = "io.netty", module = "netty-handler")
    }
    testImplementation(lib("kafka-clients"))
    testImplementation(lib("opensearch-rest-high-level-client"))
    testImplementation(lib("elasticsearch-java"))
    testImplementation(lib("rabbitmq-amqp-client"))
    testImplementation(lib("mongodb-driver-reactivestreams"))
    testRuntimeOnly(lib("postgresql-jdbc"))
    testImplementation(lib("aws-sdk2-kinesis"))
    testImplementation(lib("amazon-kinesis-client-v3"))
    testImplementation(lib("aws-java-sdk-core"))

    // Serialization / data
    testImplementation(lib("jackson-databind"))
    testImplementation(lib("jackson-dataformat-yaml"))
    testImplementation(lib("avro"))
    testImplementation(lib("gson"))
    testImplementation(lib("joda-time"))

    // Docker / Testcontainers
    testImplementation(lib("docker-java-core"))
    testImplementation(lib("testcontainers"))
    testImplementation(lib("testcontainers-kafka"))
    testImplementation(lib("testcontainers-mysql"))
    testImplementation(lib("testcontainers-postgresql"))
    testImplementation(lib("testcontainers-elasticsearch"))
    testImplementation(lib("testcontainers-localstack"))
    testImplementation(lib("testcontainers-mongodb"))

    // Test utilities
    testImplementation(lib("failsafe"))
    testImplementation(lib("awaitility"))
    testImplementation(lib("commons-lang3"))
    testImplementation(lib("guava"))
}

// Tests are skipped by default — only run when explicitly invoked via the integrationTest task
tasks.test {
    enabled = false
}

// Register the integration test task
val integrationTestSuiteFile = providers.gradleProperty("integrationTestSuiteFile").getOrElse("pulsar-io-sinks.xml")
val integrationTestGroups = providers.gradleProperty("testGroups").orNull
val integrationTestExcludedGroups = providers.gradleProperty("excludedTestGroups").orNull

val integrationTest by tasks.registering(Test::class) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    useTestNG {
        suites("src/test/resources/${integrationTestSuiteFile}")
        if (!integrationTestGroups.isNullOrEmpty()) {
            includeGroups(integrationTestGroups)
        }
        if (!integrationTestExcludedGroups.isNullOrEmpty()) {
            excludeGroups(integrationTestExcludedGroups)
        }
    }

    systemProperty("currentVersion", pulsarVersion)
    systemProperty("buildDirectory", layout.buildDirectory.get().asFile.absolutePath)
    systemProperty("kafka.version", kafkaVersion)
    systemProperty("debezium.version", debeziumVersion)

    jvmArgs(
        "-XX:+ExitOnOutOfMemoryError",
        "-Xmx1G",
        "-XX:MaxDirectMemorySize=1G",
    )

    maxParallelForks = 1
    forkEvery = 0

    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
