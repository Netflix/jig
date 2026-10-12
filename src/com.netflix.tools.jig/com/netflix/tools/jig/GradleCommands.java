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
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
            var captures = new GradleCapture(request);
            int status = capture(request, initScript(), captures, err);
            if (status != 0) {
                return status;
            }
            List<String> projects = captures.projects();
            if (request.listProjects()) {
                projects.stream().distinct().sorted().forEach(out::println);
            } else {
                if (!projects.contains(request.projectPath())) {
                    throw new IllegalArgumentException("Project path is not part of the selected Gradle build: " + request.projectPath());
                }
                BuildCapture metadata = captures.metadata();
                metadata.expect("project-path", request.projectPath());
                List<String> sourceSets = metadata.list("source-sets");
                if (request.listSourceSets()) {
                    sourceSets.stream().distinct().sorted().forEach(out::println);
                } else {
                    if (!sourceSets.contains(request.sourceSet())) {
                        throw new IllegalArgumentException("Unknown source set in project " + request.projectPath() + ": " + request.sourceSet());
                    }
                    metadata.expect("source-set", request.sourceSet());
                    String generated = request.compiler() ? JavaArgumentFile.render(captures.compilerOptions())
                            : GradleArguments.render(request, GradleArguments.View.read(metadata));
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

    private static int capture(Request request, Path init, GradleCapture captures, PrintWriter err)
            throws IOException, InterruptedException {
        var arguments = new ArrayList<>(BuildProcess.launcher(request.root(), "gradle", "gradlew", "gradle.bat", "gradlew.bat"));
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
        arguments.add(":" + CAPTURE_TASK);
        if (request.verbose()) {
            err.println("jig gradle: " + arguments);
        }
        return BuildProcess.run(request.root(), arguments, line -> {
            if (line.startsWith(CAPTURE_PREFIX)) {
                captures.accept(BuildCapture.decode("Gradle", line.substring(CAPTURE_PREFIX.length())));
            } else {
                err.println(line);
                err.flush();
            }
        });
    }
}
