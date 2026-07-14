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
package org.apache.pulsar.io.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.PushSource;
import org.apache.pulsar.io.core.SourceContext;
import org.apache.pulsar.io.core.annotations.Connector;
import org.apache.pulsar.io.core.annotations.IOType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple connector to consume messages from a RabbitMQ queue.
 */
@Connector(
    name = "rabbitmq",
    type = IOType.SOURCE,
    help = "A simple connector to move messages from a RabbitMQ queue to a Pulsar topic",
    configClass = RabbitMQSourceConfig.class)
public class RabbitMQSource extends PushSource<byte[]> {

    private static Logger logger = LoggerFactory.getLogger(RabbitMQSource.class);

    private Connection rabbitMQConnection;
    private Channel rabbitMQChannel;
    private RabbitMQSourceConfig rabbitMQSourceConfig;
    private String queueName;

    @Override
    public void open(Map<String, Object> config, SourceContext sourceContext) throws Exception {
        rabbitMQSourceConfig = RabbitMQSourceConfig.load(config, sourceContext);
        rabbitMQSourceConfig.validate();

        ConnectionFactory connectionFactory = rabbitMQSourceConfig.createConnectionFactory();
        rabbitMQConnection = connectionFactory.newConnection(rabbitMQSourceConfig.getConnectionName());
        logger.info("A new connection to {}:{} has been opened successfully.",
                rabbitMQConnection.getAddress().getCanonicalHostName(),
                rabbitMQConnection.getPort()
        );
        rabbitMQChannel = rabbitMQConnection.createChannel();
        AMQP.Queue.DeclareOk queueDeclaration;
        if (rabbitMQSourceConfig.isPassive()) {
            queueDeclaration = rabbitMQChannel.queueDeclarePassive(rabbitMQSourceConfig.getQueueName());
        } else {
            queueDeclaration = rabbitMQChannel.queueDeclare(rabbitMQSourceConfig.getQueueName(),
                                                        rabbitMQSourceConfig.isDurable(),
                                                        rabbitMQSourceConfig.isExclusive(),
                                                        rabbitMQSourceConfig.isAutoDelete(),
                                                        null
            );
        }
        queueName = queueDeclaration.getQueue();
        logger.info("Setting channel.basicQos({}, {}).",
                rabbitMQSourceConfig.getPrefetchCount(),
                rabbitMQSourceConfig.isPrefetchGlobal()
        );
        rabbitMQChannel.basicQos(rabbitMQSourceConfig.getPrefetchCount(), rabbitMQSourceConfig.isPrefetchGlobal());
        String exchange = rabbitMQSourceConfig.getExchangeName();
        String routingKey = rabbitMQSourceConfig.getRoutingKey();
        if (exchange != null && !exchange.isEmpty()) {
            // note: the exchange is expected to already exist; it is not declared here
            rabbitMQChannel.queueBind(queueName, exchange, routingKey);
            logger.info("Bound queue {} to exchange {} with routing key {}.", queueName, exchange, routingKey);
        }
        com.rabbitmq.client.Consumer consumer = new RabbitMQConsumer(this, rabbitMQChannel);
        rabbitMQChannel.basicConsume(queueName, consumer);
        logger.info("A consumer for queue {} has been successfully started.", queueName);
    }

    @Override
    public void close() throws Exception {
        if (rabbitMQChannel != null) {
            rabbitMQChannel.close();
        }
        if (rabbitMQConnection != null) {
            rabbitMQConnection.close();
        }
    }

    private class RabbitMQConsumer extends DefaultConsumer {
        private RabbitMQSource source;

        public RabbitMQConsumer(RabbitMQSource source, Channel channel) {
            super(channel);
            this.source = source;
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                throws IOException {
            long deliveryTag = envelope.getDeliveryTag();
            Map<String, String> pulsarProperties = new HashMap<>();
            pulsarProperties.put("__rabbitmq_consumer_tag", consumerTag);
            pulsarProperties.put("__rabbitmq_queue_name", queueName);
            source.consume(new RabbitMQRecord(Optional.ofNullable(envelope.getRoutingKey()), body, () -> {
                // acknowledge this delivery tag to RabbitMQ
                try {
                    this.getChannel().basicAck(deliveryTag, false);
                } catch (IOException e) {
                    logger.error("Error while acknowledging envelope {}.", envelope, e);
                }
            }, () -> {
                // negatively acknowledge this delivery tag to RabbitMQ
                try {
                    this.getChannel().basicNack(deliveryTag, false, true);
                } catch (IOException e) {
                    logger.error("Error while negatively acknowledging envelope {}.", envelope, e);
                }
            }, pulsarProperties));
        }
    }

    @Data
    private static class RabbitMQRecord implements Record<byte[]> {
        private final Optional<String> key;
        private final byte[] value;
        private final Runnable ackFunction;
        private final Runnable nackFunction;
        private final Map<String, String> properties;

        @Override
        public void ack() {
            ackFunction.run();
        }

        @Override
        public void fail() {
            nackFunction.run();
        }
    }
}
