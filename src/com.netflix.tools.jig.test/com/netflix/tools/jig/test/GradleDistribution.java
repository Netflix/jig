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
import java.util.zip.ZipInputStream;

/** A Gradle/JDK test parameter with a shared, checksum-verified distribution fixture. */
record GradleDistribution(String version, int javaVersion) {
    boolean atLeast(int major, int minor) {
        String[] parts = version.split("\\.");
        int currentMajor = Integer.parseInt(parts[0]);
        int currentMinor = Integer.parseInt(parts[1]);
        return currentMajor > major || currentMajor == major && currentMinor >= minor;
    }

    Path javaHome() {
        return Path.of(System.getenv("JIG_TEST_GRADLE_JAVA_" + javaVersion + "_HOME")).toAbsolutePath().normalize();
    }

    Path executable() throws Exception {
        String configured = System.getenv("JIG_TEST_GRADLE_DISTRIBUTIONS");
        Path cache = (configured == null ? Path.of(System.getProperty("user.home"), ".gradle", "jig-test-distributions") : Path.of(configured))
                .toAbsolutePath().normalize();
        Path installation = cache.resolve("gradle-" + version);
        Path executable = installation.resolve("bin/gradle");
        if (Files.isRegularFile(executable)) {
            return executable;
        }
        Path wrappers = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists", "gradle-" + version + "-bin");
        if (Files.isDirectory(wrappers)) {
            try (var directories = Files.list(wrappers)) {
                var existing = directories.map(directory -> directory.resolve("gradle-" + version + "/bin/gradle"))
                        .filter(Files::isRegularFile).findFirst();
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
        }
        install(cache, installation, version);
        return executable;
    }

    private static synchronized void install(Path cache, Path installation, String version) throws Exception {
        if (Files.isRegularFile(installation.resolve("bin/gradle"))) {
            return;
        }
        Files.createDirectories(cache);
        Path staging = Files.createTempDirectory(cache, "download-");
        try {
            String url = "https://services.gradle.org/distributions/gradle-" + version + "-bin.zip";
            Path archive = staging.resolve("distribution.zip");
            try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
                var download = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofFile(archive));
                if (download.statusCode() != 200) {
                    throw new IOException("Gradle download failed: HTTP " + download.statusCode() + " for " + url);
                }
                var checksum = client.send(HttpRequest.newBuilder(URI.create(url + ".sha256")).GET().build(), BodyHandlers.ofString());
                if (checksum.statusCode() != 200) {
                    throw new IOException("Gradle checksum download failed: HTTP " + checksum.statusCode());
                }
                var digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(archive)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, count);
                    }
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(checksum.body().strip())) {
                    throw new IOException("Gradle distribution checksum mismatch: " + version);
                }
            }
            try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging)) {
                        throw new IOException("Invalid Gradle archive entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target);
                    }
                }
            }
            Files.move(staging.resolve("gradle-" + version), installation, StandardCopyOption.ATOMIC_MOVE);
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
