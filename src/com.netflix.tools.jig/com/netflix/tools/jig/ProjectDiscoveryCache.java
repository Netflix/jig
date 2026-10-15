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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import com.netflix.module.compile.internal.ContentHash;
import com.netflix.tools.jig.module.ModuleRepositorySession;

final class ProjectDiscoveryCache {
    private final String tool;
    private final Path file;
    private final List<String> launcher;

    ProjectDiscoveryCache(String tool, Path root, String selection, List<String> launcher, String resource) throws IOException {
        this.tool = tool;
        this.launcher = launcher;
        String revision;
        try (var input = ProjectDiscoveryCache.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing discovery resource: " + resource);
            }
            revision = ContentHash.sha256(input.readAllBytes()).hex();
        }
        String identity = "1\n" + tool + '\n' + root + '\n' + selection + '\n' + launcher + '\n'
                + System.getProperty("java.home") + '\n' + revision;
        Path directory = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv());
        file = directory.resolve("projects").resolve(ContentHash.sha256(identity.getBytes(StandardCharsets.UTF_8)).hex() + ".properties");
    }

    void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    List<BuildCapture> read() {
        // Cache files can disappear or be interrupted externally. Neither should
        // prevent asking the build tool for a fresh discovery.
        try (var input = Files.newInputStream(file)) {
            var values = new Properties();
            values.load(input);
            int inputs = Integer.parseInt(values.getProperty("inputs", "0"));
            for (int index = 0; index < inputs; index++) {
                Path path = Path.of(values.getProperty("input." + index, ""));
                if (!fingerprint(path).equals(values.getProperty("hash." + index))) {
                    return List.of();
                }
            }
            var captures = new ArrayList<BuildCapture>();
            int records = Integer.parseInt(values.getProperty("records", "0"));
            for (int index = 0; index < records; index++) {
                var capture = BuildCapture.decode(tool, values.getProperty("record." + index, ""));
                capture.expect("kind", "projects");
                captures.add(capture);
            }
            validate(captures);
            return List.copyOf(captures);
        } catch (IOException | IllegalArgumentException failure) {
            return List.of();
        }
    }

    void store(List<BuildCapture> captures) throws IOException {
        var inputs = new TreeSet<String>();
        for (var capture : captures) {
            capture.expect("kind", "projects");
            if (capture.optional("build-files.count") == null) {
                return;
            }
            inputs.addAll(capture.list("build-files"));
        }
        if (inputs.isEmpty()) {
            return;
        }
        validate(captures);
        for (String argument : launcher) {
            Path path = Path.of(argument);
            if (path.isAbsolute() && Files.isRegularFile(path)) {
                inputs.add(path.toString());
            }
        }
        var values = new Properties();
        values.setProperty("inputs", Integer.toString(inputs.size()));
        int index = 0;
        for (String input : inputs) {
            Path path = Path.of(input);
            if (!path.isAbsolute()) {
                throw new IOException("Build tool reported a relative discovery input: " + input);
            }
            values.setProperty("input." + index, input);
            values.setProperty("hash." + index++, fingerprint(path));
        }
        values.setProperty("records", Integer.toString(captures.size()));
        for (index = 0; index < captures.size(); index++) {
            values.setProperty("record." + index, captures.get(index).encoded());
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "discovery-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                values.store(output, null);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validate(List<BuildCapture> captures) throws IOException {
        if (tool.equals("Gradle")) {
            ProjectListing.gradleContexts(captures, GradleCommandLine.CLASSPATHS);
        } else {
            ProjectListing.mavenContexts(captures, MavenProjectCommands.SCOPES);
        }
    }

    private static String fingerprint(Path file) throws IOException {
        return Files.notExists(file) ? "missing" : ContentHash.sha256(Files.readAllBytes(file)).hex();
    }
}
