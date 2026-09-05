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
import java.util.SequencedSet;
import java.util.concurrent.ConcurrentHashMap;

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.version.Version;
import com.netflix.tools.jig.module.ArtifactCandidates;

/**
 * Serves Maven relocation POMs for BOM location coordinates.
 *
 * <p>BOM location coordinates use the namespace level as the artifactId
 * and {@code <namespace>.module.bom} as the groupId (e.g.
 * {@code org.springframework.module.bom:org.springframework.boot}).
 * The transport discovers which BOM artifact covers a given namespace
 * and caches the mapping as a relocation POM per-version.
 *
 * <p>This is purely internal — no author-facing contract. The module
 * location transport queries BOM locations during discovery to avoid
 * expensive candidate probing.
 */
public final class BomLocationTransporter extends AbstractLocationTransporter {

    private final Map<String, Optional<Artifact>> discoveryCache = new ConcurrentHashMap<>();

    public BomLocationTransporter(RepositorySystem system, RepositorySystemSession backendSession, RepositorySystemSession cacheSession,
            List<RemoteRepository> repositories) {
        super(system, backendSession, cacheSession, repositories);
    }

    @Override
    protected boolean isOwnCoordinate(String groupId, String artifactId) {
        try {
            return groupId.equals(ArtifactCandidates.bomLocationCoordinate(artifactId, null)
                    .getGroupId());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    protected Artifact discover(Artifact requested) throws IOException {
        String namespace = requested.getArtifactId();
        Optional<Artifact> result;
        try {
            result = discoveryCache.computeIfAbsent(namespace, ns -> {
                try {
                    return discoverBom(ns);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return result.orElse(null);
    }

    private Optional<Artifact> discoverBom(String namespace) throws IOException {
        Optional<Artifact> bomAlias = ArtifactCandidates.bomAlias(namespace);
        if (bomAlias.isPresent()) {
            var bom = bomAlias.get();
            SequencedSet<Version> versions = resolveVersionRange(bom);
            if (!versions.isEmpty()) {
                trace("locate bom %s -> %s:%s (alias)", namespace,
                        bom.getGroupId(), bom.getArtifactId());
                return Optional.of(bom);
            }
        }

        var candidates = ArtifactCandidates.bomCandidatesForNamespace(namespace, null);
        for (var candidate : candidates) {
            trace("locate bom %s trying %s:%s", namespace,
                    candidate.getGroupId(), candidate.getArtifactId());
            SequencedSet<Version> versions = resolveVersionRange(candidate);
            if (!versions.isEmpty()) {
                trace("locate bom %s -> %s:%s", namespace, candidate.getGroupId(), candidate.getArtifactId());
                return Optional.of(candidate);
            }
        }

        trace("locate bom %s -> not found", namespace);
        return Optional.empty();
    }
}
