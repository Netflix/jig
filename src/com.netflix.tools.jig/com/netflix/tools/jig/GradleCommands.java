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
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import com.netflix.tools.jig.GradleCommandLine.Request;

/** Queries the explicitly selected Gradle build using its own runtime. */
final class GradleCommands {
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
        Path work = null;
        try {
            work = Files.createTempDirectory("jig-gradle-");
            Path init = work.resolve("capture.gradle");
            Path output = work.resolve("capture.properties");
            try (var resource = GradleCommands.class.getResourceAsStream("gradle-init.gradle")) {
                if (resource == null) {
                    throw new IOException("Gradle capture init script is missing");
                }
                Files.copy(resource, init);
            }
            int status = capture(request, init, output, err);
            if (status != 0) {
                return status;
            }
            var values = new Properties();
            try (var input = Files.newInputStream(output)) {
                values.load(input);
            }
            if (!"1".equals(values.getProperty("version"))) {
                throw new IOException("Unsupported Gradle capture version: " + values.getProperty("version"));
            }
            List<String> projects = GradleArguments.list(values, "projects");
            if (request.listProjects()) {
                projects.stream()
                        .distinct()
                        .sorted()
                        .forEach(out::println);
            } else {
                if (!projects.contains(request.project()
                        .toString())) {
                    throw new IllegalArgumentException("Project directory is not part of the selected Gradle build: " + request.project());
                }
                String generated = GradleArguments.render(request, values);
                if (request.argumentFile() == null) {
                    out.print(generated);
                } else {
                    Files.writeString(request.argumentFile(), generated);
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
        } finally {
            if (work != null) {
                try (var paths = Files.walk(work)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    err.println("jig gradle: cannot remove temporary capture files: " + e.getMessage());
                }
            }
        }
    }

    private static int capture(Request request, Path init, Path output,
            PrintWriter err)
            throws IOException, InterruptedException {
        var arguments = new ArrayList<>(launcher(request.root()));
        String task = "_jigCapture" + UUID.randomUUID()
                .toString()
                .replace("-", "");
        arguments.addAll(
                List.of(
                        "--project-dir",
                        request.root().toString(),
                        "--init-script",
                        init.toString(),
                        "--console",
                        "plain",
                        "--quiet",
                        "-Dorg.gradle.configuration-cache=false",
                        "-Dorg.gradle.unsafe.configuration-cache=false",
                        "-Dorg.gradle.unsafe.isolated-projects=false",
                        "-Djig.gradle.root=" + request.root(),
                        "-Djig.gradle.output=" + output,
                        "-Djig.gradle.task=" + task,
                        "-Djig.gradle.list=" + request.listProjects()));
        if (!request.listProjects()) {
            arguments.add("-Djig.gradle.project-dir=" + request.project());
            arguments.add("-Djig.gradle.scope=" + request.scope());
            arguments.add("-Djig.gradle.options=" + String.join(",",
                    request.options().stream()
                            .sorted()
                            .toList()));
        }
        arguments.add(task);
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
                    err.println(line);
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
