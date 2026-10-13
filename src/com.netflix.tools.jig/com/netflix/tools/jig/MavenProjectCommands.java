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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.netflix.tools.jig.CommandLine.ParsedArguments;
import com.netflix.tools.jig.CommandLine.ToolOption;
import com.netflix.tools.jig.Jig.Options;
import com.netflix.tools.jig.Jig.Options.ModuleForm;

/** On-demand queries hosted by the explicitly selected Maven build. */
final class MavenProjectCommands {
    static final List<String> SCOPES = List.of("compile", "runtime", "test");
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
            .options(BASE, PROJECT, LIST, ProjectListing.FORMAT, SCOPE, RESOLVE, WRITE, VERBOSE, HELP)
            .build();

    private MavenProjectCommands() {}

    static ToolOption[] options() {
        return COMMAND_LINE.options().toArray(ToolOption[]::new);
    }

    static String help() {
        return COMMAND_LINE.help("jig maven")
                + "\nUses mvnw in the project base directory, or mvn on PATH.\n"
                + "Discovery includes active modules and does not execute build goals.\n"
                + "The TSV listing contains absolute directory, project selector and scope.\n"
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
            var launcher = BuildProcess.launcher(request.base(), "mvn", "mvnw", "mvn.cmd", "mvnw.cmd");
            var versionArguments = new ArrayList<>(launcher);
            versionArguments.addAll(List.of("--batch-mode", "-Dstyle.color=never", "--version"));
            var versionLines = new ArrayList<String>();
            int versionStatus = invoke(request, versionArguments, versionLines::add, err);
            if (versionStatus != 0) {
                versionLines.forEach(err::println);
                return versionStatus;
            }
            if (request.verbose()) {
                versionLines.forEach(err::println);
            }
            Path home = versionLines.stream().map(MavenProjectCommands::withoutColor)
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
            var captures = new ArrayList<BuildCapture>();
            int status = invoke(request, captureArguments, line -> {
                if (line.startsWith(CAPTURE_PREFIX)) {
                    if (!captures.isEmpty()) {
                        throw new IOException("Duplicate Maven capture");
                    }
                    captures.add(BuildCapture.decode("Maven", line.substring(CAPTURE_PREFIX.length())));
                } else {
                    err.println(line);
                    err.flush();
                }
            }, err);
            if (status != 0) {
                return status;
            }
            if (captures.isEmpty()) {
                throw new IOException("Maven did not return a capture");
            }
            BuildCapture capture = captures.getFirst();
            capture.expect("kind", request.listProjects() ? "projects" : "arguments");
            List<String> projects = capture.list("projects");
            if (request.listProjects()) {
                if (request.tabSeparated()) {
                    out.print(ProjectListing.mavenContexts(List.of(capture), SCOPES));
                } else {
                    projects.stream().distinct().sorted().forEach(out::println);
                }
            } else {
                String selected = capture.require("project");
                if (!selected.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+") || !projects.contains(selected)
                        || !(selected.equals(request.project()) || (request.project().startsWith(":") && selected.endsWith(request.project())))) {
                    throw new IOException("Unexpected Maven capture project: " + selected);
                }
                capture.expect("scope", request.scope());
                String generated = MavenArguments.render(request.options(), request.moduleForm(), MavenArguments.View.read(capture));
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
        } catch (IllegalArgumentException failure) {
            err.println("jig maven: " + failure.getMessage());
            return 2;
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
        boolean tabSeparated = ProjectListing.tabSeparated(single(parsed, ProjectListing.FORMAT), listProjects);
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
        if (scope != null && !SCOPES.contains(scope)) {
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
            return new Request(base.toRealPath(), project, scope, Set.copyOf(options), nativeOptions.moduleForm, write == null ? null : Path.of(write), listProjects, tabSeparated, parsed.contains(VERBOSE));
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

    private static int invoke(Request request, List<String> arguments, BuildProcess.LineConsumer output, PrintWriter err)
            throws IOException, InterruptedException {
        if (request.verbose()) {
            err.println("jig maven: " + arguments);
        }
        return BuildProcess.run(request.base(), arguments, output);
    }

    private static String withoutColor(String line) {
        return line.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    private record Request(Path base, String project, String scope, Set<String> options, ModuleForm moduleForm, Path argumentFile, boolean listProjects, boolean tabSeparated, boolean verbose) {}
}
