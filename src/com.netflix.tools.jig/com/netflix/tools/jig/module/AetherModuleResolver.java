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
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.module.ModuleHash;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.collection.CollectRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.collection.DependencyCollectionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.graph.Dependency;
import com.netflix.tools.jig.internal.org.eclipse.aether.graph.DependencyNode;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Collects canonical module artifacts with Maven Resolver and exposes its selected artifacts as module references.
 * JPMS, not this class, determines which of those observable modules are resolved.
 */
public final class AetherModuleResolver {

    @FunctionalInterface
    public interface ModuleDescriptorResolver {
        ModuleDescriptor resolve(Artifact artifact) throws IOException;
    }

    public record Result(ModuleFinder observableModules, SequencedSet<String> automaticModuleRoots,
                         Map<String, ModuleHash> hashes, Map<String, String> versions, Map<String, Path> sources) {
        public Result {
            automaticModuleRoots = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(automaticModuleRoots));
            hashes = Map.copyOf(hashes);
            versions = Map.copyOf(versions);
            sources = Map.copyOf(sources);
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
        return resolve(roots, includeStatics, false);
    }

    public Result resolve(Collection<ModuleDescriptor> roots, boolean includeStatics, boolean includeSources) {
        try {
            var request = new CollectRequest(rootDependencies(roots, includeStatics), List.of(), repositories);
            if (session.getScopeManager() != null) {
                session.getScopeManager()
                        .getResolutionScope("runtime")
                        .ifPresent(request::setResolutionScope);
            }
            DependencyNode graph = system.collectDependencies(session, request).getRoot();
            var selectedNodes = system.flattenDependencyNodes(session, graph, null);
            var artifacts = selectedArtifacts(selectedNodes);
            var references = references(artifacts);
            var automaticModuleRoots = automaticModuleRoots(selectedNodes, references);

            if (Trace.isEnabled()) {
                artifacts.forEach((name, artifact) -> {
                    var reference = references.get(name);
                    Trace.trace("selected %s@%s%s (metadata)", name, artifact.getVersion(),
                            reference.descriptor().isAutomatic() ? " automatic" : "");
                });
            }

            var versions = new LinkedHashMap<String, String>();
            artifacts.forEach((name, artifact) -> versions.put(name, artifact.getVersion()));
            var sources = new LinkedHashMap<String, Path>();
            if (includeSources) {
                artifacts.forEach((name, artifact) -> resolveSources(artifact).ifPresent(path -> sources.put(name, path)));
            }
            return new Result(finder(references), automaticModuleRoots, Map.of(), versions, sources);
        } catch (DependencyCollectionException | IOException e) {
            throw new FindException("Failed to resolve modules", e);
        }
    }

    /** Converts the root descriptors' requires directives into direct dependencies for Aether collection. */
    private static List<Dependency> rootDependencies(Collection<ModuleDescriptor> roots, boolean includeStatics) {
        var declaredVersions = new LinkedHashMap<String, String>();
        for (var root : roots) {
            for (var requirement : root.requires()) {
                requirement.compiledVersion().ifPresent(version -> declaredVersions.putIfAbsent(requirement.name(), version.toString()));
            }
        }
        var dependencies = new ArrayList<Dependency>();
        for (var root : roots) {
            for (var requirement : root.requires()) {
                var name = requirement.name();
                var systemModule = ModuleFinder.ofSystem().find(name).isPresent();
                if (systemModule && requirement.compiledVersion().isEmpty()) {
                    continue;
                }
                var staticPhase = requirement.modifiers().contains(Modifier.STATIC);
                if (staticPhase && !includeStatics) {
                    continue;
                }
                var version = requirement.compiledVersion()
                        .map(Object::toString)
                        .orElseGet(() -> declaredVersions.get(name));
                if (version == null) {
                    throw new FindException("No version declared for module " + name);
                }
                dependencies.add(new Dependency(canonical(name, version), "compile"));
            }
        }
        return List.copyOf(dependencies);
    }

    private static LinkedHashMap<String, Artifact> selectedArtifacts(List<DependencyNode> selectedNodes) {
        var artifacts = new LinkedHashMap<String, Artifact>();
        for (var node : selectedNodes) {
            var dependency = node.getDependency();
            if (dependency == null) {
                continue;
            }
            Artifact artifact = dependency.getArtifact();
            Artifact previous = artifacts.putIfAbsent(artifact.getArtifactId(), artifact);
            if (previous != null && !previous.equals(artifact)) {
                throw new FindException("Multiple artifacts selected for module " + artifact.getArtifactId()
                        + ": " + previous + " and " + artifact);
            }
        }
        return artifacts;
    }

    private LinkedHashMap<String, ModuleReference> references(Map<String, Artifact> artifacts) throws IOException {
        var references = new LinkedHashMap<String, ModuleReference>();
        for (var entry : artifacts.entrySet()) {
            String name = entry.getKey();
            Artifact artifact = entry.getValue();
            ModuleDescriptor descriptor = descriptors.resolve(artifact);
            if (!descriptor.name().equals(name)) {
                throw new IOException("Artifact " + artifact + " provides module " + descriptor.name() + ", not " + name);
            }
            references.put(name, new ArtifactModuleReference(descriptor, artifact, system, session, repositories));
        }
        return references;
    }

    /**
     * An automatic module has no requires directives. Its selected explicit dependencies must therefore be roots for
     * JPMS to resolve them; selected automatic dependencies are resolved by JPMS's automatic-module rules.
     */
    private static SequencedSet<String> automaticModuleRoots(List<DependencyNode> selectedNodes,
            Map<String, ModuleReference> references) {
        var roots = new LinkedHashSet<String>();
        for (var node : selectedNodes) {
            var dependency = node.getDependency();
            if (dependency == null) {
                continue;
            }
            String moduleName = dependency.getArtifact().getArtifactId();
            var reference = references.get(moduleName);
            if (reference == null || !reference.descriptor().isAutomatic()) {
                continue;
            }
            for (var child : node.getChildren()) {
                var childDependency = child.getDependency();
                if (childDependency == null) {
                    continue;
                }
                String dependencyName = childDependency.getArtifact().getArtifactId();
                var dependencyReference = references.get(dependencyName);
                if (dependencyReference != null && !dependencyReference.descriptor().isAutomatic()
                        && roots.add(dependencyName)) {
                    Trace.trace("automatic module root %s (dependency of %s)", dependencyName, moduleName);
                }
            }
        }
        return roots;
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
