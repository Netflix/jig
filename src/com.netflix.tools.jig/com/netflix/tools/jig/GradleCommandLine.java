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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.netflix.tools.jig.CommandLine.Completion;
import com.netflix.tools.jig.CommandLine.CompletionRequest;
import com.netflix.tools.jig.CommandLine.ParsedArguments;
import com.netflix.tools.jig.CommandLine.ToolOption;
import com.netflix.tools.jig.Jig.Options;
import com.netflix.tools.jig.Jig.Options.ModuleForm;

/** Explicit build locations, project paths, and source-set argument projections for Gradle. */
final class GradleCommandLine {
    private static final Set<String> EXTRA_OPTIONS = Set.of("class-path", "processor-path", "source", "target", "encoding", "system",
            "add-reads");
    private static final ToolOption ROOT = ToolOption.option("--root-project-dir", "DIRECTORY", "Gradle root project directory");
    private static final ToolOption PROJECT = ToolOption.option("--project-path", "PATH", "A build-tree-qualified Gradle project path, such as :app or :build-logic:plugin");
    private static final ToolOption LIST_PROJECTS = ToolOption.flag("--list-project-paths", "List Gradle project paths, including included builds");
    private static final ToolOption LIST_SOURCE_SETS = ToolOption.flag("--list-source-sets", "List source sets in the selected project");
    private static final ToolOption SOURCE_SET = ToolOption.option("--source-set", "NAME", "A source set from --list-source-sets");
    private static final ToolOption CLASSPATH = ToolOption.option("--classpath", "compile|runtime", "Select the source set's classpath");
    private static final ToolOption RESOLVE = ToolOption.option("--resolve-options", "OPTION[,OPTION...]", "Resolve standard options to stdout", "-r");
    private static final ToolOption COMPILER = ToolOption.flag("--resolve-compiler-options", "Resolve the selected source set's effective compiler options");
    private static final ToolOption WRITE = ToolOption.option("--write-argfile", "PATH", "Write resolved options to a Java argument file", "-w");
    private static final ToolOption VERBOSE = ToolOption.flag("--verbose", "Show the Gradle invocation and build diagnostics");
    private static final ToolOption HELP = ToolOption.flag("--help", "Print this help message", "-h");
    private static final CommandLine COMMAND_LINE = CommandLine.builder()
            .description("Resolve standard Java arguments from a Gradle source set")
            .options(ROOT, PROJECT, LIST_PROJECTS, LIST_SOURCE_SETS, SOURCE_SET, CLASSPATH, RESOLVE, COMPILER, WRITE,
                    VERBOSE, HELP)
            .build();

    record Request(
            Path root,
            String projectPath,
            String sourceSet,
            String classpath,
            boolean listProjects,
            boolean listSourceSets,
            boolean compiler,
            Set<String> options,
            ModuleForm moduleForm,
            Path argumentFile,
            boolean verbose) {}

    private GradleCommandLine() {}

    static List<Completion> complete(CompletionRequest request) {
        return COMMAND_LINE.complete(request);
    }

    static String help() {
        return COMMAND_LINE.help("jig gradle")
                + "\nUses the root project's wrapper, or gradle on PATH.\n"
                + "Project paths and source-set names are obtained from the build.\n"
                + "Included-build discovery and selection require Gradle 4.0 or later.\n"
                + "Compile and runtime select the source set's compileClasspath and runtimeClasspath.\n"
                + "Discovery does not execute source producers; source-path resolution retains their dependencies.\n"
                + "Binary paths prepare selected outputs unless the corresponding source paths are requested.\n"
                + "Unknown projects or source sets are errors; empty source sets produce only configured arguments.\n"
                + "Compiler resolution implies compile and uses Gradle's compiler argument builder.\n"
                + "It excludes source filenames and launcher options; the caller owns the invocation.\n"
                + "\nResolve options include native jig options and class-path, processor-path,\n"
                + "source, target, encoding, system and add-reads. Unconfigured options are omitted.\n";
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
            throw new IllegalArgumentException("--source-set is required for argument resolution");
        }
        if (sourceSet != null && sourceSet.isBlank()) {
            throw new IllegalArgumentException("--source-set must not be empty");
        }
        if (compiler) {
            if (classpath != null && !classpath.equals("compile")) {
                throw new IllegalArgumentException("--resolve-compiler-options implies the compile classpath");
            }
            classpath = "compile";
        } else if (resolve != null && classpath == null) {
            throw new IllegalArgumentException("--classpath is required for argument resolution");
        }
        if (classpath != null && !classpath.equals("compile") && !classpath.equals("runtime")) {
            throw new IllegalArgumentException("--classpath must be compile or runtime");
        }
        var options = new LinkedHashSet<String>();
        var nativeOptions = new Options();
        if (resolve != null) {
            var nativeSpecifications = new LinkedHashSet<String>();
            for (String specification : resolve.split(",", -1)) {
                specification = specification.trim();
                if (EXTRA_OPTIONS.contains(specification)) {
                    options.add(specification);
                } else {
                    nativeSpecifications.add(specification);
                }
            }
            if (!nativeSpecifications.isEmpty()) {
                nativeOptions.setResolveOptions(String.join(",", nativeSpecifications));
                options.addAll(nativeOptions.resolveOptions);
            }
        }
        return new Request(
                directory(root),
                project,
                sourceSet,
                classpath,
                listProjects,
                listSourceSets,
                compiler,
                Set.copyOf(options),
                nativeOptions.moduleForm,
                write == null ? null : Path.of(write),
                parsed.contains(VERBOSE));
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
