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

dependencies {
    implementation(project(":pulsar-io:pulsar-io-core"))
    implementation(libs.guava)
    implementation(libs.reflections)
    implementation(libs.picocli)
    implementation(project(":pulsar-io:pulsar-io-aerospike"))
    implementation(project(":pulsar-io:pulsar-io-canal"))
    implementation(project(":pulsar-io:pulsar-io-cassandra"))
    implementation(project(":pulsar-io:pulsar-io-data-generator"))
    implementation(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-mysql"))
    implementation(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-postgres"))
    implementation(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-mongodb"))
    implementation(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-mssql"))
    implementation(project(":pulsar-io:pulsar-io-debezium:pulsar-io-debezium-oracle"))
    implementation(project(":pulsar-io:pulsar-io-dynamodb"))
    implementation(project(":pulsar-io:pulsar-io-elastic-search"))
    implementation(project(":pulsar-io:pulsar-io-file"))
    implementation(project(":pulsar-io:pulsar-io-hbase"))
    implementation(project(":pulsar-io:pulsar-io-hdfs3"))
    implementation(project(":pulsar-io:pulsar-io-http"))
    implementation(project(":pulsar-io:pulsar-io-influxdb"))
    implementation(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-clickhouse"))
    implementation(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-mariadb"))
    implementation(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-openmldb"))
    implementation(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-postgres"))
    implementation(project(":pulsar-io:pulsar-io-jdbc:pulsar-io-jdbc-sqlite"))
    implementation(project(":pulsar-io:pulsar-io-kafka"))
    implementation(project(":pulsar-io:pulsar-io-kafka-connect-adaptor"))
    implementation(project(":pulsar-io:pulsar-io-kinesis"))
    implementation(project(":pulsar-io:pulsar-io-mongo"))
    implementation(project(":pulsar-io:pulsar-io-netty"))
    implementation(project(":pulsar-io:pulsar-io-nsq"))
    implementation(project(":pulsar-io:pulsar-io-rabbitmq"))
    implementation(project(":pulsar-io:pulsar-io-redis"))
    implementation(project(":pulsar-io:pulsar-io-solr"))
    implementation(project(":pulsar-io:pulsar-io-alluxio"))
    implementation(project(":pulsar-io:pulsar-io-azure-data-explorer"))
    implementation(project(":pulsar-io:pulsar-io-batch-data-generator"))
}
