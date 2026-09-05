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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import com.netflix.tools.jig.internal.org.apache.maven.model.DistributionManagement;
import com.netflix.tools.jig.internal.org.apache.maven.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.Relocation;
import com.netflix.tools.jig.internal.org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import com.netflix.tools.jig.internal.org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.PeekTask;

/**
 * Base class for location transporters ({@code jig+location-module} and
 * {@code jig+location-bom}). Provides the common resolution pattern:
 *
 * <ol>
 *   <li><b>Local repo fast path</b> — scan the local repository for a
 *       previously cached relocation POM at any version up to the
 *       requested version. If found, reuse the relocation target
 *       without any remote requests.</li>
 *   <li><b>Canonical coordinate check</b> — range request to upstream
 *       repos for the canonical coordinate.</li>
 *   <li><b>Discovery</b> — subclass-specific logic to discover the
 *       relocation target.</li>
 * </ol>
 *
 * <p>Subclasses implement {@link #isOwnCoordinate} to identify their
 * coordinate space and {@link #discover} for strategy-specific lookup.
 */
abstract class AbstractLocationTransporter extends AbstractModuleTransporter {

    /**
     * Version advertised in location metadata. The relocation target
     * is version-agnostic, so the value is arbitrary.
     */
    static final String LOCATION_SENTINEL_VERSION = "0";

    private final Path localRepoBasedir;

    protected AbstractLocationTransporter(RepositorySystem system, RepositorySystemSession backendSession, RepositorySystemSession cacheSession,
            List<RemoteRepository> repositories) {
        super(system, backendSession, repositories);
        this.localRepoBasedir = cacheSession.getLocalRepository()
                .getBasedir()
                .toPath();
    }

    /**
     * Returns whether the given groupId and artifactId belong to this
     * transport's coordinate space.
     */
    protected abstract boolean isOwnCoordinate(String groupId, String artifactId);

    /** Returns the author-facing coordinate to check upstream. */
    protected Artifact upstreamCoordinate(Artifact requested) {
        return requested;
    }

    /**
     * Discovers the relocation target for the given artifact. Called only
     * when neither local cache nor upstream author-published relocations
     * provide a result.
     *
     * @return the target artifact (groupId and artifactId), or {@code null}
     *         if discovery fails
     */
    protected abstract Artifact discover(Artifact requested) throws IOException;

    @Override
    protected void implPeek(PeekTask task) throws Exception {
        Artifact pom = parsePomPath(task.getLocation());
        if (pom != null && isOwnCoordinate(pom.getGroupId(), pom.getArtifactId())) {
            return;
        }
        Artifact metadata = parseMetadataPath(task.getLocation());
        if (metadata != null && isOwnCoordinate(metadata.getGroupId(), metadata.getArtifactId())) {
            return;
        }
        throw new FileNotFoundException(task.getLocation()
                .toString());
    }

    @Override
    protected void implGet(GetTask task) throws Exception {
        Artifact metadata = parseMetadataPath(task.getLocation());
        if (metadata != null && isOwnCoordinate(metadata.getGroupId(), metadata.getArtifactId())) {
            serveMetadata(task, metadata);
            return;
        }

        Artifact requested = parsePomPath(task.getLocation());
        if (requested == null || !isOwnCoordinate(requested.getGroupId(), requested.getArtifactId())) {
            throw new FileNotFoundException(task.getLocation()
                    .toString());
        }

        Artifact target = TransportTrace.withContext("locate " + requested.getArtifactId(), () -> resolve(requested));
        if (target == null) {
            throw moduleNotFound(requested);
        }

        byte[] pomBytes = buildRelocationPom(requested.getGroupId(), requested.getArtifactId(), requested.getVersion(),
                target.getGroupId(), target.getArtifactId());
        writeResponse(task, pomBytes);
    }

    private FileNotFoundException moduleNotFound(Artifact requested) {
        String configuredRepositories = String.join(", ",
                repositories.stream()
                        .map(repository -> repository.getId() + " (" + repository.getUrl() + ")")
                        .toList());
        if (configuredRepositories.isEmpty()) {
            configuredRepositories = "none configured";
        }
        return new FileNotFoundException("Module "
                + requested.getArtifactId()
                + "@"
                + requested.getVersion()
                + " was not found in repositories: "
                + configuredRepositories);
    }

    /**
     * Resolves the relocation target for a location coordinate, trying
     * local cache, upstream author-published relocations, then discovery.
     */
    private Artifact resolve(Artifact requested) throws IOException {
        String moduleName = requested.getArtifactId();
        String requestedVersion = requested.getVersion();

        // 1. Local repo fast path
        Artifact local = findCachedRelocation(requested, requestedVersion);
        if (local != null) {
            trace("locate %s -> %s:%s (cached)", moduleName,
                    local.getGroupId(), local.getArtifactId());
            return local;
        }

        // 2. Canonical coordinate on upstream repos
        Artifact canonical = findUpstreamRelocation(requested, requestedVersion);
        if (canonical != null) {
            trace("locate %s -> %s:%s (canonical)", moduleName,
                    canonical.getGroupId(), canonical.getArtifactId());
            return canonical;
        }

        // 3. Subclass-specific discovery
        Artifact discovered = discover(requested);
        if (discovered != null) {
            trace("locate %s -> %s:%s", moduleName, discovered.getGroupId(), discovered.getArtifactId());
        }
        return discovered;
    }

    /**
     * Serves synthetic {@code maven-metadata.xml} for version range resolution.
     */
    private void serveMetadata(GetTask task, Artifact metadata) throws Exception {
        String
                xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%2$s</groupId>
                  <artifactId>%3$s</artifactId>
                  <versioning>
                    <latest>%1$s</latest>
                    <release>%1$s</release>
                    <versions>
                      <version>%1$s</version>
                    </versions>
                  </versioning>
                </metadata>
                """
                                .formatted(LOCATION_SENTINEL_VERSION, metadata.getGroupId(), metadata.getArtifactId());
        writeResponse(task, xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Scans the local Maven repository for a previously cached relocation
     * POM for the same coordinate at any version. The POM content is
     * version-agnostic (the relocation omits the version), so any cached
     * version's POM provides the correct mapping.
     */
    private Artifact findCachedRelocation(Artifact requested, String requestedVersion) {
        Path artifactDir = localRepoBasedir.resolve(requested.getGroupId().replace('.', '/')).resolve(requested.getArtifactId());
        if (!Files.isDirectory(artifactDir)) {
            return null;
        }

        try (var versions = Files.list(artifactDir)) {
            return versions.filter(Files::isDirectory)
                           .map(dir -> dir.resolve(requested.getArtifactId() + "-" + dir.getFileName() + ".pom"))
                           .filter(Files::isRegularFile)
                           .map(AbstractLocationTransporter::parseRelocationTarget)
                           .filter(a -> a != null)
                           .findFirst()
                           .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Checks upstream repos for the canonical coordinate at
     * any version up to and including the requested version.
     */
    private Artifact findUpstreamRelocation(Artifact requested, String requestedVersion) {
        Artifact upstream = upstreamCoordinate(requested);
        String range = LOCATION_SENTINEL_VERSION.equals(requestedVersion) ? "[0,)" : "(," + requestedVersion + "]";
        var rangeArtifact = upstream.setVersion(range);
        var request = new VersionRangeRequest(rangeArtifact, repositories, null);
        try {
            var result = system.resolveVersionRange(session, request);
            if (result.getVersions().isEmpty()) {
                return null;
            }
            var highestVersion = new TreeSet<>(result.getVersions()).getLast().toString();
            var pomArtifact = upstream.setVersion(highestVersion);
            var resolved = tryResolveArtifact(pomArtifact);
            if (resolved == null || resolved.getFile() == null) {
                return null;
            }
            return parseRelocationTarget(resolved.getFile()
                    .toPath());
        } catch (VersionRangeResolutionException e) {
            return null;
        }
    }

    static Artifact parseRelocationTarget(Path pomPath) {
        try (var in = Files.newInputStream(pomPath)) {
            var model = new MavenXpp3Reader().read(in);
            if (model.getDistributionManagement() == null) {
                return null;
            }
            var relocation = model.getDistributionManagement().getRelocation();
            if (relocation == null) {
                return null;
            }
            String groupId = relocation.getGroupId();
            String artifactId = relocation.getArtifactId();
            if (groupId == null
                    || groupId.isEmpty()
                    || artifactId == null
                    || artifactId.isEmpty()) {
                return null;
            }
            return new DefaultArtifact(groupId, artifactId, "jar", null);
        } catch (IOException | XmlPullParserException e) {
            return null;
        }
    }

    static byte[] buildRelocationPom(String groupId, String artifactId, String version,
            String targetGroupId, String targetArtifactId) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion(version);
        model.setPackaging("pom");

        Relocation relocation = new Relocation();
        relocation.setGroupId(targetGroupId);
        relocation.setArtifactId(targetArtifactId);

        DistributionManagement dm = new DistributionManagement();
        dm.setRelocation(relocation);
        model.setDistributionManagement(dm);

        return serializeModel(model);
    }
}
