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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

import com.netflix.tools.jig.GradleCommandLine.Request;
import com.netflix.tools.jig.Jig.Options.ModuleForm;
import com.netflix.tools.jig.module.SourceModuleFinder;

/**
 * Projects a captured Gradle argument view without re-mediating its
 * dependencies.
 */
final class GradleArguments {
    private GradleArguments() {}

    static String render(Request request, Properties values) throws IOException {
        if (request.compiler()) {
            if (values.getProperty("compiler-options.count") == null) {
                throw new IOException("Missing Gradle compiler options");
            }
            return argumentFile(list(values, "compiler-options"));
        }
        var arguments = new ArrayList<String>();
        var options = request.options();
        List<String> sources = list(values, "sources");
        List<String> additional = list(values, "additional");
        boolean release = values.getProperty("release") != null || additional.stream().anyMatch(argument -> argument.equals("--release") || argument.startsWith("--release="));
        boolean modular = Boolean.parseBoolean(values.getProperty("modular", "false"));
        path(arguments, options.contains("class-path"), "--class-path", list(values, "classpath"));
        path(arguments, options.contains("module-path"), "--module-path", list(values, "module-path"));
        path(arguments, options.contains("source-path"), "--source-path", sources);
        path(arguments, options.contains("processor-path"), "--processor-path", list(values, "processors"));
        for (String name : List.of("release", "source", "target", "encoding", "module-version", "main-class")) {
            boolean selected = options.contains(name) && !(release && (name.equals("source") || name.equals("target")));
            value(
                    arguments,
                    selected,
                    name.equals("source") || name.equals("target") || name.equals("encoding")
                            ? "-" + name
                            : "--" + name,
                    values.getProperty(name));
        }
        value(arguments, options.contains("system") && !release, "--system",
                values.getProperty("system"));
        boolean needsModule = options.contains("module")
                || options.contains("add-modules")
                || options.contains("module-source-path")
                || options.contains("describe-module");
        if (modular && needsModule) {
            String module = moduleName(sources, values);
            if (module != null) {
                path(arguments, options.contains("module-source-path"), "--module-source-path",
                        List.of(module + "=" + String.join(System.getProperty("path.separator"), sources)));
                value(arguments, options.contains("add-modules") && !(options.contains("module") && request.moduleForm() == ModuleForm.ROOTS),
                        "--add-modules", module);
                if (options.contains("module")) {
                    String main = values.getProperty("main-class");
                    value(arguments, request.moduleForm() != ModuleForm.MAIN || main != null, "--module",
                            request.moduleForm() == ModuleForm.MAIN ? module + "/" + main : module);
                }
                value(arguments, options.contains("describe-module"), "--describe-module", module);
            }
        }
        additional(arguments, request, additional);
        return argumentFile(arguments);
    }

    private static String argumentFile(List<String> arguments) {
        var output = new StringBuilder();
        for (String argument : arguments) {
            output.append('"')
                  .append(
                          argument.replace("\\", "\\\\")
                                  .replace("\"", "\\\"")
                                  .replace("\n", "\\n")
                                  .replace("\r", "\\r")
                                  .replace("\t", "\\t")
                                  .replace("\f", "\\f"))
                  .append('"')
                  .append('\n');
        }
        return output.toString();
    }

    static List<String> list(Properties values, String name) throws IOException {
        String count = values.getProperty(name + ".count", "0");
        int size;
        try {
            size = Integer.parseInt(count);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Gradle capture count for " + name + ": " + count, e);
        }
        if (size < 0 || size > 100_000) {
            throw new IOException("Invalid Gradle capture count for " + name + ": " + count);
        }
        var entries = new ArrayList<String>();
        for (int i = 0; i < size; i++) {
            String entry = values.getProperty(name + "." + i);
            if (entry == null) {
                throw new IOException("Missing Gradle capture entry: " + name + "." + i);
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
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

    private static String moduleName(List<String> sources, Properties values) {
        String explicit = values.getProperty("module-name");
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
