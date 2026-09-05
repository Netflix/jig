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

package com.netflix.tools.jig.test.module;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes.Name;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.module.ModuleOrigin;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import com.netflix.tools.jig.module.maven.transport.ModuleProxyTransporter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleProbeRepositoryFilterTest {

    @TempDir
    Path tempDir;

    @Test
    void successfulPartialProbeDoesNotReachLaterRepository() throws Exception {
        byte[] jar = automaticModuleJar("com.example.library");
        try (var first = TestServer.serving(jar);
             var second = TestServer.missing();
             var context = TestRepositorySystem.create(
                     tempDir.resolve("local"),
                     List.of(first.repository("first"), second.repository("second")),
                     Map.of());
             var transporter = new ModuleProxyTransporter(context.system(), context.session(), context.session(),
                     context.repositories())) {
            assertEquals("com.example.library", transporter.lookupModuleName(new DefaultArtifact("com.example", "library", "jar", "1.0")));
            assertTrue(first.requests() > 0);
            assertEquals(0, second.requests());
        }
    }

    @Test
    void packageLookupProbesWithoutDownloadingTheArtifact() throws Exception {
        byte[] jar = automaticModuleJar("com.example.library");
        try (var repository = TestServer.serving(jar);
             var session = ModuleRepositorySession.create(tempDir.resolve("lookup-local"), List.of(repository.repository("lookup")))) {
            String repositoryUrl = URLEncoder.encode(repository.repository("lookup")
                    .getUrl(),
                    StandardCharsets.UTF_8);
            var origin = ModuleOrigin.parse("pkg:maven/com.example/library@1.0?repository_url=" + repositoryUrl);

            assertEquals("com.example.library", session.lookupModule(origin));
            assertEquals(0, repository.fullRequests());
        }
    }

    @Test
    void explicitAliasOverridesModuleNameEstablishedByALaterVersion() throws Exception {
        String base = "/net/sf/jopt-simple/jopt-simple/";
        byte[]
                metadata = ("""
                <metadata>
                  <groupId>net.sf.jopt-simple</groupId>
                  <artifactId>jopt-simple</artifactId>
                  <versioning><versions>
                    <version>5.0.4</version>
                    <version>6.0-alpha-3</version>
                  </versions></versioning>
                </metadata>
                """)
                                .getBytes(StandardCharsets.UTF_8);
        try (var repository = TestServer.serving(
                     Map.of(base + "maven-metadata.xml", metadata, base + "5.0.4/jopt-simple-5.0.4.jar",
                             osgiModuleJar("net.sf.jopt-simple.jopt-simple"), base + "6.0-alpha-3/jopt-simple-6.0-alpha-3.jar", automaticModuleJar("joptsimple")));
             var context = TestRepositorySystem.create(tempDir.resolve("alias-local"), List.of(repository.repository("alias")), Map.of());
             var transporter = new ModuleProxyTransporter(context.system(), context.session(), context.session(),
                     context.repositories())) {
            assertEquals("jopt.simple", transporter.lookupModuleName(new DefaultArtifact("net.sf.jopt-simple", "jopt-simple", "jar", "5.0.4")));
        }
    }

    @Test
    void missingArtifactFallsThroughToLaterRepository() throws Exception {
        byte[] jar = automaticModuleJar("com.example.library");
        try (var first = TestServer.missing();
             var second = TestServer.serving(jar);
             var context = TestRepositorySystem.create(
                     tempDir.resolve("fallback-local"),
                     List.of(first.repository("first"), second.repository("second")),
                     Map.of());
             var transporter = new ModuleProxyTransporter(context.system(), context.session(), context.session(),
                     context.repositories())) {
            assertEquals("com.example.library", transporter.lookupModuleName(new DefaultArtifact("com.example", "library", "jar", "1.0")));
            assertEquals(1, first.requests());
            assertTrue(second.requests() > 0);
        }
    }

    private static byte[] automaticModuleJar(String moduleName) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        return jar(manifest);
    }

    private static byte[] osgiModuleJar(String moduleName) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", moduleName);
        return jar(manifest);
    }

    private static byte[] jar(Manifest manifest) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var _ = new JarOutputStream(bytes, manifest)) {}
        return bytes.toByteArray();
    }

    private static final class TestServer implements AutoCloseable {
        private static final String DEFAULT_ARTIFACT_PATH = "/com/example/library/1.0/library-1.0.jar";

        private final HttpServer server;
        private final Map<String, byte[]> resources;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger fullRequests = new AtomicInteger();

        static TestServer serving(byte[] content) throws IOException {
            return serving(DEFAULT_ARTIFACT_PATH, content);
        }

        static TestServer serving(String artifactPath, byte[] content) throws IOException {
            return serving(Map.of(artifactPath, content));
        }

        static TestServer serving(Map<String, byte[]> resources) throws IOException {
            return new TestServer(resources);
        }

        static TestServer missing() throws IOException {
            return new TestServer(Map.of());
        }

        private TestServer(Map<String, byte[]> resources) throws IOException {
            this.resources = Map.copyOf(resources);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            try (exchange) {
                byte[] content = resources.get(exchange.getRequestURI()
                        .getPath());
                if (content == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range == null) {
                    fullRequests.incrementAndGet();
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                    return;
                }
                if (!range.startsWith("bytes=")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                String requested = range.substring("bytes=".length());
                int start;
                int end;
                if (requested.startsWith("-")) {
                    start = Math.max(0, content.length - Integer.parseInt(requested.substring(1)));
                    end = content.length - 1;
                } else {
                    String[] bounds = requested.split("-", 2);
                    start = Integer.parseInt(bounds[0]);
                    end = Math.min(content.length - 1, Integer.parseInt(bounds[1]));
                }
                int length = end - start + 1;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + content.length);
                exchange.sendResponseHeaders(206, length);
                exchange.getResponseBody().write(content, start, length);
            }
        }

        RemoteRepository repository(String id) {
            return new Builder(id, "default", "http://127.0.0.1:" + server.getAddress().getPort() + "/")
                    .build();
        }

        int requests() {
            return requests.get();
        }

        int fullRequests() {
            return fullRequests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
