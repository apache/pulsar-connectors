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

// Docker image module — no Java compilation needed
tasks.named("compileJava") { enabled = false }
tasks.named("compileTestJava") { enabled = false }
tasks.named("jar") { enabled = false }

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val pulsarConnectorsVersion = project.version.toString()
val pulsarVersion = catalog.findVersion("pulsar").get().requiredVersion
val dockerOrganization = (findProperty("docker.organization") as String?) ?: "apachepulsar"
val dockerTag = (findProperty("docker.tag") as String?) ?: "latest"
val pulsarImage = (findProperty("pulsar.image") as String?) ?: "${dockerOrganization}/pulsar:${pulsarVersion}"

// Resolvable configuration for connector NAR artifacts
val connectorNars by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "nar")
    }
}

dependencies {
    connectorNars(project(":cassandra"))
    connectorNars(project(":kafka"))
    connectorNars(project(":http"))
    connectorNars(project(":kinesis"))
    connectorNars(project(":rabbitmq"))
    connectorNars(project(":nsq"))
    connectorNars(project(":jdbc:pulsar-io-jdbc-sqlite"))
    connectorNars(project(":jdbc:pulsar-io-jdbc-mariadb"))
    connectorNars(project(":jdbc:pulsar-io-jdbc-clickhouse"))
    connectorNars(project(":jdbc:pulsar-io-jdbc-postgres"))
    connectorNars(project(":jdbc:pulsar-io-jdbc-openmldb"))
    connectorNars(project(":aerospike"))
    connectorNars(project(":elastic-search"))
    connectorNars(project(":kafka-connect-adaptor-nar"))
    connectorNars(project(":hbase"))
    connectorNars(project(":hdfs3"))
    connectorNars(project(":file"))
    connectorNars(project(":canal"))
    connectorNars(project(":netty"))
    connectorNars(project(":mongo"))
    connectorNars(project(":debezium:pulsar-io-debezium-mysql"))
    connectorNars(project(":debezium:pulsar-io-debezium-postgres"))
    connectorNars(project(":debezium:pulsar-io-debezium-oracle"))
    connectorNars(project(":debezium:pulsar-io-debezium-mssql"))
    connectorNars(project(":debezium:pulsar-io-debezium-mongodb"))
    connectorNars(project(":influxdb"))
    connectorNars(project(":redis"))
    connectorNars(project(":solr"))
    connectorNars(project(":dynamodb"))
    connectorNars(project(":alluxio"))
    connectorNars(project(":azure-data-explorer"))
    connectorNars(project(":aws"))
}

// Prepare build context with connector NARs
val prepareBuildContext by tasks.registering(Sync::class) {
    from(connectorNars) {
        into("connectors")
    }
    into(layout.buildDirectory.dir("docker-context"))
}

// Copy Dockerfile into build context
val copyDockerfile by tasks.registering(Copy::class) {
    from("Dockerfile")
    into(layout.buildDirectory.dir("docker-context"))
}

val dockerBuild by tasks.registering(Exec::class) {
    group = "docker"
    description = "Build the pulsar-all Docker image with all connectors"

    dependsOn(prepareBuildContext, copyDockerfile)

    val imageName = "${dockerOrganization}/pulsar-all:${dockerTag}"

    workingDir = layout.buildDirectory.dir("docker-context").get().asFile

    commandLine(
        "docker", "build",
        "-t", imageName,
        "--build-arg", "PULSAR_IMAGE=${pulsarImage}",
        "."
    )
}

// Docker image is not part of the default build — invoke dockerBuild explicitly
