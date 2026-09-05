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

package com.netflix.tools.jig.module;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import javax.tools.StandardLocation;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.compile.CompiledModule;
import com.netflix.module.compile.ModuleCompiler;
import com.netflix.module.compile.ModulePathEntry;

/** Module reference backed by a lazily compiled source declaration. */
public final class SourceModuleReference extends ModuleReference {
    public record Dependency(String name,
                             ModuleReference reference,
                             ModuleHash hash,
                             boolean upgrade,
                             boolean system) {}

    @FunctionalInterface
    public interface PathResolver {
        Path resolve(Dependency dependency) throws IOException;
    }

    public record CompilationEnvironment(ModuleCompiler compiler,
            List<Dependency> dependencies,
            PathResolver paths,
            String moduleVersion,
            ModuleReference existing,
            Map<String, Path> moduleSources,
            ModuleCompiler.Options options) {
        public CompilationEnvironment(ModuleCompiler compiler,
                                      List<Dependency> dependencies,
                                      PathResolver paths,
                                      String moduleVersion,
                                      ModuleReference existing) {
            this(compiler,
                 dependencies,
                 paths,
                 moduleVersion,
                 existing,
                 Map.of());
        }

        public CompilationEnvironment(ModuleCompiler compiler,
                                      List<Dependency> dependencies,
                                      PathResolver paths,
                                      String moduleVersion,
                                      ModuleReference existing,
                                      Map<String, Path> moduleSources) {
            this(compiler,
                 dependencies,
                 paths,
                 moduleVersion,
                 existing,
                 moduleSources,
                 ModuleCompiler.Options.DEFAULT);
        }

        public CompilationEnvironment {
            dependencies = List.copyOf(dependencies);
            moduleSources = Map.copyOf(moduleSources);
            options = java.util.Objects.requireNonNull(options, "options");
        }
    }

    private final SourceModule sourceModule;
    private volatile CompilationEnvironment environment;
    private volatile FutureTask<Compilation> compilation;

    private record Compilation(ModuleReference existing, CompiledModule compiledModule) {}

    private volatile ModuleReference compiledReference;

    private SourceModuleReference(SourceModule sourceModule) {
        super(sourceModule.descriptor(), null);
        this.sourceModule = sourceModule;
    }

    static SourceModuleReference uncompiled(SourceModule sourceModule) {
        return new SourceModuleReference(sourceModule);
    }

    public SourceModule sourceModule() {
        return sourceModule;
    }

    public synchronized void bind(CompilationEnvironment environment) {
        if (compilation != null) {
            throw new IllegalStateException("Source module already has a compilation environment: " + sourceModule.name());
        }
        this.environment = environment;
        compilation = new FutureTask<>(this::compileOrReuse);
    }

    @Override
    public ModuleReader open() throws IOException {
        var compilation = compilation();
        if (compilation.existing() != null) {
            return compilation.existing().open();
        }
        return compiledReference(compilation.compiledModule()).open();
    }

    /**
     * Returns the compiled module, or {@code null} when an existing module was
     * reused.
     */
    public CompiledModule compiledModule() throws IOException {
        return compilation().compiledModule();
    }

    /**
     * Compiles source modules concurrently while their memoized dependencies
     * recurse.
     */
    public static void compileAll(Collection<SourceModuleReference> modules) throws IOException {
        var references = List.copyOf(new LinkedHashSet<>(modules));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures =
                    references.stream()
                            .map(reference -> executor.submit((Callable<CompiledModule>) reference::compiledModule))
                            .toList();
            for (var future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    futures.forEach(task -> task.cancel(true));
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while compiling source modules", e);
                } catch (ExecutionException e) {
                    futures.forEach(task -> task.cancel(true));
                    throw compilationFailure(e.getCause());
                }
            }
        }
    }

    private Compilation compilation() throws IOException {
        var task = compilation;
        if (task == null) {
            throw new IllegalStateException("Source module " + sourceModule.name() + " has no compilation environment");
        }
        task.run();
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while compiling " + sourceModule.name(), e);
        } catch (ExecutionException e) {
            var failure = e.getCause();
            if (failure instanceof IOException exception)
                throw exception;
            if (failure instanceof RuntimeException exception)
                throw exception;
            if (failure instanceof Error error)
                throw error;
            throw new IOException("Cannot compile " + sourceModule.name(), failure);
        }
    }

    private static IOException compilationFailure(Throwable failure) {
        if (failure instanceof IOException exception)
            return exception;
        if (failure instanceof RuntimeException exception)
            throw exception;
        if (failure instanceof Error error)
            throw error;
        return new IOException("Cannot compile source modules", failure);
    }

    private Compilation compileOrReuse() throws IOException {
        var environment = this.environment;
        if (environment.existing() != null) {
            return new Compilation(environment.existing(), null);
        }

        var compiler = environment.compiler();
        var modulePathEntries = new ArrayList<ModulePathEntry>();
        var resolved = new HashMap<Map.Entry<StandardLocation, String>, Dependency>();
        var resolvedPaths = new HashMap<Map.Entry<StandardLocation, String>, Path>();
        for (var dependency : environment.dependencies()) {
            if (dependency.system())
                continue;
            var hash = dependency.hash();
            var location = dependency.upgrade() ? StandardLocation.UPGRADE_MODULE_PATH : StandardLocation.MODULE_PATH;
            if (dependency.reference() instanceof SourceModuleReference source) {
                var compiled = source.compiledModule();
                if (compiled != null) {
                    hash = compiled.compilationHash();
                    var paths = compiler.modulePath(dependency.name(), compiled, true);
                    addModulePathEntry(modulePathEntries,
                                       resolvedPaths,
                                       dependency.name(),
                                       location,
                                       hash,
                                       paths.modulePath());
                    if (paths.hasPatchModule()) {
                        addModulePathEntry(modulePathEntries,
                                           resolvedPaths,
                                           dependency.name(),
                                           StandardLocation.PATCH_MODULE_PATH,
                                           new ModuleHash(ModuleHash.Type.PATCH,
                                                          hash.algorithm(),
                                                          hash.digest()),
                                           paths.patchModulePath());
                    }
                    continue;
                }
            }
            if (hash == null) {
                hash = dependency.reference() instanceof ModulePathReference module ? module.hash() : ModuleHash.moduleSha256(dependency.reference());
            }
            modulePathEntries.add(new ModulePathEntry(dependency.name(), location, hash));
            var key = modulePathKey(dependency.name(), location);
            if (resolved.put(key, dependency) != null) {
                throw new IOException("Duplicate module-path entry: " + key);
            }
        }

        var release = sourceModule.release();
        if (release == null && !usesSystemImage(environment.dependencies())) {
            release = Runtime.version().feature();
        }
        var dependencyNames =
                environment.dependencies().stream()
                        .map(Dependency::name)
                        .collect(Collectors.toSet());
        var qualifiedTargets = new HashSet<String>();
        sourceModule.descriptor().exports().stream()
                .flatMap(export -> export.targets().stream())
                .forEach(qualifiedTargets::add);
        sourceModule.descriptor().opens().stream()
                .flatMap(open -> open.targets().stream())
                .forEach(qualifiedTargets::add);
        var observableModuleSources = new TreeMap<>(environment.moduleSources());
        observableModuleSources.keySet().retainAll(qualifiedTargets);
        observableModuleSources.keySet().removeAll(dependencyNames);
        observableModuleSources.remove(sourceModule.name());
        ModuleCompiler.ModulePathResolver pathResolver = entry -> {
            var key = modulePathKey(entry.moduleName(), entry.location());
            var path = resolvedPaths.get(key);
            return path != null
                    ? path
                    : environment.paths().resolve(resolved.get(key));
        };
        var configuration =
                configuration(sourceModule.name(),
                              release == null ? OptionalInt.empty() : OptionalInt.of(release),
                              sourceModule.preview(),
                              Optional.ofNullable(environment.moduleVersion()).map(ModuleDescriptor.Version::parse),
                              sourceModule.descriptor().mainClass(),
                              sourceModule.runtimeAccessOptions(),
                              observableModuleSources,
                              modulePathEntries,
                              pathResolver);
        var compiledModule =
                compiler.compile(sourceModule.sourceDirectory(),
                                 configuration,
                                 Runtime.version().feature(),
                                 environment.options());
        return new Compilation(null, compiledModule);
    }

    private boolean usesSystemImage(List<Dependency> dependencies) {
        if (dependencies.stream().anyMatch(Dependency::upgrade))
            return true;
        var systemModules = ModuleFinder.ofSystem();
        return sourceModule.runtimeAccessOptions().addExports().stream()
                .anyMatch(access ->
                        access.targetModule().equals(sourceModule.name())
                                && systemModules.find(access.sourceModule()).isPresent());
    }

    private ModuleReference compiledReference(CompiledModule compilation) throws IOException {
        var reference = compiledReference;
        if (reference != null)
            return reference;
        synchronized (this) {
            if (compiledReference == null) {
                var module =
                        environment.compiler()
                                .modulePath(sourceModule.name(), compilation, false)
                                .modulePath();
                compiledReference =
                        ModuleFinder.of(module)
                                .find(sourceModule.name())
                                .orElseThrow(() -> new IOException("Compiled module is not readable: " + module));
            }
            return compiledReference;
        }
    }

    /* Bootstrap against the preceding Configuration record shape. */
    private static ModuleCompiler.Configuration configuration(String moduleName,
            OptionalInt release,
            boolean preview,
            Optional<ModuleDescriptor.Version> moduleVersion,
            Optional<String> mainClass,
            ModuleRuntimeAccessOptions runtimeAccess,
            Map<String, Path> moduleSources,
            List<ModulePathEntry> modulePathEntries,
            ModuleCompiler.ModulePathResolver paths) {
        try {
            var constructor =
                    ModuleCompiler.Configuration.class.getConstructor(String.class,
                            OptionalInt.class,
                            boolean.class,
                            Optional.class,
                            Optional.class,
                            ModuleRuntimeAccessOptions.class,
                            Map.class,
                            List.class,
                            ModuleCompiler.ModulePathResolver.class);
            return constructor.newInstance(moduleName,
                                           release,
                                           preview,
                                           moduleVersion,
                                           mainClass,
                                           runtimeAccess,
                                           moduleSources,
                                           modulePathEntries,
                                           paths);
        } catch (NoSuchMethodException bootstrap) {
            return new ModuleCompiler.Configuration(moduleName,
                    release,
                    preview,
                    moduleVersion,
                    mainClass,
                    runtimeAccess,
                    modulePathEntries,
                    paths);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct compiler configuration", failure);
        }
    }

    private static void addModulePathEntry(List<ModulePathEntry> entries,
            HashMap<Map.Entry<StandardLocation, String>, Path> paths,
            String moduleName,
            StandardLocation location,
            ModuleHash hash,
            Path path) throws IOException {
        entries.add(new ModulePathEntry(moduleName, location, hash));
        var key = modulePathKey(moduleName, location);
        if (paths.put(key, path) != null) {
            throw new IOException("Duplicate module-path entry: " + key);
        }
    }

    private static Map.Entry<StandardLocation, String> modulePathKey(String moduleName, StandardLocation location) {
        return Map.entry(location, moduleName);
    }
}
