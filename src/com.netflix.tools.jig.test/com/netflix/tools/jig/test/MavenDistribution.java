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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;

import com.netflix.tools.jig.module.ModuleRepositorySession;

/** A Maven/JDK parameter with a shared, checksum-verified test distribution. */
record MavenDistribution(String version, int javaVersion) {
    Path javaHome() {
        return Path.of(System.getenv("JIG_TEST_MAVEN_JAVA_" + javaVersion + "_HOME")).toAbsolutePath().normalize();
    }

    Path executable() throws Exception {
        String configured = System.getenv("JIG_TEST_MAVEN_DISTRIBUTIONS");
        Path cache = configured == null
                ? ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv()).resolve("test-distributions/maven")
                : Path.of(configured);
        cache = cache.toAbsolutePath().normalize();
        Path installation = cache.resolve("apache-maven-" + version);
        if (!Files.isRegularFile(installation.resolve("bin/mvn"))) {
            install(cache, installation, version);
        }
        return installation.resolve("bin/mvn");
    }

    private static synchronized void install(Path cache, Path installation, String version) throws Exception {
        if (Files.isRegularFile(installation.resolve("bin/mvn"))) {
            return;
        }
        Files.createDirectories(cache);
        Path staging = Files.createTempDirectory(cache, "download-");
        try {
            String url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/" + version + "/apache-maven-" + version + "-bin.zip";
            // Older distributions predate Maven Central's SHA-512 checksums.
            String suffix = List.of("3.0.3", "3.0.5", "3.1.1", "3.2.5", "3.3.9", "3.6.3").contains(version) ? "sha1" : "sha512";
            Path archive = staging.resolve("distribution.zip");
            try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
                var download = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofFile(archive));
                if (download.statusCode() != 200) {
                    throw new IOException("Maven download failed: HTTP " + download.statusCode() + " for " + url);
                }
                var checksum = client.send(HttpRequest.newBuilder(URI.create(url + "." + suffix)).GET().build(), BodyHandlers.ofString());
                if (checksum.statusCode() != 200) {
                    throw new IOException("Maven checksum download failed: HTTP " + checksum.statusCode());
                }
                var digest = MessageDigest.getInstance(suffix.equals("sha1") ? "SHA-1" : "SHA-512");
                try (var input = Files.newInputStream(archive)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, count);
                    }
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(checksum.body().strip())) {
                    throw new IOException("Maven distribution checksum mismatch: " + version);
                }
            }
            try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging)) {
                        throw new IOException("Invalid Maven archive entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target);
                    }
                }
            }
            Files.move(staging.resolve("apache-maven-" + version), installation, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try (var files = Files.walk(staging)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    @Override
    public String toString() {
        return version + " / JDK " + javaVersion;
    }
}
