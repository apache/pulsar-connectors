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
}

dependencies {
    implementation(libs.pulsar.io.core)
    implementation(libs.guava)
    implementation(libs.reflections)
    implementation(libs.picocli)
    implementation(project(":aerospike"))
    implementation(project(":canal"))
    implementation(project(":cassandra"))
    implementation(project(":debezium:pulsar-io-debezium-mysql"))
    implementation(project(":debezium:pulsar-io-debezium-postgres"))
    implementation(project(":debezium:pulsar-io-debezium-mongodb"))
    implementation(project(":debezium:pulsar-io-debezium-mssql"))
    implementation(project(":debezium:pulsar-io-debezium-oracle"))
    implementation(project(":dynamodb"))
    implementation(project(":elastic-search"))
    implementation(project(":file"))
    implementation(project(":hbase"))
    implementation(project(":hdfs3"))
    implementation(project(":http"))
    implementation(project(":influxdb"))
    implementation(project(":jdbc:pulsar-io-jdbc-clickhouse"))
    implementation(project(":jdbc:pulsar-io-jdbc-mariadb"))
    implementation(project(":jdbc:pulsar-io-jdbc-openmldb"))
    implementation(project(":jdbc:pulsar-io-jdbc-postgres"))
    implementation(project(":jdbc:pulsar-io-jdbc-sqlite"))
    implementation(project(":kafka"))
    implementation(project(":kafka-connect-adaptor"))
    implementation(project(":kinesis"))
    implementation(project(":mongo"))
    implementation(project(":netty"))
    implementation(project(":nsq"))
    implementation(project(":rabbitmq"))
    implementation(project(":redis"))
    implementation(project(":solr"))
    implementation(project(":alluxio"))
    implementation(project(":azure-data-explorer"))
}
