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

package com.netflix.tools.jig.module.maven.transport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.module.ArtifactCandidates;

/**
 * Serves Maven relocation POMs for module location coordinates.
 *
 * <p>Location coordinates use the module name as the artifactId and
 * the module namespace as the groupId
 * (e.g. {@code com.fasterxml:com.fasterxml.jackson.databind}).
 * The caller requests a POM at the exact module version; the transport
 * serves a {@code <distributionManagement><relocation>} POM that
 * redirects to the real artifact coordinates.
 *
 * <p>Resolution order is handled by {@link AbstractLocationTransporter}:
 * local cache, upstream author-published relocations, then discovery.
 *
 * <p>The discovery algorithm maps module names to actual coordinates:
 * <ol>
 *   <li>Previously discovered identity from descriptor probing</li>
 *   <li>Module convention — artifactId equals the full module name.
 *       Unambiguous identity match, no jar verification needed</li>
 *   <li>BOM alias table lookup</li>
 *   <li>BOM discovery — {@code <namespace>:bom}, {@code <namespace>:<last>-bom}</li>
 *   <li>Artifact candidate permutations with jar verification</li>
 * </ol>
 */
public final class ModuleLocationTransporter extends AbstractLocationTransporter {

    private static final String OBSERVED_LOCATIONS_KEY = "jig.module.observed.locations";
    private static final String DISCOVERY_CACHE_KEY = "jig.module.location.discovery";

    private final List<RemoteRepository> bomRepositories;

    public ModuleLocationTransporter(RepositorySystem system, RepositorySystemSession backendSession, RepositorySystemSession cacheSession,
            List<RemoteRepository> repositories, List<RemoteRepository> bomRepositories) {
        super(system, backendSession, cacheSession, repositories);
        this.bomRepositories = bomRepositories;
    }

    @Override
    protected boolean isOwnCoordinate(String groupId, String artifactId) {
        return groupId.equals(ArtifactCandidates.moduleLocationCoordinate(artifactId, null)
                .getGroupId());
    }

    @Override
    protected Artifact upstreamCoordinate(Artifact requested) {
        return ArtifactCandidates.locationCoordinate(requested.getArtifactId(), requested.getVersion());
    }

    @SuppressWarnings("unchecked")
    static void registerObservedLocation(RepositorySystemSession session, String moduleName, String version,
            Artifact artifact) {
        var locations = (Map<String, Artifact>) session.getData().computeIfAbsent(OBSERVED_LOCATIONS_KEY, ConcurrentHashMap::new);
        locations.putIfAbsent(moduleName + "@" + version, artifact);
    }

    @SuppressWarnings("unchecked")
    private Artifact observedLocation(String moduleName, String version) {
        var locations = (Map<String, Artifact>) session.getData().get(OBSERVED_LOCATIONS_KEY);
        return locations == null ? null : locations.get(moduleName + "@" + version);
    }

    private record DiscoveryKey(String moduleName, String version) {}

    @SuppressWarnings("unchecked")
    private Map<DiscoveryKey, Optional<Artifact>> discoveryCache() {
        return (Map<DiscoveryKey, Optional<Artifact>>) session.getData().computeIfAbsent(DISCOVERY_CACHE_KEY, ConcurrentHashMap::new);
    }

    @Override
    protected Artifact discover(Artifact requested) throws IOException {
        String moduleName = requested.getArtifactId();
        String version = requested.getVersion();
        Artifact observed = observedLocation(moduleName, version);
        if (observed != null) {
            trace("locate module %s -> %s:%s (observed)", moduleName,
                    observed.getGroupId(), observed.getArtifactId());
            return observed;
        }
        var cacheKey = new DiscoveryKey(moduleName, version);
        try {
            return discoveryCache().computeIfAbsent(cacheKey, ignored -> {
                try {
                    return discoverModule(moduleName, version);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })
                                   .orElse(null);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private final Map<String, Map<String, Artifact>> bomModuleNames = new ConcurrentHashMap<>();

    private Optional<Artifact> discoverModule(String moduleName, String version) throws IOException {
        if (AbstractLocationTransporter.LOCATION_SENTINEL_VERSION.equals(version) || isRangeVersion(version)) {
            version = ALL_VERSIONS;
        }
        var explicitAlias = ArtifactCandidates.of(moduleName, version).stream()
                .filter(candidate -> ArtifactCandidates.moduleNameForArtifact(candidate.getGroupId(), candidate.getArtifactId())
                        .filter(moduleName::equals)
                        .isPresent())
                .findFirst();
        if (explicitAlias.isPresent()) {
            Artifact target = explicitAlias.get();
            trace("locate module %s -> %s:%s (explicit alias)", moduleName,
                    target.getGroupId(), target.getArtifactId());
            return explicitAlias;
        }
        // Check BOM module name cache from prior batch probes
        for (var moduleMap : bomModuleNames.values()) {
            Artifact cached = moduleMap.get(moduleName);
            if (cached != null) {
                trace("locate module %s -> %s:%s (bom cache)", moduleName,
                        cached.getGroupId(), cached.getArtifactId());
                return Optional.of(cached);
            }
        }

        // Module convention at namespace root (OSGi): dotted artifactId
        var conventionCandidates = ArtifactCandidates.moduleCandidates(moduleName, version);
        if (!conventionCandidates.isEmpty()) {
            var convention = conventionCandidates.getFirst();
            var result = locateCandidate(convention, moduleName);
            if (result.isPresent()) {
                trace("locate module %s -> %s:%s (convention)", moduleName,
                        convention.getGroupId(), convention.getArtifactId());
                return result;
            }
        }

        // Hyphenated candidates interleaved with BOMs at each namespace depth
        var allCandidates = ArtifactCandidates.of(moduleName, version);
        String lastGroupId = null;

        for (var candidate : allCandidates) {
            if (lastGroupId != null && !candidate.getGroupId().equals(lastGroupId)) {
                var bomResult = checkBomsAtNamespace(lastGroupId, moduleName, version);
                if (bomResult.isPresent()) {
                    return bomResult;
                }
            }
            lastGroupId = candidate.getGroupId();

            var result = locateCandidate(candidate, moduleName);
            if (result.isPresent()) {
                trace("locate module %s -> %s:%s (candidate)", moduleName,
                        candidate.getGroupId(), candidate.getArtifactId());
                return result;
            }
        }

        if (lastGroupId != null) {
            var bomResult = checkBomsAtNamespace(lastGroupId, moduleName, version);
            if (bomResult.isPresent()) {
                return bomResult;
            }
        }

        trace("locate module %s -> not found", moduleName);
        return Optional.empty();
    }

    /**
     * Minimum similarity score for a BOM entry to be worth probing.
     * Entries below this threshold are skipped entirely. With
     * Levenshtein-based scoring, 0.5 means the identity portion must
     * be at least 50% similar to the target.
     */
    private static final double BOM_SIMILARITY_THRESHOLD = 0.5;

    /**
     * Checks BOMs at a namespace level. Entries are sorted by similarity
     * to the target module name and only those above a minimum threshold
     * are probed. On match the result is cached; subsequent lookups for
     * sibling modules check the cache first.
     */
    private Optional<Artifact> checkBomsAtNamespace(String namespace, String moduleName, String version) throws IOException {
        String moduleNamespace = ArtifactCandidates.moduleNamespace(moduleName);
        String[] targetParts = moduleName.split("\\.");

        for (var bomCandidate : ArtifactCandidates.bomCandidatesForNamespace(namespace, version)) {
            String bomKey = bomCandidate.getGroupId() + ":" + bomCandidate.getArtifactId();

            var moduleMap = bomModuleNames.get(bomKey);
            if (moduleMap != null) {
                Artifact cached = moduleMap.get(moduleName);
                if (cached != null) {
                    trace("locate module %s -> %s:%s (bom cache %s)", moduleName,
                            cached.getGroupId(), cached.getArtifactId(), bomKey);
                    return Optional.of(cached);
                }
                continue;
            }

            List<Artifact> entries = fetchBomEntries(bomCandidate);
            if (entries.isEmpty()) {
                bomModuleNames.put(bomKey, new ConcurrentHashMap<>());
                continue;
            }

            trace("locate module %s batch-probing bom %s", moduleName, bomKey);

            var candidates = entries.stream()
                    .filter(entry -> entry.getGroupId().equals(moduleNamespace) || entry.getGroupId().startsWith(moduleNamespace + "."))
                    .filter(entry -> ArtifactCandidates.bomSimilarity(targetParts, entry) >= BOM_SIMILARITY_THRESHOLD)
                    .sorted(ArtifactCandidates.bomSimilarityOrder(moduleName))
                    .toList();

            var probeMap = bomModuleNames.computeIfAbsent(bomKey, k -> new ConcurrentHashMap<>());
            for (var entry : candidates) {
                try {
                    moduleNameFor(entry).ifPresent(name -> probeMap.put(name, entry));
                } catch (ArtifactNotFoundException | IOException e) {
                    // skip entries we can't probe
                }
                Artifact found = probeMap.get(moduleName);
                if (found != null) {
                    trace("locate module %s -> %s:%s (bom %s)", moduleName,
                            found.getGroupId(), found.getArtifactId(), bomKey);
                    return Optional.of(found);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Artifact> locateCandidate(Artifact candidate, String moduleName) throws IOException {
        if (verifyModuleName(candidate, moduleName)) {
            return Optional.of(candidate);
        }
        for (var predecessor : ArtifactCandidates.predecessors(candidate)) {
            if (verifyModuleName(predecessor, moduleName)) {
                return Optional.of(predecessor);
            }
        }
        return Optional.empty();
    }

    private boolean verifyModuleName(Artifact artifact, String expectedModuleName) throws IOException {
        Artifact toProbe = resolveConcreteVersion(artifact);
        if (toProbe == null) {
            return false;
        }
        try {
            return moduleNameFor(toProbe).filter(expectedModuleName::equals).isPresent();
        } catch (ArtifactNotFoundException e) {
            return false;
        }
    }

    private static final String ALL_VERSIONS = "[0,)";
}
