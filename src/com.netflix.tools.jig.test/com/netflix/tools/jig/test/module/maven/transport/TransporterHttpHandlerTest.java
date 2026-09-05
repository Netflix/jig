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

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.PeekTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.PutTask;
import com.netflix.tools.jig.module.maven.transport.ResourceTransporter;
import com.netflix.tools.jig.module.maven.transport.TransporterHttpHandler;
import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransporterHttpHandlerTest {

    private static final byte[] CONTENT = "module content".getBytes(StandardCharsets.UTF_8);

    @Test
    void getIncludesChecksumHeadersAndBody() throws Exception {
        var exchange = new TestExchange("GET", URI.create("/repo/module.jar"));
        new TransporterHttpHandler(new TestTransporter()).handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        assertArrayEquals(CONTENT, exchange.responseBytes());
        assertEquals(String.valueOf(CONTENT.length),
                exchange.getResponseHeaders().getFirst("Content-Length"));
        assertEquals("application/java-archive", exchange.getResponseHeaders()
                .getFirst("Content-Type"));
        assertEquals(checksum("SHA-512"),
                exchange.getResponseHeaders().getFirst("x-checksum-sha512"));
        assertEquals(checksum("SHA-256"),
                exchange.getResponseHeaders().getFirst("x-checksum-sha256"));
        assertEquals(checksum("SHA-1"),
                exchange.getResponseHeaders().getFirst("x-checksum-sha1"));
        assertEquals(checksum("MD5"),
                exchange.getResponseHeaders().getFirst("x-checksum-md5"));
        assertEquals("\"sha-512-" + checksum("SHA-512") + "\"",
                exchange.getResponseHeaders().getFirst("ETag"));
        String contentDigest = exchange.getResponseHeaders().getFirst("Content-Digest");
        assertTrue(contentDigest.contains("sha-512=:" + checksumBase64("SHA-512") + ":"));
        assertTrue(contentDigest.contains("sha-256=:" + checksumBase64("SHA-256") + ":"));
    }

    @Test
    void streamsResourcesWithoutTransporterBuffering() throws Exception {
        var exchange = new TestExchange("GET", URI.create("/repo/module.jar"));
        new TransporterHttpHandler(new TestTransporter()).handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        assertArrayEquals(CONTENT, exchange.responseBytes());
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        var exchange = new TestExchange("HEAD", URI.create("/repo/module.jar"));
        new TransporterHttpHandler(new TestTransporter()).handle(exchange);

        assertEquals(200, exchange.getResponseCode());
        assertEquals(0, exchange.responseBytes().length);
        assertEquals(String.valueOf(CONTENT.length),
                exchange.getResponseHeaders().getFirst("Content-Length"));
        assertEquals(checksum("SHA-256"),
                exchange.getResponseHeaders().getFirst("x-checksum-sha256"));
    }

    @Test
    void conditionalGetAndPomContentType() throws Exception {
        String etag = "\"sha-512-" + checksum("SHA-512") + "\"";
        var notModified = new TestExchange("GET", URI.create("/repo/module.jar"));
        notModified.getRequestHeaders().set("If-None-Match", etag);
        new TransporterHttpHandler(new TestTransporter()).handle(notModified);
        assertEquals(304, notModified.getResponseCode());
        assertEquals(0, notModified.responseBytes().length);

        var pom = new TestExchange("GET", URI.create("/repo/module.pom"));
        new TransporterHttpHandler(new TestTransporter()).handle(pom);
        assertEquals("application/xml", pom.getResponseHeaders()
                .getFirst("Content-Type"));
    }

    @Test
    void mapsNotFoundAndUnsupportedMethods() throws Exception {
        var missing = new TestExchange("GET", URI.create("/repo/missing.jar"));
        new TransporterHttpHandler(new TestTransporter()).handle(missing);
        assertEquals(404, missing.getResponseCode());

        var post = new TestExchange("POST", URI.create("/repo/module.jar"));
        new TransporterHttpHandler(new TestTransporter()).handle(post);
        assertEquals(405, post.getResponseCode());
        assertEquals("GET, HEAD", post.getResponseHeaders()
                .getFirst("Allow"));
    }

    private static String checksum(String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm)
                .digest(CONTENT));
    }

    private static String checksumBase64(String algorithm) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm)
                .digest(CONTENT));
    }

    private static final class TestTransporter extends AbstractTransporter implements ResourceTransporter {
        @Override
        public Resource resource(URI location) throws Exception {
            if (location.getPath().contains("missing")) {
                throw new FileNotFoundException(location.toString());
            }
            var checksums = new HashMap<String, String>();
            for (String algorithm : List.of("SHA-512", "SHA-256", "SHA-1", "MD5")) {
                checksums.put(algorithm, checksum(algorithm));
            }
            return new Resource() {
                @Override
                public long contentLength() {
                    return CONTENT.length;
                }

                @Override
                public Map<String, String> checksums() {
                    return checksums;
                }

                @Override
                public void writeTo(OutputStream output) throws IOException {
                    output.write(CONTENT);
                }
            };
        }

        @Override
        public int classify(Throwable error) {
            return error instanceof FileNotFoundException ? ERROR_NOT_FOUND : ERROR_OTHER;
        }

        @Override
        protected void implPeek(PeekTask task) {
            throw new AssertionError("resource streaming must not use peek");
        }

        @Override
        protected void implGet(GetTask task) {
            throw new AssertionError("resource streaming must not use get");
        }

        @Override
        protected void implPut(PutTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void implClose() {}
    }

    private static final class TestExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        private final TestContext context = new TestContext();
        private InputStream requestBody = InputStream.nullInputStream();
        private OutputStream responseBody = new ByteArrayOutputStream();
        private int responseCode = -1;

        private TestExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        byte[] responseBytes() {
            return ((ByteArrayOutputStream) responseBody).toByteArray();
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return context;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int code, long length) {
            responseCode = code;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(1);
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(2);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            requestBody = input;
            responseBody = output;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }

    private static final class TestContext extends HttpContext {
        private HttpHandler handler;
        private Authenticator authenticator;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<Filter> filters = new ArrayList<>();

        @Override
        public HttpHandler getHandler() {
            return handler;
        }

        @Override
        public void setHandler(HttpHandler handler) {
            this.handler = handler;
        }

        @Override
        public String getPath() {
            return "/repo/";
        }

        @Override
        public HttpServer getServer() {
            return null;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public List<Filter> getFilters() {
            return filters;
        }

        @Override
        public Authenticator setAuthenticator(Authenticator authenticator) {
            Authenticator previous = this.authenticator;
            this.authenticator = authenticator;
            return previous;
        }

        @Override
        public Authenticator getAuthenticator() {
            return authenticator;
        }
    }
}
