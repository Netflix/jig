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
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleInfoHash;
import com.netflix.module.ModuleInfoHash.Builder;
import com.netflix.module.ModuleInfoHash.Coordinate;
import com.netflix.tools.jig.module.AetherModuleResolver.Result;

/** The observable modules, resolved configuration, and associated repository metadata. */
public record ModuleResolution(
        ModuleFinder observableModules,
        Configuration configuration,
        SequencedSet<String> configurationRoots,
        Set<String> staticRoots,
        Map<String, ModuleHash> hashes,
        Map<String, String> repositoryVersions,
        Set<String> systemOverrides,
        Map<String, Path> moduleSources,
        Map<String, Path> sources) {

    public enum IntegrityMode {
        NONE,
        UPDATE,
        VERIFY
    }

    @FunctionalInterface
    public interface DependencyResolver {
        Result resolve(Collection<ModuleDescriptor> roots, boolean includeStatics, boolean includeSources);
    }

    public ModuleResolution {
        configurationRoots = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(configurationRoots));
        staticRoots = Set.copyOf(staticRoots);
        hashes = Map.copyOf(hashes);
        repositoryVersions = Map.copyOf(repositoryVersions);
        systemOverrides = Set.copyOf(systemOverrides);
        moduleSources = Map.copyOf(moduleSources);
        sources = Map.copyOf(sources);
    }

    public static ModuleResolution resolve(ModuleRepositorySession repository, ModuleFinder fixedModules, Collection<String> requestedRoots,
            boolean includeStatics, boolean includeSources) {
        return resolve(repository::resolveModules, fixedModules, requestedRoots, Map.of(),
                includeStatics, includeSources);
    }

    public static ModuleResolution resolve(ModuleRepositorySession repository, ModuleFinder fixedModules, Collection<String> requestedRoots,
            Map<String, String> addedRequires, boolean includeStatics, boolean includeSources) {
        return resolve(repository::resolveModules, fixedModules, requestedRoots, addedRequires, includeStatics,
                includeSources, IntegrityMode.NONE);
    }

    public static ModuleResolution resolve(
            ModuleRepositorySession repository,
            ModuleFinder fixedModules,
            Collection<String> requestedRoots,
            Map<String, String> addedRequires,
            boolean includeStatics,
            boolean includeSources,
            IntegrityMode integrityMode) {
        return resolve(repository::resolveModules, fixedModules, requestedRoots, addedRequires, includeStatics,
                includeSources, integrityMode);
    }

    public static ModuleResolution resolve(ModuleRepositorySession repository, ModuleFinder fixedModules, Collection<String> requestedRoots,
            boolean includeStatics, boolean includeSources, IntegrityMode integrityMode) {
        return resolve(repository::resolveModules, fixedModules, requestedRoots, Map.of(),
                includeStatics, includeSources, integrityMode);
    }

    public static ModuleResolution resolve(DependencyResolver dependencyResolver, ModuleFinder fixedModules, Collection<String> requestedRoots,
            boolean includeStatics, boolean includeSources) {
        return resolve(dependencyResolver, fixedModules, requestedRoots, Map.of(), includeStatics,
                includeSources);
    }

    public static ModuleResolution resolve(DependencyResolver dependencyResolver, ModuleFinder fixedModules, Collection<String> requestedRoots,
            Map<String, String> addedRequires, boolean includeStatics, boolean includeSources) {
        return resolve(dependencyResolver, fixedModules, requestedRoots, addedRequires, includeStatics, includeSources,
                IntegrityMode.NONE);
    }

    public static ModuleResolution resolve(
            DependencyResolver dependencyResolver,
            ModuleFinder fixedModules,
            Collection<String> requestedRoots,
            Map<String, String> addedRequires,
            boolean includeStatics,
            boolean includeSources,
            IntegrityMode integrityMode) {
        ModuleFinder systemModules = ModuleFinder.ofSystem();
        Configuration parent = ModuleLayer.boot().configuration();
        ModuleFinder unresolvedSystemModules = finder(
                systemModules.findAll().stream()
                        .filter(reference -> parent.findModule(reference.descriptor().name()).isEmpty())
                        .collect(
                                Collectors.toMap(
                                        reference -> reference.descriptor().name(),
                                        reference -> reference,
                                        (left, right) -> left,
                                        LinkedHashMap::new)));
        var allAvailable = modules(fixedModules, systemModules);
        var sourceModules = selectedSourceModules(allAvailable, requestedRoots, includeStatics);
        ModuleFinder selectedFixedModules = finder(
                fixedModules.findAll().stream()
                        .filter(reference -> !(reference instanceof SourceModuleReference) || sourceModules.containsKey(reference.descriptor().name()))
                        .collect(
                                Collectors.toMap(
                                        reference -> reference.descriptor().name(),
                                        reference -> reference,
                                        (left, right) -> left,
                                        LinkedHashMap::new)));
        var available = modules(selectedFixedModules, systemModules);
        validateAddedRequires(modules(selectedFixedModules), addedRequires);
        var rootDeclarations = new ArrayList<>(localRootDeclarations(selectedFixedModules, requestedRoots, includeStatics, available,
                systemModules));
        if (!addedRequires.isEmpty()) {
            rootDeclarations.add(addedRequiresDeclaration(addedRequires, available, systemModules));
        }
        var repositoryModules = dependencyResolver.resolve(rootDeclarations, includeStatics, includeSources);
        var systemOverrides = new LinkedHashSet<String>();
        selectedFixedModules.findAll().stream()
                .map(reference -> reference.descriptor().name())
                .filter(name -> systemModules.find(name).isPresent())
                .forEach(systemOverrides::add);
        repositoryModules.versions().keySet().stream()
                .filter(name -> systemModules.find(name).isPresent())
                .forEach(systemOverrides::add);
        var modules = modules(selectedFixedModules, repositoryModules.observableModules(), unresolvedSystemModules);
        ModuleFinder observableModules = finder(modules);
        var configurationInputs = configurationInputs(
                selectedFixedModules,
                repositoryModules.observableModules(),
                unresolvedSystemModules,
                parent,
                systemOverrides);
        var runtimeRoots = new LinkedHashSet<>(requestedRoots);
        runtimeRoots.addAll(addedRequires.keySet());
        runtimeRoots.addAll(repositoryModules.automaticModuleRoots());
        Set<String> staticRoots = includeStatics ? staticRequirements(modules, runtimeRoots) : Set.of();
        var roots = new LinkedHashSet<>(runtimeRoots);
        roots.addAll(staticRoots);

        Configuration configuration = Configuration.resolve(
                configurationInputs.before(), configurationInputs.parents(), ModuleFinder.of(), roots);
        var selectedSources = new LinkedHashMap<String, SourceModuleReference>();
        for (ResolvedModule module : configuration.modules()) {
            if (module.reference() instanceof SourceModuleReference source) {
                selectedSources.put(module.name(), source);
            }
        }
        var hashes = resolvedHashes(configuration, selectedSources, configurationInputs.inheritedModules(),
                repositoryModules.observableModules(), integrityMode);
        var sources = new LinkedHashMap<>(repositoryModules.sources());
        if (includeSources) {
            sourceModules.forEach((name, source) -> sources.put(name, source.sourceModule()
                    .sourceDirectory()));
        }
        var moduleSources = new LinkedHashMap<String, Path>();
        sourceModules.forEach((name, source) -> moduleSources.put(name, source.sourceModule()
                .sourceDirectory()));
        return new ModuleResolution(
                observableModules,
                configuration,
                roots,
                staticRoots,
                hashes,
                repositoryModules.versions(),
                systemOverrides,
                moduleSources,
                sources);
    }

    private record ConfigurationInputs(
            ModuleFinder before, List<Configuration> parents, Set<ModuleReference> inheritedModules) {
        private ConfigurationInputs {
            parents = List.copyOf(parents);
            inheritedModules = Set.copyOf(inheritedModules);
        }
    }

    private static ConfigurationInputs configurationInputs(
            ModuleFinder fixedModules,
            ModuleFinder repositoryModules,
            ModuleFinder unresolvedSystemModules,
            Configuration parent,
            Set<String> systemOverrides) {
        ModuleFinder before = finder(modules(fixedModules, repositoryModules, unresolvedSystemModules));
        boolean hasAutomaticModules = before.findAll().stream()
                .anyMatch(reference -> reference.descriptor().isAutomatic());
        if (systemOverrides.isEmpty() || !hasAutomaticModules) {
            return new ConfigurationInputs(before, List.of(parent), Set.of());
        }

        // An automatic module would read both the parent and child versions of an overridden module. Rebase the boot
        // configuration as observable references so finder precedence selects the replacement.
        Set<ModuleReference> inheritedModules = parent.modules().stream()
                .map(ResolvedModule::reference)
                .collect(Collectors.toUnmodifiableSet());
        ModuleFinder inheritedFinder = finder(
                inheritedModules.stream().collect(
                        Collectors.toMap(
                                reference -> reference.descriptor().name(),
                                reference -> reference,
                                (left, right) -> left,
                                LinkedHashMap::new)));
        before = finder(modules(fixedModules, repositoryModules, inheritedFinder, unresolvedSystemModules));
        return new ConfigurationInputs(before, List.of(Configuration.empty()), inheritedModules);
    }

    private record Traversal(String name, boolean requiredForCompilation) {}

    private static Set<String> staticRequirements(Map<String, ModuleReference> modules, Collection<String> roots) {
        Set<String> explicitRoots = Set.copyOf(roots);
        var staticRoots = new LinkedHashSet<String>();
        var visited = new LinkedHashMap<String, Boolean>();
        var queue = new ArrayDeque<Traversal>();
        roots.forEach(name -> queue.addLast(new Traversal(name, false)));
        while (!queue.isEmpty()) {
            var next = queue.removeFirst();
            ModuleReference reference = modules.get(next.name());
            if (reference == null) {
                continue;
            }
            boolean source = reference instanceof SourceModuleReference;
            boolean requiredForCompilation = source || next.requiredForCompilation();
            Boolean previous = visited.get(next.name());
            if (previous != null && (previous || !requiredForCompilation)) {
                continue;
            }
            visited.put(next.name(), requiredForCompilation);
            for (var requirement : reference.descriptor().requires()) {
                if (!modules.containsKey(requirement.name())) {
                    continue;
                }
                var modifiers = requirement.modifiers();
                boolean isStatic = modifiers.contains(Modifier.STATIC);
                boolean isTransitive = modifiers.contains(Modifier.TRANSITIVE);
                if (isStatic && !(source || requiredForCompilation && isTransitive)) {
                    continue;
                }
                if (isStatic && !explicitRoots.contains(requirement.name())) {
                    staticRoots.add(requirement.name());
                }
                queue.addLast(new Traversal(requirement.name(), source || requiredForCompilation && isTransitive));
            }
        }
        return Set.copyOf(staticRoots);
    }

    /** Returns modules selected only when static requirements are resolved. */
    public static Set<String> staticOnlyModules(Configuration configuration, Collection<String> runtimeRoots) {
        var finder = finder(
                configuration.modules().stream()
                        .collect(Collectors.toMap(ResolvedModule::name, ResolvedModule::reference,
                                (left, right) -> left, LinkedHashMap::new)));
        var runtime = Configuration.resolve(finder, configuration.parents(), ModuleFinder.of(), runtimeRoots);
        var runtimeModules = runtime.modules().stream()
                .map(ResolvedModule::name)
                .collect(Collectors.toSet());
        return configuration.modules().stream()
                .map(ResolvedModule::name)
                .filter(name -> !runtimeModules.contains(name))
                .collect(Collectors.toSet());
    }

    private static ModuleDescriptor addedRequiresDeclaration(Map<String, String> addedRequires, Map<String, ModuleReference> available, ModuleFinder systemModules) {
        var builder = ModuleDescriptor.newModule("com.netflix.tools.jig.resolution");
        addedRequires.forEach((moduleName, version) -> {
            if (!available.containsKey(moduleName) || systemModules.find(moduleName).isPresent()) {
                builder.requires(Set.of(), moduleName, Version.parse(version));
            }
        });
        return builder.build();
    }

    private static List<ModuleDescriptor> localRootDeclarations(ModuleFinder fixedModules, Collection<String> roots, boolean includeStatics,
            Map<String, ModuleReference> resolutionModules, ModuleFinder systemModules) {
        Map<String, ModuleReference> available = modules(fixedModules);
        var declarations = new ArrayList<ModuleDescriptor>();
        var visited = new LinkedHashMap<String, Boolean>();
        var queue = new ArrayDeque<Traversal>();
        roots.forEach(name -> queue.addLast(new Traversal(name, false)));
        while (!queue.isEmpty()) {
            var next = queue.removeFirst();
            ModuleReference reference = available.get(next.name());
            if (reference == null) {
                continue;
            }
            boolean source = reference instanceof SourceModuleReference;
            boolean requiredForCompilation = source || next.requiredForCompilation();
            Boolean previous = visited.get(next.name());
            if (previous != null && (previous || !requiredForCompilation)) {
                continue;
            }
            visited.put(next.name(), requiredForCompilation);

            var declaration = reference.descriptor();
            if (declaration.isAutomatic()) {
                continue;
            }
            var repositoryRequirements = declaration.requires().stream()
                    .filter(requirement -> {
                        var modifiers = requirement.modifiers();
                        boolean isStatic = modifiers.contains(Modifier.STATIC);
                        boolean isTransitive = modifiers.contains(Modifier.TRANSITIVE);
                        return !isStatic || includeStatics && (source || requiredForCompilation && isTransitive);
                    })
                    .filter(requirement -> {
                        var name = requirement.name();
                        var explicitSystemOverride = source && requirement.compiledVersion().isPresent() && systemModules.find(name).isPresent();
                        return !resolutionModules.containsKey(name) || explicitSystemOverride;
                    })
                    .toList();
            if (!repositoryRequirements.isEmpty()) {
                var builder = ModuleDescriptor.newModule(declaration.name());
                repositoryRequirements.forEach(builder::requires);
                declarations.add(builder.build());
            }

            for (var requirement : declaration.requires()) {
                var modifiers = requirement.modifiers();
                boolean isStatic = modifiers.contains(Modifier.STATIC);
                boolean isTransitive = modifiers.contains(Modifier.TRANSITIVE);
                if (isStatic && !(includeStatics && (source || requiredForCompilation && isTransitive))) {
                    continue;
                }
                queue.addLast(new Traversal(requirement.name(), source || requiredForCompilation && isTransitive));
            }
        }
        return List.copyOf(declarations);
    }

    private static void validateAddedRequires(Map<String, ModuleReference> available, Map<String, String> addedRequires) {
        addedRequires.forEach((moduleName, requiredVersion) -> {
            ModuleReference reference = available.get(moduleName);
            if (reference == null) {
                return;
            }
            String actualVersion = reference.descriptor()
                    .rawVersion()
                    .orElse(null);
            if (!requiredVersion.equals(actualVersion)) {
                throw new FindException("Module "
                        + moduleName
                        + " requires version "
                        + requiredVersion
                        + " but the fixed module has version "
                        + (actualVersion == null ? "<none>" : actualVersion));
            }
        });
    }

    private static LinkedHashMap<String, SourceModuleReference> selectedSourceModules(Map<String, ModuleReference> available, Collection<String> roots, boolean includeStatics) {
        var result = new LinkedHashMap<String, SourceModuleReference>();
        var queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String name = queue.removeFirst();
            ModuleReference reference = available.get(name);
            if (reference == null) {
                throw new FindException("Module " + name + " not found on module source path or module path");
            }
            if (!(reference instanceof SourceModuleReference source) || result.putIfAbsent(name, source) != null) {
                continue;
            }
            for (var requirement : source.descriptor().requires()) {
                if (requirement.compiledVersion().isPresent()) {
                    continue;
                }
                var isStatic = requirement.modifiers().contains(Modifier.STATIC);
                if (isStatic && !includeStatics) {
                    continue;
                }
                var requiredName = requirement.name();
                if (available.get(requiredName) instanceof SourceModuleReference) {
                    queue.addLast(requiredName);
                }
            }
        }
        return result;
    }

    private static Map<String, ModuleHash> resolvedHashes(
            Configuration configuration,
            Map<String, SourceModuleReference> sourceModules,
            Set<ModuleReference> inheritedModules,
            ModuleFinder repositoryModules,
            IntegrityMode integrityMode) {
        var hashes = new LinkedHashMap<String, ModuleHash>();
        if (integrityMode == IntegrityMode.NONE) {
            return Map.copyOf(hashes);
        }
        var systemModules = ModuleFinder.ofSystem().findAll().stream()
                .map(reference -> reference.descriptor().name())
                .collect(Collectors.toSet());
        try {
            for (ModuleReference module : repositoryModules.findAll()) {
                if (!hashes.containsKey(module.descriptor()
                        .name())) {
                    var hash = module instanceof ModulePathReference repositoryModule ? repositoryModule.hash() : ModuleHash.moduleSha256(module);
                    hashes.put(module.descriptor()
                                     .name(),
                            hash);
                }
            }
            for (ResolvedModule module : configuration.modules()) {
                if (!systemModules.contains(module.name())
                        && !sourceModules.containsKey(module.name())
                        && !inheritedModules.contains(module.reference())
                        && !hashes.containsKey(module.name())) {
                    hashes.put(module.name(), ModuleHash.moduleSha256(module.reference()));
                }
            }
            for (String moduleName : sourceModules.keySet()) {
                var observed = observedHashes(configuration, moduleName, hashes);
                var path = sourceModules.get(moduleName)
                                        .sourceModule()
                                        .sourceDirectory()
                                        .resolve("module-info.hash");
                if (integrityMode == IntegrityMode.VERIFY) {
                    if (!Files.exists(path)) {
                        if (observed.keys().isEmpty()) {
                            continue;
                        }
                        throw new FindException("Module hash file does not exist: " + path);
                    }
                    var expected = ModuleInfoHash.read(path);
                    if (!expected.covers(observed)) {
                        throw new FindException("Resolved module hashes do not match " + path + ":\n  " + String.join("\n  ", expected.differences(observed)));
                    }
                } else {
                    if (observed.keys().isEmpty()) {
                        Files.deleteIfExists(path);
                        continue;
                    }
                    if (Files.exists(path)) {
                        var existing = ModuleInfoHash.read(path);
                        for (Coordinate key : existing.keys()) {
                            var current = observed.hashForKey(key);
                            if (current.isPresent() && !current.equals(existing.hashForKey(key))) {
                                throw new FindException("Resolved module hash does not match existing entry " + key + " in " + path);
                            }
                        }
                    }
                    observed.write(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash resolved modules", e);
        }
        return Map.copyOf(hashes);
    }

    private static ModuleInfoHash observedHashes(Configuration configuration, String sourceModule,
            Map<String, ModuleHash> hashes) {
        var builder = ModuleInfoHash.newBuilder();
        var source = configuration.findModule(sourceModule).orElseThrow();
        source.reads().stream()
                .sorted(Comparator.comparing(ResolvedModule::name))
                .forEach(readable -> addObserved(sourceModule, readable, hashes, builder));
        return builder.build();
    }

    private static void addObserved(String sourceModule, ResolvedModule dependency,
            Map<String, ModuleHash> hashes, Builder builder) {
        String name = dependency.name();
        if (name.equals(sourceModule)) {
            return;
        }
        ModuleHash hash = hashes.get(name);
        if (hash != null) {
            builder.put(new Coordinate(name, dependency.reference().descriptor().version()), hash);
        }
    }

    private static LinkedHashMap<String, ModuleReference> modules(ModuleFinder... finders) {
        var modules = new LinkedHashMap<String, ModuleReference>();
        for (ModuleFinder finder : finders) {
            for (ModuleReference reference : finder.findAll()) {
                modules.putIfAbsent(reference.descriptor()
                        .name(),
                        reference);
            }
        }
        return modules;
    }

    private static ModuleFinder finder(Map<String, ModuleReference> modules) {
        Map<String, ModuleReference> references = Map.copyOf(modules);
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(references.get(name));
            }

            @Override
            public Set<ModuleReference> findAll() {
                return new LinkedHashSet<>(references.values());
            }
        };
    }
}
