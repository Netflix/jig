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

package com.netflix.tools.jig.module.maven.transport;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.Transporter;
import com.netflix.tools.jig.module.maven.transport.ResourceTransporter.Resource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** Publishes a read-only Aether {@link Transporter} as an HTTP handler. */
public final class TransporterHttpHandler implements HttpHandler {

    private static final HexFormat HEX = HexFormat.of();
    private static final Map<String, String> CHECKSUM_HEADERS = Map.of("SHA-512", "x-checksum-sha512", "SHA-256", "x-checksum-sha256", "SHA-1", "x-checksum-sha1",
            "MD5", "x-checksum-md5");

    private final ResourceTransporter transporter;

    public TransporterHttpHandler(ResourceTransporter transporter) {
        this.transporter = Objects.requireNonNull(transporter, "transporter");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            switch (exchange.getRequestMethod()) {
                case "GET" -> handleRead(exchange, false);
                case "HEAD" -> handleRead(exchange, true);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        }
    }

    private void handleRead(HttpExchange exchange, boolean head) throws IOException {
        URI location = relativeLocation(exchange);
        Resource resource;
        try {
            resource = transporter.resource(location);
        } catch (Exception error) {
            int status = transporter.classify(error) == Transporter.ERROR_NOT_FOUND ? 404 : 500;
            if (status == 404) {
                TransportTrace.trace("resource not found %s", location);
            } else {
                TransportTrace.trace("resource failed %s: %s", location, error.getMessage());
            }
            exchange.sendResponseHeaders(status, -1);
            return;
        }

        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(exchange.getRequestURI()
                .getPath()));
        headers.set("Content-Length", String.valueOf(resource.contentLength()));
        for (var checksum : resource.checksums().entrySet()) {
            String header = CHECKSUM_HEADERS.get(checksum.getKey());
            if (header != null) {
                headers.set(header, checksum.getValue());
            }
        }
        String contentDigest = contentDigest(resource.checksums());
        if (!contentDigest.isEmpty()) {
            headers.set("Content-Digest", contentDigest);
        }
        String etag = etag(resource.checksums());
        if (etag != null) {
            headers.set("ETag", etag);
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (etag.equals(ifNoneMatch) || "*".equals(ifNoneMatch)) {
                headers.remove("Content-Length");
                exchange.sendResponseHeaders(304, -1);
                return;
            }
        }

        if (head) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, resource.contentLength());
            resource.writeTo(exchange.getResponseBody());
        }
    }

    private static URI relativeLocation(HttpExchange exchange) {
        String path = exchange.getRequestURI().getRawPath();
        String contextPath = exchange.getHttpContext().getPath();
        if (path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return URI.create(path);
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pom") || lower.endsWith(".xml")) {
            return "application/xml";
        }
        if (lower.endsWith(".jar") || lower.endsWith(".jmod")) {
            return "application/java-archive";
        }
        if (lower.endsWith(".sha512")
                || lower.endsWith(".sha256")
                || lower.endsWith(".sha1")
                || lower.endsWith(".md5")) {
            return "text/plain; charset=us-ascii";
        }
        if (lower.endsWith(".asc")) {
            return "application/pgp-signature";
        }
        return "application/octet-stream";
    }

    private static String etag(Map<String, String> checksums) {
        for (String algorithm : List.of("SHA-512", "SHA-256", "SHA-1", "MD5")) {
            String value = checksums.get(algorithm);
            if (value != null) {
                return '"' + algorithm.toLowerCase(Locale.ROOT) + '-' + value + '"';
            }
        }
        return null;
    }

    private static String contentDigest(Map<String, String> checksums) {
        var values = new ArrayList<String>();
        addContentDigest(values, "sha-512", checksums.get("SHA-512"));
        addContentDigest(values, "sha-256", checksums.get("SHA-256"));
        return String.join(", ", values);
    }

    private static void addContentDigest(List<String> values, String algorithm, String checksum) {
        if (checksum == null) {
            return;
        }
        String base64 = Base64.getEncoder().encodeToString(HEX.parseHex(checksum));
        values.add(algorithm + "=:" + base64 + ":");
    }
}
