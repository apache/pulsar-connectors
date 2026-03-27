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
val pulsarVersion = catalog.findVersion("pulsar").get().requiredVersion
val dockerOrganization = (findProperty("docker.organization") as String?) ?: "apachepulsar"
val dockerTag = (findProperty("docker.tag") as String?) ?: "latest"
val pulsarImage = (findProperty("pulsar.image") as String?) ?: "${dockerOrganization}/pulsar:${pulsarVersion}"

// Connector projects that produce NAR files
val connectorProjects = listOf(
    ":cassandra", ":kafka", ":http", ":kinesis", ":rabbitmq", ":nsq",
    ":jdbc:pulsar-io-jdbc-sqlite", ":jdbc:pulsar-io-jdbc-mariadb",
    ":jdbc:pulsar-io-jdbc-clickhouse", ":jdbc:pulsar-io-jdbc-postgres",
    ":jdbc:pulsar-io-jdbc-openmldb",
    ":aerospike", ":elastic-search", ":kafka-connect-adaptor-nar",
    ":hbase", ":hdfs3", ":file", ":canal", ":netty", ":mongo",
    ":debezium:pulsar-io-debezium-mysql", ":debezium:pulsar-io-debezium-postgres",
    ":debezium:pulsar-io-debezium-oracle", ":debezium:pulsar-io-debezium-mssql",
    ":debezium:pulsar-io-debezium-mongodb",
    ":influxdb", ":redis", ":solr", ":dynamodb", ":alluxio",
    ":azure-data-explorer", ":aws",
)

// Prepare the build context
val prepareBuildContext by tasks.registering(Sync::class) {
    // Depend on jar tasks of all connector projects (which produce .nar files via NAR plugin)
    connectorProjects.forEach { path ->
        dependsOn("${path}:jar")
    }

    // Collect NAR files from each connector project's build/libs directory
    connectorProjects.forEach { path ->
        from(project(path).layout.buildDirectory.dir("libs")) {
            include("*.nar")
            into("connectors")
        }
    }

    // TLS certificates for integration tests
    from("${rootDir}/tests/certificate-authority") {
        into("certificate-authority")
    }
    // Test scripts and supervisor config
    from("scripts") { into("scripts") }
    from("conf") { into("conf") }
    // Dockerfile
    from("Dockerfile")

    into(layout.buildDirectory.dir("docker-context"))
}

val dockerBuild by tasks.registering(Exec::class) {
    group = "docker"
    description = "Build the pulsar-connectors-test Docker image for integration tests"

    dependsOn(prepareBuildContext)

    val imageName = "${dockerOrganization}/pulsar-connectors-test:${dockerTag}"

    workingDir = layout.buildDirectory.dir("docker-context").get().asFile

    commandLine(
        "docker", "build",
        "-t", imageName,
        "--build-arg", "PULSAR_IMAGE=${pulsarImage}",
        "."
    )
}
