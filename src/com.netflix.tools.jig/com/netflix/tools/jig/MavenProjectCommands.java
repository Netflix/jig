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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import com.netflix.tools.jig.CommandLine.ToolOption;

/** On-demand queries hosted by the explicitly selected Maven build. */
final class MavenProjectCommands {
    private static final String CAPTURE_PREFIX = "jig-maven:";
    private static final ToolOption BASE = ToolOption.option("--project-base-dir", "DIRECTORY", "Maven project base directory");
    private static final ToolOption LIST = ToolOption.flag("--list-project-dirs", "List project directories from the selected Maven build");
    private static final ToolOption VERBOSE = ToolOption.flag("--verbose", "Show Maven invocations and version diagnostics");
    private static final ToolOption HELP = ToolOption.flag("--help", "Print this help message", "-h");
    private static final CommandLine COMMAND_LINE = CommandLine.builder()
            .description("Discover Maven projects using their own build runtime")
            .options(BASE, LIST, VERBOSE, HELP)
            .build();

    private MavenProjectCommands() {}

    static ToolOption[] options() {
        return COMMAND_LINE.options().toArray(ToolOption[]::new);
    }

    static String help() {
        return COMMAND_LINE.help("jig maven")
                + "\nUses mvnw in the project base directory, or mvn on PATH.\n"
                + "Discovery includes active modules and does not execute build goals.\n";
    }

    static int run(PrintWriter out, PrintWriter err, String[] arguments) {
        if (Arrays.stream(arguments).anyMatch(argument -> argument.equals("--help") || argument.equals("-h"))) {
            out.print(help());
            return 0;
        }
        Request request;
        try {
            request = parse(arguments);
        } catch (IllegalArgumentException failure) {
            err.println("jig maven: " + failure.getMessage());
            return 2;
        }
        try {
            var launcher = launcher(request.base());
            var versionArguments = new ArrayList<>(launcher);
            versionArguments.addAll(List.of("--batch-mode", "-Dstyle.color=never", "--version"));
            var version = invoke(request, versionArguments, err);
            if (version.status() != 0) {
                version.lines().forEach(err::println);
                return version.status();
            }
            if (request.verbose()) {
                version.lines().forEach(err::println);
            }
            Path home = version.lines().stream().map(MavenProjectCommands::withoutColor)
                    .filter(line -> line.startsWith("Maven home:")).map(line -> Path.of(line.substring("Maven home:".length()).trim()))
                    .findFirst().orElseThrow(() -> new IOException("Maven did not report its installation directory"));
            Path extension = MavenCaptureExtension.create(home.toRealPath());
            var captureArguments = new ArrayList<>(launcher);
            captureArguments.addAll(List.of("--batch-mode", "--quiet", "-Dstyle.color=never", "--file", request.base().resolve("pom.xml").toString(),
                    "-Dmaven.ext.class.path=" + extension, "validate"));
            var capture = invoke(request, captureArguments, err);
            var values = new Properties();
            for (String line : capture.lines()) {
                if (line.startsWith(CAPTURE_PREFIX)) {
                    try {
                        values.load(new ByteArrayInputStream(Base64.getDecoder().decode(line.substring(CAPTURE_PREFIX.length()))));
                    } catch (IllegalArgumentException failure) {
                        throw new IOException("Invalid Maven capture", failure);
                    }
                } else {
                    err.println(line);
                }
            }
            if (capture.status() != 0) {
                return capture.status();
            }
            if (values.isEmpty()) {
                throw new IOException("Maven did not return a capture");
            }
            if (!"1".equals(values.getProperty("version"))) {
                throw new IOException("Unsupported Maven capture version: " + values.getProperty("version"));
            }
            projects(values).stream().distinct().sorted().forEach(out::println);
            return 0;
        } catch (IOException failure) {
            err.println("jig maven: " + failure.getMessage());
            return 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("jig maven: interrupted");
            return 1;
        }
    }

    private static Request parse(String[] arguments) {
        var parsed = COMMAND_LINE.parse(arguments);
        var bases = parsed.values(BASE);
        if (bases.isEmpty()) {
            throw new IllegalArgumentException("--project-base-dir is required");
        }
        if (bases.size() != 1) {
            throw new IllegalArgumentException("--project-base-dir may only be specified once");
        }
        if (!parsed.contains(LIST)) {
            throw new IllegalArgumentException("--list-project-dirs is required");
        }
        Path base = Path.of(bases.getFirst()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("Project base directory does not exist: " + base);
        }
        if (!Files.isRegularFile(base.resolve("pom.xml"))) {
            throw new IllegalArgumentException("Project base directory has no pom.xml: " + base);
        }
        try {
            return new Request(base.toRealPath(), parsed.contains(VERBOSE));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot access project base directory: " + base, failure);
        }
    }

    private static List<String> projects(Properties values) throws IOException {
        String count = values.getProperty("project-dirs.count");
        int size;
        try {
            size = Integer.parseInt(count);
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid Maven capture count for project-dirs: " + count, failure);
        }
        if (size < 0 || size > 100_000) {
            throw new IOException("Invalid Maven capture count for project-dirs: " + count);
        }
        var projects = new ArrayList<String>();
        for (int index = 0; index < size; index++) {
            String directory = values.getProperty("project-dirs." + index);
            if (directory == null) {
                throw new IOException("Missing Maven capture entry: project-dirs." + index);
            }
            projects.add(directory);
        }
        return List.copyOf(projects);
    }

    private static ProcessResult invoke(Request request, List<String> arguments, PrintWriter err) throws IOException, InterruptedException {
        if (request.verbose()) {
            err.println("jig maven: " + arguments);
        }
        var process = new ProcessBuilder(arguments).directory(request.base().toFile())
                .redirectInput(Redirect.INHERIT).redirectErrorStream(true).start();
        try {
            var lines = new ArrayList<String>();
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return new ProcessResult(process.waitFor(), List.copyOf(lines));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String withoutColor(String line) {
        return line.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    private static List<String> launcher(Path base) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path wrapper = base.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (windows) {
            return List.of("cmd.exe", "/d", "/c", Files.isRegularFile(wrapper) ? wrapper.toString() : "mvn.cmd");
        }
        return Files.isRegularFile(wrapper) ? List.of("sh", wrapper.toString()) : List.of("mvn");
    }

    private record Request(Path base, boolean verbose) {}
    private record ProcessResult(int status, List<String> lines) {}
}
