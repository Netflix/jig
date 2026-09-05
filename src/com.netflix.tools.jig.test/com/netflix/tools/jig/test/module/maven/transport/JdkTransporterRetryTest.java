/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.tools.jig.test.module.maven.transport;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.netflix.tools.jig.internal.org.eclipse.aether.ConfigurationProperties;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession.CloseableSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.supplier.RepositorySystemSupplier;
import com.netflix.tools.jig.internal.org.eclipse.aether.supplier.SessionBuilderSupplier;
import com.netflix.tools.jig.module.maven.transport.JdkTransporterFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkTransporterRetryTest {
    @Test
    void retriesTooManyRequestsUsingConfiguredBackoff(@TempDir Path localRepository) throws Exception {
        try (var server = new RetryServer(2, null);
             var session = session(localRepository, Map.of(ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT, 2, ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL, 1L));
             var transporter = new JdkTransporterFactory(_ -> Map.of()).newInstance(session, repository(server))) {
            var task = new GetTask(URI.create("artifact.jar"));

            transporter.get(task);

            assertEquals("content", task.getDataString());
            assertEquals(3, server.requests());
        }
    }

    @Test
    void honorsRetryAfterInsteadOfConfiguredBackoff(@TempDir Path localRepository) throws Exception {
        try (var server = new RetryServer(1, "0");
             var session = session(
                     localRepository,
                     Map.of(
                     ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT, 1,
                     ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL, 1_000L,
                     ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL_MAX, 10L));
             var transporter = new JdkTransporterFactory(_ -> Map.of()).newInstance(session, repository(server))) {
            var task = new GetTask(URI.create("artifact.jar"));

            transporter.get(task);

            assertEquals("content", task.getDataString());
            assertEquals(2, server.requests());
        }
    }

    @Test
    void honorsHttpDateRetryAfter(@TempDir Path localRepository) throws Exception {
        String retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)
                .minusSeconds(1));
        try (var server = new RetryServer(1, retryAfter);
             var session = session(
                     localRepository,
                     Map.of(
                     ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT, 1,
                     ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL, 1_000L,
                     ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL_MAX, 10L));
             var transporter = new JdkTransporterFactory(_ -> Map.of()).newInstance(session, repository(server))) {
            var task = new GetTask(URI.create("artifact.jar"));

            transporter.get(task);

            assertEquals("content", task.getDataString());
            assertEquals(2, server.requests());
        }
    }

    @Test
    void drainsThrottledResponseBeforeRetrying(@TempDir Path localRepository) throws Exception {
        try (var server = new RetryServer(1, null, 1_000_000);
             var session = session(localRepository, Map.of(ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT, 1, ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL, 0L));
             var transporter = new JdkTransporterFactory(_ -> Map.of()).newInstance(session, repository(server))) {
            transporter.get(new GetTask(URI.create("artifact.jar")));

            assertEquals(1, server.connections());
        }
    }

    @Test
    void limitsEachBackoffToConfiguredMaximum(@TempDir Path localRepository) throws Exception {
        try (var server = new RetryServer(Integer.MAX_VALUE, null);
             var session = session(
                     localRepository,
                     Map.of(
                     ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT, 3,
                     ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL, 4L,
                     ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL_MAX, 10L));
             var transporter = new JdkTransporterFactory(_ -> Map.of()).newInstance(session, repository(server))) {
            var task = new GetTask(URI.create("artifact.jar"));

            Exception failure = assertThrows(Exception.class, () -> transporter.get(task));

            assertTrue(failure.getMessage().contains("HTTP 429"),
                    failure.toString());
            assertEquals(3, server.requests());
        }
    }

    @Test
    void stopsWhenRetryAfterExceedsConfiguredMaximum(@TempDir Path localRepository) throws Exception {
        try (var server = new RetryServer(Integer.MAX_VALUE, "1");
             var session = session(localRepository, Map.of(ConfigurationProperties.HTTP_RETRY_HANDLER_COUNT, 3, ConfigurationProperties.HTTP_RETRY_HANDLER_INTERVAL_MAX, 10L));
             var transporter = new JdkTransporterFactory(_ -> Map.of()).newInstance(session, repository(server))) {
            var task = new GetTask(URI.create("artifact.jar"));

            Exception failure = assertThrows(Exception.class, () -> transporter.get(task));

            assertTrue(failure.getMessage().contains("HTTP 429"),
                    failure.toString());
            assertEquals(1, server.requests());
        }
    }

    private static CloseableSession session(Path localRepository, Map<String, Object> configuration) {
        var system = new RepositorySystemSupplier().get();
        return new SessionBuilderSupplier(system)
                .get()
                .withLocalRepositoryBaseDirectories(localRepository)
                .setConfigProperties(configuration)
                .build();
    }

    private static RemoteRepository repository(RetryServer server) {
        return new Builder("test", "default",
                server.uri().toString()).build();
    }

    private static final class RetryServer implements AutoCloseable {
        private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final Set<Integer> connections = ConcurrentHashMap.newKeySet();
        private final int failures;
        private final String retryAfter;
        private final int failureBodySize;

        private RetryServer(int failures, String retryAfter) throws Exception {
            this(failures, retryAfter, 0);
        }

        private RetryServer(int failures, String retryAfter, int failureBodySize) throws Exception {
            this.failures = failures;
            this.retryAfter = retryAfter;
            this.failureBodySize = failureBodySize;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) {
            try (exchange) {
                connections.add(exchange.getRemoteAddress()
                                        .getPort());
                if (requests.incrementAndGet() <= failures) {
                    if (retryAfter != null) {
                        exchange.getResponseHeaders().set("Retry-After", retryAfter);
                    }
                    exchange.sendResponseHeaders(429, failureBodySize == 0 ? -1 : failureBodySize);
                    if (failureBodySize > 0) {
                        exchange.getResponseBody().write(new byte[failureBodySize]);
                    }
                    return;
                }
                exchange.sendResponseHeaders(200, CONTENT.length);
                exchange.getResponseBody().write(CONTENT);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        private int requests() {
            return requests.get();
        }

        private int connections() {
            return connections.size();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
