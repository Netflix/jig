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
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.module.ModuleHash;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;
import com.netflix.tools.jig.module.ModuleGraph.Module;
import com.netflix.tools.jig.module.ModuleGraph.Requirement;

/** Resolves exact modules through the module repository. */
public final class AetherModuleResolver {

    private static final Set<String> RUNTIME_SCOPES = Set.of("compile", "runtime");

    @FunctionalInterface
    public interface ModuleDescriptorResolver {
        ModuleDescriptor resolve(Artifact artifact) throws IOException;
    }

    public record Result(ModuleFinder finder, SequencedSet<String> supplementalRoots, Map<String, ModuleHash> hashes,
                         Map<String, String> versions, Map<String, Path> sources, Map<String, Set<String>> dependencies) {
        public Result(ModuleFinder finder, SequencedSet<String> supplementalRoots, Map<String, ModuleHash> hashes,
                      Map<String, String> versions, Map<String, Path> sources) {
            this(finder, supplementalRoots, hashes, versions, sources,
                    Map.of());
        }

        public Result {
            supplementalRoots = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(supplementalRoots));
            hashes = Map.copyOf(hashes);
            versions = Map.copyOf(versions);
            sources = Map.copyOf(sources);
            dependencies = dependencies.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }

    private static final class ArtifactModuleReference extends ModuleReference implements ModulePathReference {
        private record Resolved(ModuleReference reference, Path artifact) {}

        private final Artifact artifact;
        private final RepositorySystem system;
        private final RepositorySystemSession session;
        private final List<RemoteRepository> repositories;
        private volatile Resolved resolved;
        private volatile ModuleHash moduleHash;

        private ArtifactModuleReference(ModuleDescriptor descriptor, Artifact artifact, RepositorySystem system,
                RepositorySystemSession session, List<RemoteRepository> repositories) {
            super(descriptor, null);
            this.artifact = artifact;
            this.system = system;
            this.session = session;
            this.repositories = List.copyOf(repositories);
        }

        @Override
        public ModuleHash hash() throws IOException {
            var current = moduleHash;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = moduleHash;
                if (current != null) {
                    return current;
                }
                var artifactHash = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), "jar.hash",
                        artifact.getVersion());
                try {
                    var result = system.resolveArtifact(session, new ArtifactRequest(artifactHash, repositories, null));
                    var path = result.getArtifact().getPath();
                    if (path == null) {
                        throw new IOException("Resolved module hash has no path: " + artifactHash);
                    }
                    current = ModuleHash.read(path);
                    moduleHash = current;
                    return current;
                } catch (ArtifactResolutionException e) {
                    throw new IOException("Failed to resolve module hash " + artifactHash, e);
                }
            }
        }

        @Override
        public Path modulePath() throws IOException {
            return resolved().artifact();
        }

        @Override
        public ModuleReader open() throws IOException {
            return resolved().reference().open();
        }

        private Resolved resolved() throws IOException {
            Resolved current = resolved;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = resolved;
                if (current != null) {
                    return current;
                }
                try {
                    var result = system.resolveArtifact(session, new ArtifactRequest(artifact, repositories, null));
                    Path path = result.getArtifact().getPath();
                    if (path == null) {
                        throw new IOException("Resolved artifact has no path: " + artifact);
                    }
                    var reference = ModuleFinder.of(path)
                            .find(descriptor().name())
                            .orElseThrow(() -> new IOException("Artifact " + artifact + " does not provide module " + descriptor().name()));
                    current = new Resolved(reference, path);
                    resolved = current;
                    return current;
                } catch (ArtifactResolutionException e) {
                    throw new IOException("Failed to resolve module " + artifact, e);
                }
            }
        }
    }

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final ModuleDescriptorResolver descriptors;

    public AetherModuleResolver(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repositories,
            ModuleDescriptorResolver descriptors) {
        this.system = system;
        this.session = session;
        this.repositories = List.copyOf(repositories);
        this.descriptors = descriptors;
    }

    public Result resolve(Collection<ModuleDescriptor> roots, boolean includeStatics) {
        return resolve(roots, includeStatics, Set.of(), false);
    }

    public Result resolve(Collection<ModuleDescriptor> roots, boolean includeStatics, Set<String> fixedModules) {
        return resolve(roots, includeStatics, fixedModules, false);
    }

    public Result resolve(Collection<ModuleDescriptor> roots, boolean includeStatics, Set<String> fixedModules,
                          boolean includeSources) {
        try {
            var graph = ModuleGraph.resolve(rootRequirements(roots, fixedModules), includeStatics, this::load);
            var references = new LinkedHashMap<String, ModuleReference>();
            var modules = new LinkedHashMap<String, Module>();
            graph.forEach(module -> {
                references.put(module.name(), module.reference());
                modules.put(module.name(), module);
            });
            var supplementalRoots = supplementalRoots(graph, references);
            var dependencies = automaticDependencies(graph);
            Set<String> folded = foldContainedAutomaticDependencies(rootNames(roots), references);
            folded.forEach(references::remove);

            if (Trace.isEnabled()) {
                graph.stream()
                        .filter(module -> references.containsKey(module.name()))
                        .forEach(
                                module -> Trace.trace("selected %s@%s%s (metadata)", module.name(), module.version(),
                                        module.reference()
                                              .descriptor()
                                              .isAutomatic()
                                                ? " automatic" : ""));
            }

            var versions = new LinkedHashMap<String, String>();
            references.keySet().forEach(name -> versions.put(name, modules.get(name)
                    .version()));
            var sources = new LinkedHashMap<String, Path>();
            if (includeSources) {
                references.keySet().forEach(name -> resolveSources(canonical(name, versions.get(name))).ifPresent(path -> sources.put(name, path)));
            }
            return new Result(finder(references), supplementalRoots, Map.of(), versions,
                    sources, dependencies);
        } catch (IOException e) {
            throw new FindException("Failed to resolve modules", e);
        }
    }

    private Module load(String name, String version) throws IOException {
        Artifact artifact = canonical(name, version);
        ModuleDescriptor descriptor = descriptors.resolve(artifact);
        List<Requirement> requirements = requirements(artifact, descriptor);
        if (!descriptor.name().equals(name)) {
            throw new IOException("Artifact "
                    + artifact
                    + " provides module "
                    + descriptor.name()
                    + ", not "
                    + name);
        }
        var reference = new ArtifactModuleReference(descriptor, artifact, system, session, repositories);
        return new Module(name, version, reference, requirements);
    }

    private List<Requirement> requirements(Artifact artifact, ModuleDescriptor descriptor) throws IOException {
        var staticRequirements = descriptor.requires().stream()
                .filter(requirement -> requirement.modifiers().contains(Modifier.STATIC))
                .map(Requires::name)
                .collect(Collectors.toSet());
        try {
            var request = new ArtifactDescriptorRequest(artifact, repositories, null);
            return system.readArtifactDescriptor(session, request).getDependencies().stream()
                    .filter(dependency -> RUNTIME_SCOPES.contains(dependency.getScope()))
                    .map(
                            dependency ->
                            new Requirement(
                                    dependency.getArtifact().getArtifactId(),
                                    dependency.getArtifact().getVersion(),
                                    dependency.isOptional() || staticRequirements.contains(dependency.getArtifact().getArtifactId()),
                                    dependency.getExclusions().stream()
                                            .noneMatch(exclusion -> exclusion.getGroupId().equals("*") && exclusion.getArtifactId().equals("*"))))
                    .toList();
        } catch (ArtifactDescriptorException e) {
            throw new IOException("Failed to read module requirements for " + artifact, e);
        }
    }

    private static List<Requirement> rootRequirements(Collection<ModuleDescriptor> roots, Set<String> fixedModules) {
        var declaredVersions = new LinkedHashMap<String, String>();
        for (var root : roots) {
            for (var requirement : root.requires()) {
                requirement.compiledVersion().ifPresent(version -> declaredVersions.putIfAbsent(requirement.name(), version.toString()));
            }
        }
        var requirements = new ArrayList<Requirement>();
        for (var root : roots) {
            for (var requirement : root.requires()) {
                var name = requirement.name();
                var systemModule = ModuleFinder.ofSystem()
                        .find(name)
                        .isPresent();
                if (systemModule && requirement.compiledVersion().isEmpty()
                        || !systemModule && fixedModules.contains(name)) {
                    continue;
                }
                var version = requirement.compiledVersion()
                        .map(Object::toString)
                        .orElseGet(() -> declaredVersions.get(name));
                if (version == null) {
                    throw new FindException("No version declared for module " + name);
                }
                var staticPhase = requirement.modifiers().contains(Modifier.STATIC);
                requirements.add(new Requirement(name, version, staticPhase));
            }
        }
        return List.copyOf(requirements);
    }

    private static Set<String> rootNames(Collection<ModuleDescriptor> roots) {
        var names = new LinkedHashSet<String>();
        roots.forEach(root -> root.requires().forEach(requirement -> names.add(requirement.name())));
        return names;
    }

    private static SequencedSet<String> supplementalRoots(List<Module> modules, Map<String, ModuleReference> references) {
        var roots = new LinkedHashSet<String>();
        for (var module : modules) {
            if (!module.reference()
                       .descriptor()
                       .isAutomatic()) {
                continue;
            }
            for (var requirement : module.requirements()) {
                var dependency = references.get(requirement.name());
                if (dependency != null && !dependency.descriptor().isAutomatic() && roots.add(requirement.name())) {
                    Trace.trace("supplemental root %s " + "(explicit module dependency of automatic module %s)",
                            requirement.name(), module.name());
                }
            }
        }
        return roots;
    }

    private static Map<String, Set<String>> automaticDependencies(List<Module> modules) {
        var dependencies = new LinkedHashMap<String, Set<String>>();
        for (var module : modules) {
            if (!module.reference()
                       .descriptor()
                       .isAutomatic()) {
                continue;
            }
            var names = module.requirements().stream()
                    .map(Requirement::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!names.isEmpty()) {
                dependencies.put(module.name(), names);
            }
        }
        return dependencies;
    }

    private static Set<String> foldContainedAutomaticDependencies(Set<String> rootModules, Map<String, ModuleReference> references) {
        var folded = new LinkedHashSet<String>();
        for (String rootName : rootModules) {
            ModuleReference root = references.get(rootName);
            if (root == null || !root.descriptor().isAutomatic()) {
                continue;
            }
            Set<String> rootPackages = root.descriptor().packages();
            for (var entry : references.entrySet()) {
                String dependencyName = entry.getKey();
                if (rootModules.contains(dependencyName)) {
                    continue;
                }
                var dependency = entry.getValue().descriptor();
                if (!dependency.isAutomatic() || dependency.packages().isEmpty()) {
                    continue;
                }
                if (rootPackages.containsAll(dependency.packages()) && folded.add(dependencyName)) {
                    Trace.trace("fold %s into automatic module %s", dependencyName, rootName);
                }
            }
        }
        return Set.copyOf(folded);
    }

    private static ModuleFinder finder(Map<String, ModuleReference> references) {
        var copy = Map.copyOf(references);
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(copy.get(name));
            }

            @Override
            public Set<ModuleReference> findAll() {
                return new LinkedHashSet<>(copy.values());
            }
        };
    }

    private static Artifact canonical(String name, String version) {
        Artifact coordinate = ArtifactCandidates.locationCoordinate(name, version);
        return new DefaultArtifact(coordinate.getGroupId(), coordinate.getArtifactId(), "jar", version);
    }

    private Optional<Path> resolveSources(Artifact artifact) {
        var sourcesArtifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), "sources", "jar",
                artifact.getVersion());
        try {
            var result = system.resolveArtifact(session, new ArtifactRequest(sourcesArtifact, repositories, null));
            return Optional.ofNullable(result.getArtifact()
                    .getPath());
        } catch (ArtifactResolutionException e) {
            if (e.getResult() != null && e.getResult().isMissing()) {
                return Optional.empty();
            }
            throw new FindException("Failed to resolve sources for " + artifact, e);
        }
    }
}
