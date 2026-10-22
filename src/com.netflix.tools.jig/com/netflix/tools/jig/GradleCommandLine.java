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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.netflix.tools.jig.CommandLine.Completion;
import com.netflix.tools.jig.CommandLine.CompletionRequest;
import com.netflix.tools.jig.CommandLine.ParsedArguments;
import com.netflix.tools.jig.CommandLine.ToolOption;
import com.netflix.tools.jig.Jig.Options.ModuleForm;

/** Explicit build locations, project paths, and source-set argument projections for Gradle. */
final class GradleCommandLine {
    static final List<String> CLASSPATHS = List.of("compile", "runtime");
    private static final ToolOption ROOT = ToolOption.option("--root-project-dir", "DIRECTORY", "Set the Gradle root project directory (required)");
    private static final ToolOption PROJECT = ToolOption.option("--project-path", "PATH", "Select a project, such as :app or :build-logic:plugin");
    private static final ToolOption LIST_PROJECTS = ToolOption.flag("--list-project-paths", "List Gradle project paths, including included builds");
    private static final ToolOption LIST_SOURCE_SETS = ToolOption.flag("--list-source-sets", "List source sets; requires --project-path");
    private static final ToolOption SOURCE_SET = ToolOption.option("--source-set", "NAME", "Select a source set (default: main)");
    private static final ToolOption CLASSPATH = ToolOption.option("--classpath", "compile|runtime", "Select the source set's classpath (default: compile)");
    private static final ToolOption RESOLVE = ToolOption.option("--resolve-options", "OPTION[,OPTION...]", "Resolve Java tool options; requires --project-path", "-r");
    private static final ToolOption COMPILER = ToolOption.flag("--resolve-compiler-options", "Resolve compiler options; requires --project-path.\n"
            + "Uses compile; excludes source filenames and launcher options.");
    private static final ToolOption WRITE = ToolOption.option("--write-argfile", "PATH", "Write resolved options to a Java argument file instead of stdout", "-w");
    private static final ToolOption REFRESH = ToolOption.flag("--refresh", "Query Gradle again; cached answers skip Gradle and build tasks");
    private static final ToolOption VERBOSE = ToolOption.flag("--verbose", "Show cache hits, Gradle invocations and build diagnostics");
    private static final ToolOption HELP = ToolOption.flag("--help", "Print this help message", "-h");
    private static final CommandLine COMMAND_LINE = CommandLine.builder()
            .description("Resolve standard Java arguments from a Gradle source set")
            .options(ROOT, PROJECT, LIST_PROJECTS, ProjectListing.FORMAT, LIST_SOURCE_SETS, SOURCE_SET, CLASSPATH, RESOLVE, COMPILER, WRITE,
                    REFRESH, VERBOSE, HELP)
            .build();

    record Request(
            Path root,
            String projectPath,
            String sourceSet,
            String classpath,
            boolean listProjects,
            boolean tabSeparated,
            boolean listSourceSets,
            boolean compiler,
            Set<String> options,
            ModuleForm moduleForm,
            Path argumentFile,
            boolean verbose,
            boolean refresh) {}

    private GradleCommandLine() {}

    static List<Completion> complete(CompletionRequest request) {
        return COMMAND_LINE.complete(request);
    }

    static String help() {
        return """
                Usage: jig gradle --root-project-dir <DIRECTORY> [OPTIONS]

                Resolve Java tool arguments using the build's wrapper, or gradle on PATH.
                Choose --list-project-paths, --list-source-sets, --resolve-options or
                --resolve-compiler-options.

                """ + CommandLine.optionsReference(ROOT, PROJECT, SOURCE_SET, CLASSPATH,
                        LIST_PROJECTS, LIST_SOURCE_SETS, ProjectListing.FORMAT, RESOLVE, COMPILER,
                        WRITE, REFRESH, VERBOSE, HELP);
    }

    static Request parse(String[] arguments) {
        ParsedArguments parsed;
        try {
            parsed = COMMAND_LINE.parse(arguments);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            throw new IllegalArgumentException(Character.toLowerCase(message.charAt(0)) + message.substring(1), e);
        }
        String root = single(parsed, ROOT);
        String project = single(parsed, PROJECT);
        String sourceSet = single(parsed, SOURCE_SET);
        String classpath = single(parsed, CLASSPATH);
        String resolve = single(parsed, RESOLVE);
        String write = single(parsed, WRITE);
        boolean listProjects = parsed.contains(LIST_PROJECTS);
        boolean tabSeparated = ProjectListing.tabSeparated(single(parsed, ProjectListing.FORMAT), listProjects);
        boolean listSourceSets = parsed.contains(LIST_SOURCE_SETS);
        if (root == null) {
            throw new IllegalArgumentException("--root-project-dir is required");
        }
        boolean compiler = parsed.contains(COMPILER);
        boolean resolution = resolve != null || compiler;
        if (write != null && !resolution) {
            throw new IllegalArgumentException("--write-argfile requires --resolve-options or --resolve-compiler-options");
        }
        int operations = (listProjects ? 1 : 0) + (listSourceSets ? 1 : 0) + (resolve != null ? 1 : 0) + (compiler ? 1 : 0);
        if (operations > 1) {
            throw new IllegalArgumentException("--list-project-paths, --list-source-sets, --resolve-options and --resolve-compiler-options are mutually exclusive");
        }
        if (operations == 0) {
            throw new IllegalArgumentException("--list-project-paths, --list-source-sets, --resolve-options or --resolve-compiler-options is required");
        }
        if (listProjects && project != null) {
            throw new IllegalArgumentException("--project-path applies only to source-set discovery or argument resolution");
        }
        if (!resolution && (sourceSet != null || classpath != null)) {
            throw new IllegalArgumentException("--source-set and --classpath apply only to argument resolution");
        }
        if (!listProjects && project == null) {
            throw new IllegalArgumentException("--project-path is required");
        }
        if (project != null && (!project.startsWith(":") || project.contains("::") || (project.length() > 1 && project.endsWith(":")))) {
            throw new IllegalArgumentException("--project-path must be an absolute Gradle project path, such as : or :app");
        }
        if (resolution && sourceSet == null) {
            sourceSet = "main";
        }
        if (sourceSet != null && sourceSet.isBlank()) {
            throw new IllegalArgumentException("--source-set must not be empty");
        }
        if (resolution && classpath == null) {
            classpath = "compile";
        }
        if (compiler && !classpath.equals("compile")) {
            throw new IllegalArgumentException("--resolve-compiler-options implies the compile classpath");
        }
        if (classpath != null && !CLASSPATHS.contains(classpath)) {
            throw new IllegalArgumentException("--classpath must be compile or runtime");
        }
        var selection = resolve == null ? ResolveOptions.NONE : ResolveOptions.parse(resolve);
        return new Request(
                directory(root),
                project,
                sourceSet,
                classpath,
                listProjects,
                tabSeparated,
                listSourceSets,
                compiler,
                selection.forProvider(ResolveOptions.Provider.GRADLE),
                selection.moduleForm(),
                write == null ? null : Path.of(write),
                parsed.contains(VERBOSE), parsed.contains(REFRESH));
    }

    private static String single(ParsedArguments parsed, ToolOption option) {
        var values = parsed.values(option);
        if (values.size() > 1) {
            throw new IllegalArgumentException(option.names().getFirst() + " may only be specified once");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private static Path directory(String value) {
        Path path = Path.of(value)
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Project directory does not exist: " + path);
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot access project directory: " + path, e);
        }
    }
}
