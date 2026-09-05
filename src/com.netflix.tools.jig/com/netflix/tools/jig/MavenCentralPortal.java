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

package com.netflix.tools.jig;

import java.io.IOException;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Uploads deployment bundles to the Maven Central Publisher Portal. */
public final class MavenCentralPortal {
    public enum Approval {
        AUTOMATIC,
        MANUAL
    }

    public static final String USERNAME = "MAVEN_CENTRAL_USERNAME";
    public static final String PASSWORD = "MAVEN_CENTRAL_PASSWORD";

    private static final URI UPLOAD = URI.create("https://central.sonatype.com/api/v1/publisher/upload");
    private static final Pattern DEPLOYMENT_STATE = Pattern.compile("\\\"deploymentState\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private static final Duration PUBLICATION_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final HttpClient client;
    private final URI upload;
    private final URI status;
    private final String authorization;

    private MavenCentralPortal(HttpClient client, URI upload, String authorization) {
        this.client = client;
        this.upload = upload;
        this.status = upload.resolve("status");
        this.authorization = authorization;
    }

    public static MavenCentralPortal fromEnvironment(Map<String, String> environment) {
        return fromCredentials(environment, null);
    }

    public static MavenCentralPortal fromCredentials(Map<String, String> environment, PasswordAuthentication configured) {
        return fromCredentials(environment, configured, HttpClient.newHttpClient(), UPLOAD);
    }

    public static MavenCentralPortal fromEnvironment(Map<String, String> environment, HttpClient client, URI upload) {
        return fromCredentials(environment, null, client, upload);
    }

    public static MavenCentralPortal fromCredentials(Map<String, String> environment, PasswordAuthentication configured, HttpClient client,
            URI upload) {
        String username;
        String password;
        if (isSet(environment, USERNAME) || isSet(environment, PASSWORD)) {
            username = required(environment, USERNAME);
            password = required(environment, PASSWORD);
        } else if (configured != null) {
            username = configured.getUserName();
            password = new String(configured.getPassword());
        } else {
            throw new IllegalArgumentException("Maven Central credentials are not configured in Maven settings server central or the environment");
        }
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new MavenCentralPortal(client, upload, "Bearer " + token);
    }

    public String publish(Path bundle, String deploymentName, Approval approval) throws IOException {
        Objects.requireNonNull(approval);
        String deployment = upload(bundle, deploymentName, approval);
        awaitPublication(deployment, approval);
        return deployment;
    }

    public String upload(Path bundle, String deploymentName, Approval approval) throws IOException {
        Objects.requireNonNull(approval);
        Path file = bundle.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Maven Central bundle is not a file: " + file);
        }
        String boundary = "jig-" + UUID.randomUUID();
        byte[] preamble = ("--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"bundle\"; filename=\"central-bundle.zip\"\r\n" + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        URI requestUri = requestUri(deploymentName, approval);
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", authorization)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(BodyPublishers.concat(BodyPublishers.ofByteArray(preamble),
                        BodyPublishers.ofFile(file), BodyPublishers.ofByteArray(epilogue)))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Maven Central upload was interrupted", e);
        }
        if (response.statusCode() != 201) {
            String body = response.body().strip();
            if (body.length() > 1000) {
                body = body.substring(0, 1000);
            }
            throw new IOException("Maven Central upload failed with HTTP "
                    + response.statusCode()
                    + (body.isEmpty() ? "" : ": " + body));
        }
        return response.body().strip();
    }

    private void awaitPublication(String deployment, Approval approval) throws IOException {
        long deadline = System.nanoTime() + PUBLICATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String response = status(deployment);
            var matcher = DEPLOYMENT_STATE.matcher(response);
            if (!matcher.find()) {
                throw new IOException("Maven Central status response has no deploymentState: " + response);
            }
            String state = matcher.group(1);
            if (state.equals("PUBLISHED") || approval == Approval.MANUAL && state.equals("VALIDATED")) {
                return;
            }
            if (state.equals("FAILED")) {
                throw new IOException("Maven Central deployment " + deployment + " failed: " + response);
            }
            if (!state.equals("PENDING")
                    && !state.equals("VALIDATING")
                    && !state.equals("VALIDATED")
                    && !state.equals("PUBLISHING")) {
                throw new IOException("Maven Central deployment " + deployment + " has unknown state " + state);
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Maven Central publication was interrupted", e);
            }
        }
        throw new IOException("Timed out waiting for Maven Central deployment " + deployment);
    }

    private String status(String deployment) throws IOException {
        URI uri = URI.create(status + "?id=" + URLEncoder.encode(deployment, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(1))
                .header("Authorization", authorization)
                .POST(BodyPublishers.noBody())
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Maven Central status request was interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Maven Central status request failed with HTTP " + response.statusCode());
        }
        String body = response.body().strip();
        return body.length() > 4000 ? body.substring(0, 4000) : body;
    }

    private URI requestUri(String deploymentName, Approval approval) {
        String publishingType = approval == Approval.AUTOMATIC ? "AUTOMATIC" : "USER_MANAGED";
        String query = "publishingType=" + publishingType;
        if (deploymentName != null && !deploymentName.isBlank()) {
            query += "&name=" + URLEncoder.encode(deploymentName, StandardCharsets.UTF_8);
        }
        String separator = upload.getQuery() == null ? "?" : "&";
        return URI.create(upload + separator + query);
    }

    private static boolean isSet(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value != null && !value.isBlank();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is not set");
        }
        return value;
    }
}
