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

// Distribution module — no Java compilation needed
tasks.named("compileJava") { enabled = false }
tasks.named("compileTestJava") { enabled = false }
tasks.named("jar") { enabled = false }

val pulsarVersion = project.version.toString()

// Resolvable configuration for connector NAR artifacts.
// The NAR plugin registers .nar as an outgoing variant on runtimeElements,
// so Gradle resolves and builds them automatically.
val connectorNars by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "nar")
    }
}

dependencies {
    connectorNars(project(":pulsar-io:pulsar-io-cassandra"))
    connectorNars(project(":pulsar-io:pulsar-io-kafka"))
    connectorNars(project(":pulsar-io:pulsar-io-http"))
    connectorNars(project(":pulsar-io:pulsar-io-kinesis"))
    connectorNars(project(":pulsar-io:pulsar-io-rabbitmq"))
    connectorNars(project(":pulsar-io:pulsar-io-nsq"))
    connectorNars(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-sqlite"))
    connectorNars(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-mariadb"))
    connectorNars(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-clickhouse"))
    connectorNars(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-postgres"))
    connectorNars(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-openmldb"))
    connectorNars(project(":pulsar-io:pulsar-io-data-generator"))
    connectorNars(project(":pulsar-io:pulsar-io-batch-data-generator"))
    connectorNars(project(":pulsar-io:pulsar-io-aerospike"))
    connectorNars(project(":pulsar-io:pulsar-io-elastic-search"))
    connectorNars(project(":pulsar-io:pulsar-io-kafka-connect-adaptor-nar"))
    connectorNars(project(":pulsar-io:pulsar-io-hbase"))
    connectorNars(project(":pulsar-io:pulsar-io-hdfs3"))
    connectorNars(project(":pulsar-io:pulsar-io-file"))
    connectorNars(project(":pulsar-io:pulsar-io-canal"))
    connectorNars(project(":pulsar-io:pulsar-io-netty"))
    connectorNars(project(":pulsar-io:pulsar-io-mongo"))
    connectorNars(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-mysql"))
    connectorNars(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-postgres"))
    connectorNars(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-oracle"))
    connectorNars(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-mssql"))
    connectorNars(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-mongodb"))
    connectorNars(project(":pulsar-io:pulsar-io-influxdb"))
    connectorNars(project(":pulsar-io:pulsar-io-redis"))
    connectorNars(project(":pulsar-io:pulsar-io-solr"))
    connectorNars(project(":pulsar-io:pulsar-io-dynamodb"))
    connectorNars(project(":pulsar-io:pulsar-io-alluxio"))
    connectorNars(project(":pulsar-io:pulsar-io-azure-data-explorer"))
}

val ioDistDir by tasks.registering(Sync::class) {
    destinationDir = layout.buildDirectory.dir("apache-pulsar-io-connectors-${pulsarVersion}-bin").get().asFile

    from(rootProject.projectDir.resolve("LICENSE")) {
        into(".")
    }
    from("src/assemble/README") {
        into(".")
    }

    // NAR artifacts resolved automatically via the connectorNars configuration
    from(connectorNars) {
        into(".")
    }
}

tasks.named("assemble") {
    dependsOn(ioDistDir)
}
