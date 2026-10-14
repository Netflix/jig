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
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.netflix.tools.jig.GradleCommandLine.Request;
import com.netflix.tools.jig.Jig.Options.ModuleForm;
import com.netflix.tools.jig.module.SourceModuleFinder;

/**
 * Projects a captured Gradle argument view without re-mediating its
 * dependencies.
 */
final class GradleArguments {
    record View(List<String> sources, List<String> classpath, List<String> modulePath, List<String> processors,
                List<String> additional, boolean modular, Map<String, String> metadata) {
        static View read(BuildCapture capture) throws IOException {
            var metadata = new LinkedHashMap<String, String>();
            for (String name : List.of("release", "source", "target", "encoding", "module-version", "main-class", "system", "module-name")) {
                String value = capture.optional(name);
                if (value != null) {
                    metadata.put(name, value);
                }
            }
            return new View(capture.list("sources"), capture.list("classpath"), capture.list("module-path"),
                    capture.list("processors"), capture.list("additional"), capture.flag("modular"), Map.copyOf(metadata));
        }
    }

    private GradleArguments() {}

    static String render(Request request, View view) {
        var arguments = new ArrayList<String>();
        var options = request.options();
        var metadata = view.metadata();
        List<String> sources = view.sources();
        List<String> additional = view.additional();
        boolean release = metadata.containsKey("release") || additional.stream().anyMatch(argument -> argument.equals("--release") || argument.startsWith("--release="));
        path(arguments, options.contains("class-path"), "--class-path", view.classpath());
        path(arguments, options.contains("module-path"), "--module-path", view.modulePath());
        String module = view.modular() && (options.contains("module") || options.contains("add-modules")
                || options.contains("module-source-path") || options.contains("describe-module")) ? moduleName(sources, metadata) : null;
        boolean moduleSources = options.contains("module-source-path") && module != null;
        path(arguments, options.contains("source-path") && !moduleSources, "--source-path", sources);
        path(arguments, options.contains("processor-path"), "--processor-path", view.processors());
        for (String name : List.of("release", "source", "target", "encoding", "module-version", "main-class")) {
            boolean selected = options.contains(name) && !(release && (name.equals("source") || name.equals("target")));
            value(
                    arguments,
                    selected,
                    name.equals("source") || name.equals("target") || name.equals("encoding")
                            ? "-" + name
                            : "--" + name,
                    metadata.get(name));
        }
        value(arguments, options.contains("system") && !release, "--system",
                metadata.get("system"));
        if (module != null) {
            path(arguments, moduleSources, "--module-source-path",
                    List.of(module + "=" + String.join(System.getProperty("path.separator"), sources)));
            value(arguments, options.contains("add-modules") && !(options.contains("module") && request.moduleForm() == ModuleForm.ROOTS),
                    "--add-modules", module);
            if (options.contains("module")) {
                String main = metadata.get("main-class");
                value(arguments, request.moduleForm() != ModuleForm.MAIN || main != null, "--module",
                        request.moduleForm() == ModuleForm.MAIN ? module + "/" + main : module);
            }
            value(arguments, options.contains("describe-module"), "--describe-module", module);
        }
        additional(arguments, request, additional);
        return JavaArgumentFile.render(arguments);
    }

    private static void path(List<String> arguments, boolean requested, String option,
            List<String> values) {
        if (!values.isEmpty()) {
            value(arguments, requested, option,
                    String.join(System.getProperty("path.separator"), new LinkedHashSet<>(values)));
        }
    }

    private static void value(List<String> arguments, boolean requested, String option,
            String value) {
        if (requested && value != null && !value.isEmpty()) {
            arguments.add(option);
            arguments.add(value);
        }
    }

    private static String moduleName(List<String> sources, Map<String, String> metadata) {
        String explicit = metadata.get("module-name");
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        var names = new LinkedHashSet<String>();
        for (String source : sources) {
            Path root = Path.of(source);
            if (Files.isRegularFile(root.resolve("module-info.java"))) {
                ModuleFinder finder = SourceModuleFinder.of(root);
                finder.findAll().forEach(reference -> names.add(reference.descriptor()
                        .name()));
            }
        }
        if (names.size() > 1) {
            throw new IllegalArgumentException("Gradle project declares multiple main modules: " + names);
        }
        return names.isEmpty() ? null : names.getFirst();
    }

    private static void additional(List<String> arguments, Request request, List<String> additional) {
        for (int i = 0; i < additional.size(); i++) {
            String argument = additional.get(i);
            int equals = argument.indexOf('=');
            String spelling = equals < 0 ? argument : argument.substring(0, equals);
            String option = switch (spelling) {
                case "-cp", "-classpath", "--class-path" -> "class-path";
                case "-p", "--module-path" -> "module-path";
                case "-processorpath", "--processor-path" -> "processor-path";
                case "-source", "--source" -> "source";
                case "-target", "--target" -> "target";
                case "-encoding" -> "encoding";
                default -> spelling.startsWith("--") ? spelling.substring(2) : "";
            };
            if (!request.options().contains(option)) {
                continue;
            }
            arguments.add(argument);
            if (equals < 0 && !option.equals("enable-preview") && i + 1 < additional.size()) {
                arguments.add(additional.get(++i));
            }
        }
    }
}
