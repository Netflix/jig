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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import com.netflix.tools.jig.CommandLine.ParsedArguments;
import com.netflix.tools.jig.CommandLine.ToolOption;
import com.netflix.tools.jig.Jig.Options;
import com.netflix.tools.jig.Jig.Options.ModuleForm;

/** On-demand queries hosted by the explicitly selected Maven build. */
final class MavenProjectCommands {
    private static final String CAPTURE_PREFIX = "jig-maven:";
    private static final ToolOption BASE = ToolOption.option("--project-base-dir", "DIRECTORY", "Maven project base directory");
    private static final ToolOption PROJECT = ToolOption.option("--project", "SELECTOR", "Select a Maven project by groupId:artifactId or :artifactId");
    private static final ToolOption LIST = ToolOption.flag("--list-projects", "List groupId:artifactId project selectors from the selected Maven build");
    private static final ToolOption SCOPE = ToolOption.option("--scope", "compile|runtime|test", "Select the Maven project's classpath and source roots");
    private static final ToolOption RESOLVE = ToolOption.option("--resolve-options", "OPTION[,OPTION...]", "Resolve standard options to stdout", "-r");
    private static final ToolOption WRITE = ToolOption.option("--write-argfile", "PATH", "Write resolved options to a Java argument file", "-w");
    private static final Set<String> RESOLVE_OPTIONS = Set.of("class-path", "source-path", "module-path", "module-source-path", "module", "add-modules", "describe-module");
    private static final ToolOption VERBOSE = ToolOption.flag("--verbose", "Show Maven invocations and version diagnostics");
    private static final ToolOption HELP = ToolOption.flag("--help", "Print this help message", "-h");
    private static final CommandLine COMMAND_LINE = CommandLine.builder()
            .description("Resolve standard Java arguments using the Maven build runtime")
            .options(BASE, PROJECT, LIST, SCOPE, RESOLVE, WRITE, VERBOSE, HELP)
            .build();

    private MavenProjectCommands() {}

    static ToolOption[] options() {
        return COMMAND_LINE.options().toArray(ToolOption[]::new);
    }

    static String help() {
        return COMMAND_LINE.help("jig maven")
                + "\nUses mvnw in the project base directory, or mvn on PATH.\n"
                + "Discovery includes active modules and does not execute build goals.\n"
                + "Argument resolution requires --project and --scope.\n"
                + "Resolve options: class-path, source-path, module-path, module-source-path, module, add-modules, describe-module.\n"
                + "Module options use jig's module identities; module supports the standard jig forms, including module=main.\n"
                + "Binary paths prepare native compile outputs; requesting source paths leaves selected sources to the caller.\n";
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
            boolean prepare = request.options().contains("class-path") || request.options().contains("module-path");
            Path extension = MavenCaptureExtension.create(home.toRealPath(), prepare);
            var captureArguments = new ArrayList<>(launcher);
            captureArguments.addAll(List.of("--batch-mode", "--quiet", "-Dstyle.color=never", "--file", request.base().resolve("pom.xml").toString(),
                    "-Dmaven.ext.class.path=" + extension));
            if (request.project() != null) {
                captureArguments.addAll(List.of("--projects", request.project()));
            }
            if (!request.listProjects()) {
                captureArguments.addAll(List.of("-Djig.maven.project=" + request.project(), "-Djig.maven.scope=" + request.scope(),
                        "-Djig.maven.options=" + String.join(",", request.options().stream().sorted().toList())));
            }
            String phase = "validate";
            if (prepare) {
                captureArguments.add("--also-make");
                boolean testScope = request.scope().equals("test");
                phase = testScope ? "test-compile" : "compile";
            }
            captureArguments.add(phase);
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
            if (request.listProjects()) {
                list(values, "projects").stream().distinct().sorted().forEach(out::println);
            } else {
                String generated = MavenArguments.render(request.options(), request.moduleForm(), values);
                if (request.argumentFile() == null) {
                    out.print(generated);
                } else {
                    Files.writeString(request.argumentFile(), generated);
                }
            }
            return 0;
        } catch (IOException failure) {
            err.println("jig maven: " + failure.getMessage());
            return 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("jig maven: interrupted");
            return 1;
        } catch (RuntimeException failure) {
            err.println("jig maven: " + failure.getMessage());
            failure.printStackTrace(err);
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
        boolean listProjects = parsed.contains(LIST);
        String project = single(parsed, PROJECT);
        String scope = single(parsed, SCOPE);
        String resolve = single(parsed, RESOLVE);
        String write = single(parsed, WRITE);
        if (listProjects && resolve != null) {
            throw new IllegalArgumentException("--list-projects and --resolve-options are mutually exclusive");
        }
        if (!listProjects && resolve == null) {
            throw new IllegalArgumentException("--list-projects or --resolve-options is required");
        }
        if (write != null && resolve == null) {
            throw new IllegalArgumentException("--write-argfile requires --resolve-options");
        }
        if (resolve == null && scope != null) {
            throw new IllegalArgumentException("--scope applies only to argument resolution");
        }
        if (resolve != null && project == null) {
            throw new IllegalArgumentException("--project is required for argument resolution");
        }
        if (project != null && !project.matches("[A-Za-z0-9_.-]*:[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("--project requires a selector in the form groupId:artifactId or :artifactId");
        }
        if (resolve != null && scope == null) {
            throw new IllegalArgumentException("--scope is required for argument resolution");
        }
        if (scope != null && !Set.of("compile", "runtime", "test").contains(scope)) {
            throw new IllegalArgumentException("--scope must be compile, runtime or test");
        }
        var options = new LinkedHashSet<String>();
        var nativeOptions = new Options();
        if (resolve != null) {
            var nativeSpecifications = new LinkedHashSet<String>();
            for (String option : resolve.split(",", -1)) {
                option = option.trim();
                if (option.isEmpty()) {
                    throw new IllegalArgumentException("resolve options must not be empty");
                }
                if (!RESOLVE_OPTIONS.contains(option) && !option.startsWith("module=")) {
                    throw new IllegalArgumentException("unknown resolve options: " + option);
                }
                if (option.equals("class-path")) {
                    options.add(option);
                } else {
                    nativeSpecifications.add(option);
                }
            }
            if (!nativeSpecifications.isEmpty()) {
                nativeOptions.setResolveOptions(String.join(",", nativeSpecifications));
                options.addAll(nativeOptions.resolveOptions);
            }
        }
        Path base = Path.of(bases.getFirst()).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("Project base directory does not exist: " + base);
        }
        if (!Files.isRegularFile(base.resolve("pom.xml"))) {
            throw new IllegalArgumentException("Project base directory has no pom.xml: " + base);
        }
        try {
            return new Request(base.toRealPath(), project, scope, Set.copyOf(options), nativeOptions.moduleForm, write == null ? null : Path.of(write), listProjects, parsed.contains(VERBOSE));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot access project base directory: " + base, failure);
        }
    }

    private static String single(ParsedArguments parsed, ToolOption option) {
        var values = parsed.values(option);
        if (values.size() > 1) {
            throw new IllegalArgumentException(option.names().getFirst() + " may only be specified once");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    static List<String> list(Properties values, String name) throws IOException {
        String count = values.getProperty(name + ".count");
        int size;
        try {
            size = Integer.parseInt(count);
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid Maven capture count for " + name + ": " + count, failure);
        }
        if (size < 0 || size > 100_000) {
            throw new IOException("Invalid Maven capture count for " + name + ": " + count);
        }
        var projects = new ArrayList<String>();
        for (int index = 0; index < size; index++) {
            String project = values.getProperty(name + "." + index);
            if (project == null) {
                throw new IOException("Missing Maven capture entry: " + name + "." + index);
            }
            projects.add(project);
        }
        return List.copyOf(projects);
    }

    static String argumentFile(List<String> arguments) {
        var output = new StringBuilder();
        for (String argument : arguments) {
            output.append('"').append(argument.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f")).append('"').append('\n');
        }
        return output.toString();
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

    private record Request(Path base, String project, String scope, Set<String> options, ModuleForm moduleForm, Path argumentFile, boolean listProjects, boolean verbose) {}
    private record ProcessResult(int status, List<String> lines) {}
}
