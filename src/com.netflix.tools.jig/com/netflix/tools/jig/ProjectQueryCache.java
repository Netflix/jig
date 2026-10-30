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
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.netflix.module.compile.internal.ContentHash;
import com.netflix.tools.jig.module.ModuleRepositorySession;

/** Stores build-tool answers, retaining distinct observed input states. */
final class ProjectQueryCache {
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Duration PRUNE_INTERVAL = Duration.ofDays(1);
    private static final String ROOT = "\u0000project-root\u0000";
    private static final Set<String> PATH_OPTIONS = Set.of("--class-path", "-classpath", "-cp", "--module-path", "-p",
            "--processor-path", "-processorpath", "--processor-module-path", "--source-path", "-sourcepath",
            "--module-source-path", "--patch-module", "--upgrade-module-path", "--system", "-d", "-s", "-h");
    private final String tool;
    private final Path root;
    private final Path directory;
    private final List<String> launcher;

    ProjectQueryCache(String tool, Path root, String request, List<String> launcher, String resource) throws IOException {
        this.tool = tool;
        this.root = root;
        this.launcher = launcher;
        String revision;
        try (var input = ProjectQueryCache.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing capture resource: " + resource);
            }
            revision = hash(input.readAllBytes());
        }
        String identity = "1\n" + tool + '\n' + request + '\n'
                + launcher.stream().map(this::relative).toList() + '\n' + System.getProperty("java.home") + '\n' + revision
                + '\n' + Jig.class.getModule().getDescriptor().rawVersion().orElse("development")
                + '\n' + System.getProperty("os.name") + '\n' + java.io.File.pathSeparator;
        Path cache = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv())
                .resolve("projects");
        directory = cache.resolve(hash(identity.getBytes(StandardCharsets.UTF_8)));
    }

    void clear() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (Path file : entries()) {
            Properties values = load(file);
            if (matches(values)) {
                Files.deleteIfExists(file);
            }
        }
    }

    List<BuildCapture> read() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        prune();
        for (Path file : entries()) {
            var values = load(file);
            if (!matches(values)) {
                continue;
            }
            var captures = new ArrayList<BuildCapture>();
            try {
                int count = count(values, "records");
                for (int index = 0; index < count; index++) {
                    var stored = BuildCapture.decode(tool, values.getProperty("record." + index, "")).properties();
                    stored.replaceAll((key, value) -> ((String) value).replace(ROOT, root.toString()));
                    captures.add(BuildCapture.of(tool, stored));
                }
                validate(captures);
            } catch (IOException | IllegalArgumentException failure) {
                continue;
            }
            try {
                Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
            } catch (NoSuchFileException failure) {
                continue;
            }
            return List.copyOf(captures);
        }
        return List.of();
    }

    void store(List<BuildCapture> captures) throws IOException {
        var inputs = new TreeSet<String>();
        for (var capture : captures) {
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
        var state = new TreeSet<String>();
        int index = 0;
        for (String input : inputs) {
            Path path = Path.of(input);
            if (!path.isAbsolute()) {
                throw new IOException("Build tool reported a relative input: " + input);
            }
            String name = relative(input);
            String fingerprint = fingerprint(path);
            values.setProperty("input." + index, name);
            values.setProperty("hash." + index++, fingerprint);
            state.add(name + '\n' + fingerprint);
        }
        values.setProperty("records", Integer.toString(captures.size()));
        for (index = 0; index < captures.size(); index++) {
            Properties stored = captures.get(index).properties();
            for (String name : stored.stringPropertyNames()) {
                if (pathField(name)) {
                    stored.setProperty(name, relative(stored.getProperty(name)));
                }
            }
            if ("argfile".equals(stored.getProperty("kind"))) {
                relocateArguments(stored);
            }
            // Arbitrary compiler/plugin values are not necessarily paths. Keep such
            // answers local rather than rewriting strings we do not understand.
            if (stored.values().stream().anyMatch(value -> ((String) value).contains(root.toString()))) {
                values.setProperty("location", root.toString());
            }
            values.setProperty("record." + index, BuildCapture.of(tool, stored).encoded());
        }
        String identity = String.join("\n", state) + '\n' + values.getProperty("location", "");
        Files.createDirectories(directory);
        prune();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".query-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                values.store(output, null);
            }
            Files.move(temporary, directory.resolve(hash(identity.getBytes(StandardCharsets.UTF_8)) + ".properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void relocateArguments(Properties values) throws IOException {
        var arguments = BuildCapture.of(tool, values).list("arguments");
        String pathOption = null;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            int equals = argument.indexOf('=');
            String option = equals < 0 ? argument : argument.substring(0, equals);
            if (pathOption != null) {
                values.setProperty("arguments." + index, relativePaths(pathOption, argument));
                pathOption = null;
            } else if (PATH_OPTIONS.contains(option)) {
                if (equals < 0) {
                    pathOption = option;
                } else {
                    values.setProperty("arguments." + index, option + "=" + relativePaths(option, argument.substring(equals + 1)));
                }
            }
        }
    }

    private String relativePaths(String option, String value) {
        int equals = option.equals("--module-source-path") || option.equals("--patch-module") ? value.indexOf('=') : -1;
        String prefix = equals < 0 ? "" : value.substring(0, equals + 1);
        String paths = equals < 0 ? value : value.substring(equals + 1);
        return prefix + String.join(java.io.File.pathSeparator,
                java.util.Arrays.stream(paths.split(Pattern.quote(java.io.File.pathSeparator), -1)).map(this::relative).toList());
    }

    private String relative(String value) {
        if (value.equals(root.toString())) {
            return ROOT;
        }
        String prefix = root + root.getFileSystem().getSeparator();
        return value.startsWith(prefix) ? ROOT + value.substring(root.toString().length()) : value;
    }

    private static boolean pathField(String name) {
        return name.matches("(?:build-files|project-directories|sources|dependency-sources|classpath|module-path|processors)\\.\\d+")
                || name.equals("output-directory") || name.equals("system");
    }

    private boolean matches(Properties values) throws IOException {
        if (values.isEmpty() || !values.getProperty("location", root.toString()).equals(root.toString())) {
            return false;
        }
        try {
            int inputs = count(values, "inputs");
            if (inputs == 0) {
                return false;
            }
            for (int index = 0; index < inputs; index++) {
                Path path = Path.of(values.getProperty("input." + index, "").replace(ROOT, root.toString()));
                if (!path.isAbsolute() || !fingerprint(path).equals(values.getProperty("hash." + index))) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private void validate(List<BuildCapture> captures) throws IOException {
        if (captures.isEmpty()) {
            throw new IOException("Empty " + tool + " cache response");
        }
        if (captures.getFirst().require("kind").equals("argfile")) {
            for (var capture : captures) {
                capture.expect("kind", "argfile");
                capture.list("arguments");
            }
        } else if (captures.getFirst().require("kind").equals("source-sets")) {
            captures.getFirst().list("source-sets");
        } else if (tool.equals("Gradle")) {
            ProjectListing.gradleContexts(captures, GradleCommandLine.CLASSPATHS);
        } else {
            ProjectListing.mavenContexts(captures, MavenProjectCommands.SCOPES);
        }
    }

    private List<Path> entries() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".properties")).sorted().toList();
        } catch (NoSuchFileException failure) {
            return List.of();
        }
    }

    private void prune() throws IOException {
        Path parent = directory.getParent();
        Path marker = parent.resolve(".pruned");
        Instant now = Instant.now();
        if (Files.exists(marker) && Files.getLastModifiedTime(marker).toInstant().isAfter(now.minus(PRUNE_INTERVAL))) {
            return;
        }
        Instant cutoff = now.minus(RETENTION);
        try (var paths = Files.walk(parent)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(parent) || path.equals(directory)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (NoSuchFileException | DirectoryNotEmptyException failure) {
                    // Another invocation can touch, publish or prune entries concurrently.
                }
            }
        }
        Files.writeString(marker, "");
    }

    private static Properties load(Path file) throws IOException {
        var values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
        } catch (NoSuchFileException | IllegalArgumentException failure) {
            return new Properties();
        }
        return values;
    }

    private static int count(Properties values, String name) {
        int count = Integer.parseInt(values.getProperty(name, "0"));
        if (count < 0 || count > 100_000) {
            throw new IllegalArgumentException("Invalid cache count: " + name);
        }
        return count;
    }

    private static String fingerprint(Path file) throws IOException {
        return Files.notExists(file) ? "missing" : hash(Files.readAllBytes(file));
    }

    private static String hash(byte[] bytes) {
        return ContentHash.sha256(bytes).hex();
    }
}
