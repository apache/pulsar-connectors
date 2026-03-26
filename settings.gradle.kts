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

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        maven {
            url = uri("https://packages.confluent.io/maven/")
            content {
                includeGroupByRegex("io\\.confluent(\\..*)?")
            }
        }
        // ASF snapshots for Pulsar SNAPSHOT dependencies
        maven {
            url = uri("https://repository.apache.org/content/repositories/snapshots/")
            content {
                includeGroup("org.apache.pulsar")
            }
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}

rootProject.name = "pulsar-connectors"

// Enforced platform for dependency version management
include("pulsar-dependencies")

// Simple connectors (flat layout, top-level directories)
include("aerospike")
include("alluxio")
include("aws")
include("azure-data-explorer")
include("canal")
include("cassandra")
include("dynamodb")
include("elastic-search")
include("file")
include("hbase")
include("hdfs3")
include("http")
include("influxdb")
include("kafka")
include("kafka-connect-adaptor")
include("kafka-connect-adaptor-nar")
include("kinesis")
include("kinesis-kpl-shaded")
include("mongo")
include("netty")
include("nsq")
include("rabbitmq")
include("redis")
include("solr")

// JDBC — parent + sub-modules with qualified names to avoid clashes with debezium
include("jdbc")
include("jdbc:pulsar-io-jdbc-core")
project(":jdbc:pulsar-io-jdbc-core").projectDir = file("jdbc/core")
include("jdbc:pulsar-io-jdbc-clickhouse")
project(":jdbc:pulsar-io-jdbc-clickhouse").projectDir = file("jdbc/clickhouse")
include("jdbc:pulsar-io-jdbc-mariadb")
project(":jdbc:pulsar-io-jdbc-mariadb").projectDir = file("jdbc/mariadb")
include("jdbc:pulsar-io-jdbc-openmldb")
project(":jdbc:pulsar-io-jdbc-openmldb").projectDir = file("jdbc/openmldb")
include("jdbc:pulsar-io-jdbc-postgres")
project(":jdbc:pulsar-io-jdbc-postgres").projectDir = file("jdbc/postgres")
include("jdbc:pulsar-io-jdbc-sqlite")
project(":jdbc:pulsar-io-jdbc-sqlite").projectDir = file("jdbc/sqlite")

// Debezium — parent + sub-modules with qualified names
include("debezium")
include("debezium:pulsar-io-debezium-core")
project(":debezium:pulsar-io-debezium-core").projectDir = file("debezium/core")
include("debezium:pulsar-io-debezium-mongodb")
project(":debezium:pulsar-io-debezium-mongodb").projectDir = file("debezium/mongodb")
include("debezium:pulsar-io-debezium-mssql")
project(":debezium:pulsar-io-debezium-mssql").projectDir = file("debezium/mssql")
include("debezium:pulsar-io-debezium-mysql")
project(":debezium:pulsar-io-debezium-mysql").projectDir = file("debezium/mysql")
include("debezium:pulsar-io-debezium-oracle")
project(":debezium:pulsar-io-debezium-oracle").projectDir = file("debezium/oracle")
include("debezium:pulsar-io-debezium-postgres")
project(":debezium:pulsar-io-debezium-postgres").projectDir = file("debezium/postgres")

// Docs (connector doc generation)
include("docs")

// Distribution
include("distribution:pulsar-io-distribution")
project(":distribution:pulsar-io-distribution").projectDir = file("distribution/io")
