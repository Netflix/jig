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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import com.netflix.module.compile.internal.ContentHash;
import com.netflix.tools.jig.GradleCommandLine.Request;
import com.netflix.tools.jig.module.ModuleRepositorySession;

/** Queries the explicitly selected Gradle build using its own runtime. */
final class GradleCommands {
    private static final String CAPTURE_PREFIX = "jig-gradle:";
    private static final String CAPTURE_TASK = "_jigCapture";

    private GradleCommands() {}

    static int run(PrintWriter out, PrintWriter err, String[] arguments) {
        if (arguments.length == 0 || Arrays.stream(arguments).anyMatch(argument -> argument.equals("--help") || argument.equals("-h"))) {
            out.print(GradleCommandLine.help());
            return 0;
        }
        Request request;
        try {
            request = GradleCommandLine.parse(arguments);
        } catch (IllegalArgumentException e) {
            err.println("jig gradle: " + e.getMessage());
            return 2;
        }
        try {
            var values = new Properties();
            int status = capture(request, initScript(), values, err);
            if (status != 0) {
                return status;
            }
            if (values.isEmpty()) {
                throw new IOException("Gradle did not return a capture");
            }
            if (!"1".equals(values.getProperty("version"))) {
                throw new IOException("Unsupported Gradle capture version: " + values.getProperty("version"));
            }
            List<String> projects = GradleArguments.list(values, "project-paths");
            if (request.listProjects()) {
                projects.stream().distinct().sorted().forEach(out::println);
            } else {
                if (!projects.contains(request.projectPath())) {
                    throw new IllegalArgumentException("Project path is not part of the selected Gradle build: " + request.projectPath());
                }
                List<String> sourceSets = GradleArguments.list(values, "source-sets");
                if (request.listSourceSets()) {
                    sourceSets.stream().distinct().sorted().forEach(out::println);
                } else {
                    if (!sourceSets.contains(request.sourceSet())) {
                        throw new IllegalArgumentException("Unknown source set in project " + request.projectPath() + ": " + request.sourceSet());
                    }
                    String generated = GradleArguments.render(request, values);
                    if (request.argumentFile() == null) {
                        out.print(generated);
                    } else {
                        Files.writeString(request.argumentFile(), generated);
                    }
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("jig gradle: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("jig gradle: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("jig gradle: interrupted");
            return 1;
        }
    }

    private static Path initScript() throws IOException {
        byte[] script;
        try (var resource = GradleCommands.class.getResourceAsStream("gradle-init.gradle")) {
            if (resource == null) {
                throw new IOException("Gradle capture init script is missing");
            }
            script = resource.readAllBytes();
        }
        // Only the bundled script is cached. Its content-addressed path stays
        // stable for Gradle's configuration cache; requests and results are not stored.
        Path directory = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv())
                .resolve("gradle").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path init = directory.resolve(ContentHash.sha256(script).hex() + ".gradle");
        if (!Files.exists(init)) {
            Path temporary = Files.createTempFile(directory, "capture-", ".tmp");
            try {
                Files.write(temporary, script);
                Files.move(temporary, init, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return init;
    }

    private static int capture(Request request, Path init, Properties values, PrintWriter err)
            throws IOException, InterruptedException {
        var arguments = new ArrayList<>(launcher(request.root()));
        arguments.addAll(
                List.of(
                        "--project-dir",
                        request.root().toString(),
                        "--init-script",
                        init.toString(),
                        "--console",
                        "plain",
                        "--quiet",
                        "-Dorg.gradle.unsafe.isolated-projects=false",
                        "-Pjig.gradle.root=" + request.root(),
                        "-Pjig.gradle.list=" + request.listProjects(),
                        "-Pjig.gradle.list-source-sets=" + request.listSourceSets()));
        if (!request.listProjects()) {
            arguments.add("-Pjig.gradle.project-path=" + request.projectPath());
        }
        if (!request.listProjects() && !request.listSourceSets()) {
            arguments.add("-Pjig.gradle.compiler=" + request.compiler());
            arguments.add("-Pjig.gradle.source-set=" + request.sourceSet());
            arguments.add("-Pjig.gradle.classpath=" + request.classpath());
            arguments.add("-Pjig.gradle.options=" + String.join(",", request.options().stream().sorted().toList()));
        }
        String projectPath = request.listProjects() ? ":" : request.projectPath();
        arguments.add((projectPath.equals(":") ? ":" : projectPath + ":") + CAPTURE_TASK);
        if (request.verbose()) {
            err.println("jig gradle: " + arguments);
        }
        var process = new ProcessBuilder(arguments)
                .directory(request.root().toFile())
                .redirectInput(Redirect.INHERIT)
                .redirectErrorStream(true)
                .start();
        try {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(CAPTURE_PREFIX)) {
                        try {
                            values.load(new ByteArrayInputStream(Base64.getDecoder().decode(line.substring(CAPTURE_PREFIX.length()))));
                        } catch (IllegalArgumentException e) {
                            throw new IOException("Invalid Gradle capture", e);
                        }
                    } else {
                        err.println(line);
                    }
                }
            }
            return process.waitFor();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static List<String> launcher(Path root) {
        boolean windows = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows");
        Path wrapper = root.resolve(windows ? "gradlew.bat" : "gradlew");
        if (windows) {
            return List.of("cmd.exe", "/d", "/c",
                    Files.isRegularFile(wrapper) ? wrapper.toString() : "gradle.bat");
        }
        return Files.isRegularFile(wrapper) ? List.of("sh", wrapper.toString()) : List.of("gradle");
    }
}
