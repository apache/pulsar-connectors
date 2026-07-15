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
package org.apache.pulsar.io.netty;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.SourceContext;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * End-to-end tests that drive {@link NettySource} through its real entry point
 * ({@link NettySource#open}), send bytes over an actual socket for each supported
 * transport (TCP, UDP, HTTP), and assert the records the source emits.
 *
 * <p>The existing {@code NettyServerTest} only constructs {@code NettyServer} objects
 * and asserts they are non-null; nothing crosses the wire. These tests close that gap.
 *
 * <p>The source is a {@link org.apache.pulsar.io.core.PushSource}: its Netty handlers
 * call {@code consume(record)}, which the framework drains via {@link NettySource#read()}.
 * A background reader thread collects those records so assertions can run against what was
 * actually delivered. Ports are allocated from the ephemeral range so tests can run in
 * parallel, and every wait is deadline-bounded so a broken pipeline fails fast.
 */
public class NettySourceIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final long TIMEOUT_MS = 15_000L;

    private NettySource source;
    private Thread readerThread;
    private List<Record<byte[]>> collected;

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (source != null) {
            try {
                source.close();
            } catch (Exception e) {
                // Ignore teardown failures so they don't mask the test result.
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void testTcpDeliversBytesAsRecord() throws Exception {
        int port = startSource("tcp");
        awaitTcpListening(port, TIMEOUT_MS);

        byte[] payload = "hello-tcp".getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(HOST, port)) {
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
            // Assert on the concatenation of received records so a TCP read that splits the
            // payload across records still passes as long as all the bytes arrive.
            assertTrue(awaitBytes(payload.length, TIMEOUT_MS),
                    "expected " + payload.length + " bytes, got " + concat().length);
        }
        assertEquals(concat(), payload);
    }

    @Test
    public void testUdpDeliversDatagramAsRecord() throws Exception {
        int port = startSource("udp");

        byte[] payload = "hello-udp".getBytes(StandardCharsets.UTF_8);
        // UDP is connectionless with no bind acknowledgement, and early datagrams can be
        // dropped before the server is listening, so resend until a record is delivered.
        try (DatagramSocket client = new DatagramSocket()) {
            DatagramPacket packet = new DatagramPacket(
                    payload, payload.length, InetAddress.getByName(HOST), port);
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                client.send(packet);
                Thread.sleep(100);
            }
        }
        assertTrue(!collected.isEmpty(), "no datagram record was delivered");
        assertEquals(collected.get(0).getValue(), payload);
    }

    @Test
    public void testHttpDeliversRequestBodyAsRecord() throws Exception {
        int port = startSource("http");
        awaitTcpListening(port, TIMEOUT_MS);

        byte[] body = "hello-http".getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(HOST, port)) {
            String request = "POST / HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            assertTrue(awaitBytes(body.length, TIMEOUT_MS),
                    "expected HTTP body of " + body.length + " bytes, got " + concat().length);
        }
        assertEquals(concat(), body);
    }

    // ---------------------------------------------------------------- helpers

    private int startSource(String type) throws Exception {
        int port = freePort();
        Map<String, Object> config = new HashMap<>();
        config.put("type", type);
        config.put("host", HOST);
        config.put("port", port);
        config.put("numberOfThreads", 1);

        source = new NettySource();
        source.open(config, Mockito.mock(SourceContext.class));

        collected = new CopyOnWriteArrayList<>();
        readerThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Record<byte[]> record = source.read();
                    if (record != null) {
                        collected.add(record);
                    }
                }
            } catch (Exception e) {
                // read() throws InterruptedException on teardown; terminate quietly.
            }
        }, "netty-source-it-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        return port;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitTcpListening(int port, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, port), 200);
                return;
            } catch (IOException notReadyYet) {
                Thread.sleep(50);
            }
        }
        throw new AssertionError("server never started listening on port " + port);
    }

    /** Polls until at least {@code count} total bytes have been collected or the deadline passes. */
    private boolean awaitBytes(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (concat().length >= count) {
                return true;
            }
            Thread.sleep(25);
        }
        return concat().length >= count;
    }

    /** Concatenates the byte values of all collected records, in arrival order. */
    private byte[] concat() {
        int total = 0;
        for (Record<byte[]> record : collected) {
            total += record.getValue().length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (Record<byte[]> record : collected) {
            byte[] value = record.getValue();
            System.arraycopy(value, 0, out, pos, value.length);
            pos += value.length;
        }
        return out;
    }
}
