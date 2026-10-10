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
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import com.netflix.tools.jig.Jig.Options.ModuleForm;
import com.netflix.tools.jig.module.ModuleIdentity;
import com.netflix.tools.jig.module.SourceModuleFinder;

/** Projects Maven-selected paths using jig's existing source and artifact module identities. */
final class MavenArguments {
    private MavenArguments() {}

    static String render(Set<String> options, ModuleForm moduleForm, Properties values) throws IOException {
        List<String> sources = MavenProjectCommands.list(values, "sources");
        List<String> supplied = MavenProjectCommands.list(values, "classpath");
        String outputDirectory = values.getProperty("output-directory");
        if (outputDirectory == null) {
            throw new IOException("Missing Maven capture entry: output-directory");
        }
        Path output = Path.of(outputDirectory).toAbsolutePath().normalize();
        ModuleDescriptor sourceModule = sourceModule(sources);
        ModuleDescriptor module = sourceModule != null ? sourceModule : binaryModule(output);
        boolean sourceView = options.contains(module == null ? "source-path" : "module-source-path");
        var classpath = new LinkedHashSet<String>();
        var modulePath = new LinkedHashSet<String>();
        for (String entry : supplied) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (path.equals(output) && sourceView) {
                // The corresponding sources, not an old compiled output, satisfy
                // this selection. Prerequisites remain on the binary paths.
                continue;
            }
            if (module == null) {
                classpath.add(entry);
            } else if (path.equals(output) || namedModule(path)) {
                modulePath.add(entry);
            } else {
                classpath.add(entry);
            }
        }
        var arguments = new ArrayList<String>();
        path(arguments, options.contains("class-path"), "--class-path", List.copyOf(classpath));
        path(arguments, options.contains("module-path"), "--module-path", List.copyOf(modulePath));
        path(arguments, options.contains("source-path"), "--source-path", sources);
        if (module != null) {
            String name = module.name();
            path(arguments, options.contains("module-source-path") && sourceModule != null, "--module-source-path",
                    List.of(name + "=" + String.join(System.getProperty("path.separator"), sources)));
            if (options.contains("add-modules") && !(options.contains("module") && moduleForm == ModuleForm.ROOTS)) {
                arguments.addAll(List.of("--add-modules", name));
            }
            if (options.contains("module")) {
                String value = name;
                if (moduleForm == ModuleForm.MAIN) {
                    value += "/" + module.mainClass().orElseThrow(() -> new IllegalArgumentException("No main class declared for module " + name));
                }
                arguments.addAll(List.of("--module", value));
            }
            if (options.contains("describe-module")) {
                arguments.addAll(List.of("--describe-module", name));
            }
        }
        return MavenProjectCommands.argumentFile(arguments);
    }

    private static ModuleDescriptor sourceModule(List<String> sources) {
        var modules = new LinkedHashMap<String, ModuleDescriptor>();
        for (String source : sources) {
            Path root = Path.of(source);
            if (Files.isRegularFile(root.resolve("module-info.java"))) {
                SourceModuleFinder.of(root).findAll().forEach(reference -> modules.putIfAbsent(reference.descriptor().name(), reference.descriptor()));
            }
        }
        if (modules.size() > 1) {
            throw new IllegalArgumentException("Maven scope declares multiple modules: " + modules.keySet());
        }
        return modules.isEmpty() ? null : modules.sequencedValues().getFirst();
    }

    private static ModuleDescriptor binaryModule(Path output) {
        if (!Files.isRegularFile(output.resolve("module-info.class"))) {
            return null;
        }
        return ModuleFinder.of(output).findAll().iterator().next().descriptor();
    }

    private static boolean namedModule(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return Files.isRegularFile(path.resolve("module-info.class"));
        }
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) {
            return false;
        }
        // Observe declared identity before invoking ModuleFinder: unnamed jars may
        // legitimately contain classes that cannot be placed on a module path.
        try (var jar = new JarFile(path.toFile())) {
            Set<String> names = jar.stream().map(entry -> entry.getName()).collect(Collectors.toSet());
            ModuleIdentity identity = ModuleIdentity.parseJarEntries(names, name -> {
                try (var input = jar.getInputStream(jar.getJarEntry(name))) {
                    return input.readAllBytes();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            if (identity.moduleName() == null) {
                return false;
            }
            // Validate an explicitly named artifact using the standard JPMS reader.
            ModuleFinder.of(path).findAll();
            return true;
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static void path(List<String> arguments, boolean requested, String option, List<String> paths) {
        if (requested && !paths.isEmpty()) {
            arguments.addAll(List.of(option, String.join(System.getProperty("path.separator"), new LinkedHashSet<>(paths))));
        }
    }
}
