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

package com.netflix.tools.jig.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.tools.jig.MavenCentralPortal;
import com.netflix.tools.jig.MavenCentralPortal.Approval;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCentralPortalTest {
    @Test
    void publishesBundleAndWaitsForCompletion(@TempDir Path directory) throws Exception {
        Path bundle = Files.writeString(directory.resolve("bundle.zip"), "bundle");
        var authorization = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var statusRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/upload",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    query.set(exchange.getRequestURI().getQuery());
                    body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                    byte[] response = "deployment-id".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/status",
                exchange -> {
                    statusRequests.incrementAndGet();
                    assertEquals("id=deployment-id", exchange.getRequestURI().getQuery());
                    byte[] response = "{\"deploymentState\":\"PUBLISHED\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            URI endpoint = endpoint(server);
            var portal = MavenCentralPortal.fromEnvironment(Map.of(MavenCentralPortal.USERNAME, "token-user", MavenCentralPortal.PASSWORD, "token-password"),
                    HttpClient.newHttpClient(), endpoint);

            String deployment = portal.publish(bundle, "Example release", Approval.AUTOMATIC);

            assertEquals("deployment-id", deployment);
            assertEquals(
                    "Bearer " + Base64.getEncoder().encodeToString("token-user:token-password".getBytes(StandardCharsets.UTF_8)),
                    authorization.get());
            assertEquals("publishingType=AUTOMATIC&name=Example+release", query.get());
            assertTrue(body.get().contains("name=\"bundle\""),
                    body.get());
            assertTrue(body.get().contains("bundle"),
                    body.get());
            assertEquals(1, statusRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopsAfterValidationForManualApproval(@TempDir Path directory) throws Exception {
        Path bundle = Files.writeString(directory.resolve("bundle.zip"), "bundle");
        var query = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/upload",
                exchange -> {
                    query.set(exchange.getRequestURI().getQuery());
                    byte[] response = "deployment-id".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/status",
                exchange -> {
                    byte[] response = "{\"deploymentState\":\"VALIDATED\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            URI endpoint = endpoint(server);
            var portal = MavenCentralPortal.fromEnvironment(Map.of(MavenCentralPortal.USERNAME, "token-user", MavenCentralPortal.PASSWORD, "token-password"),
                    HttpClient.newHttpClient(), endpoint);

            String deployment = portal.publish(bundle, null, Approval.MANUAL);

            assertEquals("deployment-id", deployment);
            assertEquals("publishingType=USER_MANAGED", query.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsCentralValidationFailures(@TempDir Path directory) throws Exception {
        Path bundle = Files.writeString(directory.resolve("bundle.zip"), "bundle");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/upload",
                exchange -> {
                    byte[] response = "deployment-id".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/status",
                exchange -> {
                    byte[] response = "{\"deploymentState\":\"FAILED\",\"errors\":[\"namespace is not owned\"]}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            URI endpoint = endpoint(server);
            var portal = MavenCentralPortal.fromEnvironment(Map.of(MavenCentralPortal.USERNAME, "token-user", MavenCentralPortal.PASSWORD, "token-password"),
                    HttpClient.newHttpClient(), endpoint);

            var exception = assertThrows(IOException.class, () -> portal.publish(bundle, "Example release", Approval.AUTOMATIC));

            assertTrue(exception.getMessage().contains("namespace is not owned"),
                    exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesMavenSettingsCredentialsWhenEnvironmentIsEmpty(@TempDir Path directory) throws Exception {
        var configured = new PasswordAuthentication("settings-user", "settings-password".toCharArray());

        assertEquals(authorization("settings-user", "settings-password"), uploadAuthorization(directory, Map.of(), configured));
    }

    @Test
    void environmentCredentialsOverrideMavenSettings(@TempDir Path directory) throws Exception {
        var configured = new PasswordAuthentication("settings-user", "settings-password".toCharArray());
        Map<String, String> environment = Map.of(MavenCentralPortal.USERNAME, "environment-user", MavenCentralPortal.PASSWORD, "environment-password");

        assertEquals(authorization("environment-user", "environment-password"), uploadAuthorization(directory, environment, configured));
    }

    @Test
    void rejectsIncompleteEnvironmentOverride() {
        var configured = new PasswordAuthentication("settings-user", "settings-password".toCharArray());

        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralPortal.fromCredentials(Map.of(MavenCentralPortal.USERNAME, "environment-user"), configured));

        assertEquals("MAVEN_CENTRAL_PASSWORD is not set", exception.getMessage());
    }

    @Test
    void requiresPortalCredentials() {
        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralPortal.fromCredentials(Map.of(), null));

        assertEquals(
                "Maven Central credentials are not configured in Maven settings server central or the environment",
                exception.getMessage());
    }

    private static String uploadAuthorization(Path directory, Map<String, String> environment, PasswordAuthentication configured) throws Exception {
        Path bundle = Files.writeString(directory.resolve("bundle.zip"), "bundle");
        var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/upload",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] response = "deployment-id".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            URI endpoint = endpoint(server);
            var portal = MavenCentralPortal.fromCredentials(environment, configured, HttpClient.newHttpClient(), endpoint);
            portal.upload(bundle, null, Approval.AUTOMATIC);
            return authorization.get();
        } finally {
            server.stop(0);
        }
    }

    private static URI endpoint(HttpServer server) throws URISyntaxException {
        return new URI(
                "http",
                null,
                server.getAddress().getHostString(),
                server.getAddress().getPort(),
                "/upload",
                null,
                null);
    }

    private static String authorization(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Bearer " + token;
    }
}
