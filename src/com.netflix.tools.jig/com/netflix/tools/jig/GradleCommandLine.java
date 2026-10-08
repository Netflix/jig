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

/** Explicit project locations and standard argument projections for Gradle. */
final class GradleCommandLine {
    private static final Set<String> EXTRA_OPTIONS = Set.of("class-path", "processor-path", "source", "target", "encoding", "system",
            "add-reads");
    private static final ToolOption ROOT = ToolOption.option("--root-project-dir", "DIRECTORY", "Gradle root project directory");
    private static final ToolOption PROJECT = ToolOption.option("--project-dir", "DIRECTORY", "A project directory from --list-project-dirs");
    private static final ToolOption LIST = ToolOption.flag("--list-project-dirs", "List configured project directories");
    private static final ToolOption SCOPE = ToolOption.option("--scope", "compile|runtime", "Java argument scope");
    private static final ToolOption RESOLVE = ToolOption.option("--resolve-options", "OPTION[,OPTION...]", "Resolve standard options to stdout", "-r");
    private static final ToolOption WRITE = ToolOption.option("--write-argfile", "PATH", "Write resolved options to a Java argument file", "-w");
    private static final ToolOption VERBOSE = ToolOption.flag("--verbose", "Show the Gradle invocation and build diagnostics");
    private static final ToolOption HELP = ToolOption.flag("--help", "Print this help message", "-h");
    private static final CommandLine COMMAND_LINE = CommandLine.builder()
            .description("Resolve standard Java arguments from a Gradle project")
            .options(ROOT, PROJECT, LIST, SCOPE, RESOLVE, WRITE,
                    VERBOSE, HELP)
            .build();

    record Request(
            Path root,
            Path project,
            String scope,
            boolean listProjects,
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
                + "Source roots are obtained from the build, not supplied by the caller.\n"
                + "Compile and runtime select the project's main Java source configuration.\n"
                + "A project without Java scope content produces no arguments.\n"
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
        String scope = single(parsed, SCOPE);
        String resolve = single(parsed, RESOLVE);
        String write = single(parsed, WRITE);
        boolean list = parsed.contains(LIST);
        if (root == null) {
            throw new IllegalArgumentException("--root-project-dir is required");
        }
        if (write != null && resolve == null) {
            throw new IllegalArgumentException("--write-argfile requires --resolve-options");
        }
        if (list && resolve != null) {
            throw new IllegalArgumentException("--list-project-dirs and --resolve-options are mutually exclusive");
        }
        if (!list && resolve == null) {
            throw new IllegalArgumentException("--list-project-dirs or --resolve-options is required");
        }
        if (list && (project != null || scope != null)) {
            throw new IllegalArgumentException("--project-dir and --scope apply only to argument resolution");
        }
        if (!list && project == null) {
            throw new IllegalArgumentException("--project-dir is required for argument resolution");
        }
        if (!list && scope == null) {
            throw new IllegalArgumentException("--scope is required for argument resolution");
        }
        if (scope != null && !scope.equals("compile") && !scope.equals("runtime")) {
            throw new IllegalArgumentException("--scope must be compile or runtime");
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
                project == null ? null : directory(project),
                scope,
                list,
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
