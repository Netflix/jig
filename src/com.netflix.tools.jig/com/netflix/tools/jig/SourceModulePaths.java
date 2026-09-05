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
import java.io.Writer;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.netflix.module.ModuleHash;
import com.netflix.module.compile.ModuleCompiler;
import com.netflix.tools.jig.Jig.RepositoryPaths;
import com.netflix.tools.jig.module.ModulePathReference;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.SourceModuleReference;
import com.netflix.tools.jig.module.SourceModuleReference.CompilationEnvironment;
import com.netflix.tools.jig.module.SourceModuleReference.Dependency;

final class SourceModulePaths {
    private final Jig.Options options;
    private final ModuleResolution resolution;
    private final RepositoryPaths repositoryPaths;
    private final ModuleCompiler compiler;
    private final Writer compilerOutput;
    private final ModuleFinder systemModules;
    private final Map<String, ModuleReference> existingModules;
    private final Map<String, ModuleHash> hashes;
    private final Map<String, Path> patches;

    SourceModulePaths(Jig.Options options, ModuleResolution resolution, RepositoryPaths repositoryPaths,
                      ModuleCompiler compiler, Writer compilerOutput) {
        this.options = options;
        this.resolution = resolution;
        this.repositoryPaths = repositoryPaths;
        this.compiler = compiler;
        this.compilerOutput = compilerOutput;
        systemModules = ModuleFinder.ofSystem();
        existingModules = new HashMap<>();
        hashes = resolution.hashes();
        patches = new HashMap<>();
        if (options.modulePath.length > 0) {
            ModuleFinder.of(options.modulePath)
                    .findAll()
                    .forEach(reference -> existingModules.put(reference.descriptor()
                            .name(),
                            reference));
        }
    }

    void bind() {
        for (var resolved : resolution.configuration().modules()) {
            if (!(resolved.reference() instanceof SourceModuleReference source)) {
                continue;
            }
            var readable = resolved.reads().stream()
                    .map(module -> module.name())
                    .collect(Collectors.toSet());
            var dependencies = new ArrayList<Dependency>();
            for (var dependency : resolution.configuration().modules()) {
                if (dependency.name().equals(resolved.name()) || !readable.contains(dependency.name())) {
                    continue;
                }
                var system = isSystemModule(dependency.name(), dependency.reference());
                dependencies.add(
                        new Dependency(
                                dependency.name(),
                                dependency.reference(),
                                system ? null : hashes.get(dependency.name()),
                                resolution.systemOverrides().contains(dependency.name()),
                                system));
            }
            source.bind(
                    new CompilationEnvironment(compiler, dependencies, this::compilationModulePath, options.moduleVersion,
                            existingModules.get(resolved.name()), resolution.moduleSources(), new ModuleCompiler.Options(options.recompile, options.emitCompileDiagnostics, options.verbose ? compilerOutput : null)));
        }
    }

    Path modulePath(String moduleName, ModuleReference reference, boolean allowPatch) throws IOException {
        if (reference instanceof SourceModuleReference source) {
            var existing = existingModules.get(moduleName);
            if (existing != null) {
                return existing.location()
                               .filter(location -> location.getScheme().equalsIgnoreCase("file"))
                               .map(Path::of)
                               .orElse(null);
            }
            var compiled = source.compiledModule();
            var paths = compiler.modulePath(moduleName, compiled, allowPatch);
            if (paths.hasPatchModule()) {
                patches.put(moduleName, paths.patchModulePath());
            } else {
                patches.remove(moduleName);
            }
            return paths.modulePath();
        }
        var selected = repositoryPaths.jmods().get(moduleName);
        if (selected == null) {
            selected = repositoryPaths.targetJars().get(moduleName);
        }
        if (selected != null) {
            return selected;
        }
        if (reference instanceof ModulePathReference module) {
            return module.modulePath();
        }
        return reference.location()
                        .filter(location -> location.getScheme().equalsIgnoreCase("file"))
                        .map(Path::of)
                        .orElse(null);
    }

    Map<String, Path> patches() {
        return Map.copyOf(patches);
    }

    private boolean isSystemModule(String name, ModuleReference reference) {
        return systemModules.find(name)
                            .map(system -> system.location().equals(reference.location()))
                            .orElse(false);
    }

    private Path compilationModulePath(Dependency dependency) throws IOException {
        if (dependency.reference() instanceof SourceModuleReference source) {
            var existing = existingModules.get(dependency.name());
            if (existing != null) {
                return existing.location()
                               .filter(location -> location.getScheme().equalsIgnoreCase("file"))
                               .map(Path::of)
                               .orElse(null);
            }
            return compiler.modulePath(dependency.name(), source.compiledModule(), false).modulePath();
        }
        return modulePath(dependency.name(), dependency.reference(), false);
    }
}
