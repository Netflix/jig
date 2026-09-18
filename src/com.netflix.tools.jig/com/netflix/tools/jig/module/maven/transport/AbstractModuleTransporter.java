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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.stream.XMLStreamException;

import com.netflix.tools.jig.internal.org.apache.maven.api.metadata.Metadata;
import com.netflix.tools.jig.internal.org.apache.maven.metadata.v4.MetadataStaxWriter;
import com.netflix.tools.jig.internal.org.apache.maven.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxWriter;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.graph.Dependency;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorResult;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResult;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.PutTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.version.GenericVersionScheme;
import com.netflix.tools.jig.internal.org.eclipse.aether.version.InvalidVersionSpecificationException;
import com.netflix.tools.jig.internal.org.eclipse.aether.version.Version;
import com.netflix.tools.jig.internal.org.eclipse.aether.version.VersionScheme;
import com.netflix.tools.jig.module.ArtifactCandidates;
import com.netflix.tools.jig.module.ModuleIdentity;

/**
 * Base class for jig's virtual Maven transporters. Provides resolver helpers,
 * module name utilities, checksum computation, and common transport methods.
 *
 * <p>Subclasses implement {@link #implPeek} and {@link #implGet} for their
 * specific artifact type. All are read-only (put throws) and stateless.
 *
 * <p>Two session-scoped caches provide cross-transporter coordination:
 * <ul>
 *   <li>identity cache — artifact key to module identity, avoiding
 *       repeated jar downloads and inspection</li>
 *   <li>BOM cache — BOM group:artifact to managed dependency entries</li>
 * </ul>
 */
public abstract class AbstractModuleTransporter extends AbstractTransporter {

    private static final HexFormat HEX = HexFormat.of();

    private static final String IDENTITY_CACHE_KEY = "jig.module.identity.cache";
    private static final String BOM_CACHE_KEY = "jig.module.bom.cache";

    @SuppressWarnings("unchecked")
    private Map<String, ModuleIdentity> identityCache() {
        return (Map<String, ModuleIdentity>) session.getData().computeIfAbsent(IDENTITY_CACHE_KEY, ConcurrentHashMap::new);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Artifact>> bomCache() {
        return (Map<String, List<Artifact>>) session.getData().computeIfAbsent(BOM_CACHE_KEY, ConcurrentHashMap::new);
    }

    /**
     * Returns the managed dependency entries for a BOM artifact, fetching
     * and caching per session. Returns an empty list if the BOM does not
     * exist.
     */
    protected List<Artifact> fetchBomEntries(Artifact bomCandidate) {
        Artifact resolved = resolveConcreteVersion(bomCandidate);
        if (resolved == null) {
            return List.of();
        }
        String bomKey = resolved.getGroupId()
                + ":"
                + resolved.getArtifactId()
                + ":"
                + resolved.getVersion();
        return bomCache().computeIfAbsent(bomKey, k -> {
            try {
                var request = new ArtifactDescriptorRequest(resolved, repositories, null);
                var desc = system.readArtifactDescriptor(session, request);
                return desc.getManagedDependencies().stream()
                        .map(Dependency::getArtifact)
                        .filter(a -> "jar".equals(a.getExtension()))
                        .toList();
            } catch (ArtifactDescriptorException e) {
                return List.of();
            }
        });
    }

    static void trace(String fmt, Object... args) {
        TransportTrace.trace(fmt, args);
    }

    protected final RepositorySystem system;
    protected final RepositorySystemSession session;
    protected final List<RemoteRepository> repositories;

    protected AbstractModuleTransporter(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repositories) {
        this.system = system;
        this.session = session;
        this.repositories = repositories;
    }

    @Override
    public int classify(Throwable error) {
        if (error instanceof FileNotFoundException) {
            return ERROR_NOT_FOUND;
        }
        return ERROR_OTHER;
    }

    @Override
    protected void implPut(PutTask task) {
        throw new UnsupportedOperationException("read-only transport");
    }

    @Override
    protected void implClose() {}

    /** Writes generated bytes with checksums used by Maven Resolver. */
    protected void writeResponse(GetTask task, byte[] data) throws Exception {
        writeResponse(task, data,
                Map.of("SHA-1", checksum(data, "SHA-1"), "MD5", checksum(data, "MD5")));
    }

    protected void writeResponse(GetTask task, byte[] data, Map<String, String> providedChecksums) throws Exception {
        providedChecksums.forEach(task::setChecksum);
        utilGet(task, new ByteArrayInputStream(data), true, data.length, false);
    }

    protected static String checksum(byte[] data, String algorithm) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance(algorithm).digest(data);
        return HEX.formatHex(hash);
    }

    /**
     * Parses a POM path into artifact coordinates. Returns {@code null} if
     * the path doesn't match {@code g/a/v/a-v.pom}.
     */
    protected static Artifact parsePomPath(URI location) {
        String path = location.getPath();
        if (path == null || !path.endsWith(".pom")) {
            return null;
        }
        String[] segments = path.split("/");
        if (segments.length < 4) {
            return null;
        }
        String fileName = segments[segments.length - 1];
        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];
        String groupId = String.join(".", Arrays.copyOfRange(segments, 0, segments.length - 3));

        String expectedFileName = artifactId + "-" + version + ".pom";
        if (!fileName.equals(expectedFileName)) {
            return null;
        }
        return new DefaultArtifact(groupId, artifactId, "pom", version);
    }

    protected static Artifact parseArtifactPath(String path) {
        if (path == null) {
            return null;
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] segments = path.split("/");
        if (segments.length < 4) {
            return null;
        }

        String fileName = segments[segments.length - 1];
        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];
        String groupId = String.join(".", Arrays.copyOfRange(segments, 0, segments.length - 3));
        String baseName = artifactId + "-" + version;
        if (!fileName.startsWith(baseName)) {
            return null;
        }
        String suffix = fileName.substring(baseName.length());
        if (suffix.startsWith(".")) {
            String extension = suffix.substring(1);
            if (extension.isEmpty()) {
                return null;
            }
            return new DefaultArtifact(groupId, artifactId, extension, version);
        }
        if (!suffix.startsWith("-")) {
            return null;
        }
        int extensionSeparator = suffix.indexOf('.');
        if (extensionSeparator <= 1 || extensionSeparator == suffix.length() - 1) {
            return null;
        }
        String classifier = suffix.substring(1, extensionSeparator);
        String extension = suffix.substring(extensionSeparator + 1);
        return new DefaultArtifact(groupId, artifactId, classifier, extension, version);
    }

    /**
     * Parses a metadata path into artifact coordinates. Returns {@code null}
     * if the path doesn't match {@code g/a/maven-metadata.xml}.
     */
    protected static Artifact parseMetadataPath(URI location) {
        String path = location.getPath();
        if (path == null || !path.endsWith("/maven-metadata.xml")) {
            return null;
        }
        String prefix = path.substring(0, path.length() - "/maven-metadata.xml".length());
        String[] segments = prefix.split("/");
        if (segments.length < 2) {
            return null;
        }
        String artifactId = segments[segments.length - 1];
        String groupId = String.join(".", Arrays.copyOfRange(segments, 0, segments.length - 1));
        return new DefaultArtifact(groupId, artifactId, "", "");
    }

    /**
     * Serializes a Maven {@link Model} to POM XML bytes.
     */
    @SuppressWarnings("deprecation") // Maven 3 POMs require the legacy model writer.
    protected static byte[] serializeModel(Model model) {
        try {
            var out = new ByteArrayOutputStream();
            new MavenXpp3Writer().write(out, model);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static byte[] serializeModel(com.netflix.tools.jig.internal.org.apache.maven.api.model.Model model) {
        try {
            var out = new ByteArrayOutputStream();
            serializeModel(out, model);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static void serializeModel(OutputStream output, com.netflix.tools.jig.internal.org.apache.maven.api.model.Model model) throws IOException {
        try {
            var writer = new MavenStaxWriter();
            String version = model.getModelVersion();
            writer.setNamespace("http://maven.apache.org/POM/" + version);
            writer.setSchemaLocation("https://maven.apache.org/xsd/maven-" + version + ".xsd");
            writer.setAddLocationInformation(false);
            writer.write(output, model);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to serialize Maven model", e);
        }
    }

    protected static byte[] serializeMetadata(Metadata metadata) {
        try {
            var out = new ByteArrayOutputStream();
            serializeMetadata(out, metadata);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static void serializeMetadata(OutputStream output, Metadata metadata) throws IOException {
        try {
            new MetadataStaxWriter().write(output, metadata);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to serialize Maven metadata", e);
        }
    }

    private static final VersionScheme VERSION_SCHEME = new GenericVersionScheme();

    /** Returns whether a version string is a range (e.g. {@code [0,)}). */
    protected static boolean isRangeVersion(String version) {
        if (version == null) {
            return false;
        }
        try {
            return VERSION_SCHEME.parseVersionConstraint(version).getRange() != null;
        } catch (InvalidVersionSpecificationException e) {
            return false;
        }
    }

    /**
     * Resolves a range version to the highest concrete version.
     * Returns the artifact unchanged if already concrete, or
     * {@code null} if no versions match.
     */
    protected Artifact resolveConcreteVersion(Artifact artifact) {
        if (!isRangeVersion(artifact.getVersion())) {
            return artifact;
        }
        var versions = resolveVersionRange(artifact);
        if (versions.isEmpty()) {
            return null;
        }
        return artifact.setVersion(versions.getLast()
                .toString());
    }

    protected SequencedSet<Version> resolveVersionRange(Artifact artifact) {
        return resolveVersionRange(artifact, repositories);
    }

    protected SequencedSet<Version> resolveVersionRangeFresh(Artifact artifact) {
        var fresh = repositories.stream()
                .map(AbstractModuleTransporter::alwaysUpdate)
                .toList();
        return resolveVersionRange(artifact, fresh);
    }

    private SequencedSet<Version> resolveVersionRange(Artifact artifact, List<RemoteRepository> repositories) {
        Artifact rangeArtifact = artifact.setVersion("[0,)");
        VersionRangeRequest request = new VersionRangeRequest(rangeArtifact, repositories, null);
        try {
            return new TreeSet<>(system.resolveVersionRange(session, request)
                    .getVersions());
        } catch (VersionRangeResolutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static RemoteRepository alwaysUpdate(RemoteRepository repository) {
        var release = repository.getPolicy(false);
        var snapshot = repository.getPolicy(true);
        return new Builder(repository)
                .setReleasePolicy(new RepositoryPolicy(release.isEnabled(), RepositoryPolicy.UPDATE_POLICY_ALWAYS, release.getChecksumPolicy()))
                .setSnapshotPolicy(new RepositoryPolicy(snapshot.isEnabled(), RepositoryPolicy.UPDATE_POLICY_ALWAYS, snapshot.getChecksumPolicy()))
                .build();
    }

    protected ArtifactDescriptorResult readArtifactDescriptor(Artifact artifact) {
        ArtifactDescriptorRequest request = new ArtifactDescriptorRequest(artifact, repositories, null);
        try {
            return system.readArtifactDescriptor(session, request);
        } catch (ArtifactDescriptorException e) {
            throw new RuntimeException(e);
        }
    }

    protected ModuleIdentity probeModuleIdentity(Artifact artifact) {
        String key = artifactKey(artifact);
        var cache = identityCache();
        ModuleIdentity cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        var probe = MavenModuleProbe.call(() -> tryResolveArtifact(artifact));
        Artifact resolved = probe.value();
        ModuleIdentity identity = probe.identity();
        if (identity == null && resolved != null && resolved.getPath() != null) {
            identity = resolved.getExtension().equals("jmod") ? ModuleIdentity.parseJmod(resolved.getPath()) : ModuleIdentity.parseJar(resolved.getPath());
        }
        if (identity != null) {
            cache.put(key, identity);
        }
        return identity;
    }

    private static String artifactKey(Artifact artifact) {
        return artifact.getGroupId()
                + ":"
                + artifact.getArtifactId()
                + ":"
                + artifact.getExtension()
                + ":"
                + artifact.getClassifier()
                + ":"
                + artifact.getVersion();
    }

    protected Artifact tryResolveArtifact(Artifact artifact) {
        ArtifactRequest request = new ArtifactRequest(artifact, repositories, null);
        try {
            ArtifactResult result = system.resolveArtifact(session, request);
            if (result.isMissing()) {
                return null;
            }
            return result.getArtifact();
        } catch (ArtifactResolutionException e) {
            ArtifactResult result = e.getResult();
            if (result != null && result.isMissing()) {
                return null;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Determines the module name for an artifact. Returns the name if
     * the artifact exists and is independently locatable via the module
     * convention or candidate walk. Returns empty if the artifact exists
     * but cannot be independently located. Throws if the artifact does
     * not exist on any configured repository.
     *
     * @return the locatable module name, or empty if not locatable
     * @throws ArtifactNotFoundException if the artifact does not exist
     */
    protected Optional<String> moduleNameFor(Artifact artifact) throws ArtifactNotFoundException, IOException {
        ModuleIdentity identity = probeModuleIdentity(artifact);
        if (identity == null) {
            throw new ArtifactNotFoundException(artifact);
        }

        String name = effectiveModuleName(artifact, identity);

        if (ArtifactCandidates.isAuthoritative(artifact.getGroupId(), name)) {
            return Optional.of(name);
        }
        return Optional.empty();
    }

    protected String effectiveModuleName(Artifact artifact, ModuleIdentity identity) {
        if (identity.moduleName() != null) {
            return identity.moduleName();
        }
        return ArtifactCandidates.moduleNameForArtifact(artifact.getGroupId(), artifact.getArtifactId())
                .or(() -> laterEstablishedModuleName(artifact))
                .orElseGet(() -> identity.osgiModuleName() != null ? identity.osgiModuleName() : ArtifactCandidates.deriveModuleName(artifact.getGroupId(), artifact.getArtifactId()));
    }

    protected Optional<String> laterEstablishedModuleName(Artifact artifact) {
        var versions = resolveVersionRange(artifact);
        if (versions.isEmpty()) {
            return Optional.empty();
        }

        var latestVersion = versions.getLast();
        var currentVersion = versions.stream()
                .filter(v -> v.toString().equals(artifact.getVersion()))
                .findFirst()
                .orElse(null);
        if (currentVersion == null || currentVersion.compareTo(latestVersion) >= 0) {
            return Optional.empty();
        }

        Artifact laterArtifact = artifact.setVersion(latestVersion.toString());
        ModuleIdentity laterIdentity = probeModuleIdentity(laterArtifact);
        if (laterIdentity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(laterIdentity.moduleName());
    }
}
