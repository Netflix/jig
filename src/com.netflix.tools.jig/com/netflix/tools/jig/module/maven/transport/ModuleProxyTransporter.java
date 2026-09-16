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
import java.io.OutputStream;
import java.io.Serial;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLStreamException;

import com.netflix.module.ModuleHash;
import com.netflix.tools.jig.internal.org.apache.maven.api.metadata.Metadata;
import com.netflix.tools.jig.internal.org.apache.maven.api.metadata.Versioning;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Dependency;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Exclusion;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.collection.CollectRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.collection.DependencyCollectionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.graph.DependencyNode;
import com.netflix.tools.jig.internal.org.eclipse.aether.installation.InstallRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.installation.InstallationException;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResult;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.PeekTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.TransportListener;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.http.HttpTransporter;
import com.netflix.tools.jig.internal.org.eclipse.aether.transfer.TransferCancelledException;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.graph.transformer.ConflictResolver;
import com.netflix.tools.jig.module.ArtifactCandidates;
import com.netflix.tools.jig.module.MavenArtifactOrigin;
import com.netflix.tools.jig.module.MavenDependency;

/**
 * Maps Maven artifacts into the canonical module coordinate space.
 *
 * <p>Artifact content is returned unchanged; the canonical main jar filename
 * supplies the name of an otherwise unnamed automatic module.
 */
public final class ModuleProxyTransporter extends AbstractModuleTransporter implements HttpTransporter, ResourceTransporter {

    private static final Map<String, String> CHECKSUM_EXTENSIONS = Map.of("sha512", "SHA-512", "sha256", "SHA-256", "sha1", "SHA-1",
            "md5", "MD5");
    private static final List<String> CHECKSUM_ALGORITHMS = List.of("SHA-1", "MD5");
    private final RepositorySystemSession locationSession;
    private final List<RemoteRepository> locationRepositories;

    public ModuleProxyTransporter(RepositorySystem system, RepositorySystemSession backendSession, RepositorySystemSession locationSession,
            List<RemoteRepository> repositories) {
        super(system, backendSession, repositories);
        this.locationSession = locationSession;
        var locationRepository = new Builder("jig-location", "default", "jig+location-module://virtual").build();
        var locationRepositories = new ArrayList<RemoteRepository>();
        locationRepositories.add(locationRepository);
        locationRepositories.addAll(repositories);
        this.locationRepositories = List.copyOf(locationRepositories);
    }

    @FunctionalInterface
    private interface ContentWriter {
        void writeTo(OutputStream output) throws IOException;
    }

    private record Resource(long contentLength, Map<String, String> checksums, ContentWriter writer) implements ResourceTransporter.Resource {
        private Resource {
            checksums = Map.copyOf(checksums);
        }

        @Override
        public void writeTo(OutputStream output) throws IOException {
            writer.writeTo(output);
        }
    }

    private static final class CancelledTransfer extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final TransferCancelledException cancellation;

        private CancelledTransfer(TransferCancelledException cancellation) {
            super(cancellation);
            this.cancellation = cancellation;
        }
    }

    private static final class ProgressOutputStream extends OutputStream {
        private final OutputStream output;
        private final TransportListener listener;
        private final byte[] singleByte = new byte[1];

        private ProgressOutputStream(OutputStream output, TransportListener listener) {
            this.output = output;
            this.listener = listener;
        }

        @Override
        public void write(int value) throws IOException {
            output.write(value);
            singleByte[0] = (byte) value;
            progressed(singleByte, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
            progressed(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        private void progressed(byte[] bytes, int offset, int length) throws CancelledTransfer {
            try {
                listener.transportProgressed(ByteBuffer.wrap(bytes, offset, length));
            } catch (TransferCancelledException e) {
                throw new CancelledTransfer(e);
            }
        }
    }

    private static final class MeasuringOutputStream extends OutputStream {
        private final Map<String, MessageDigest> digests = new LinkedHashMap<>();
        private long count;

        private MeasuringOutputStream() throws NoSuchAlgorithmException {
            for (String algorithm : CHECKSUM_ALGORITHMS) {
                digests.put(algorithm, MessageDigest.getInstance(algorithm));
            }
        }

        @Override
        public void write(int value) {
            count++;
            for (var digest : digests.values()) {
                digest.update((byte) value);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            count += length;
            for (var digest : digests.values()) {
                digest.update(bytes, offset, length);
            }
        }

        private Map<String, String> checksums() {
            var checksums = new LinkedHashMap<String, String>();
            digests.forEach((algorithm, digest) -> checksums.put(algorithm, HexFormat.of()
                    .formatHex(digest.digest())));
            return Map.copyOf(checksums);
        }
    }

    public String lookupModuleName(Artifact artifact) throws IOException {
        try {
            return moduleNameFor(artifact).orElseThrow(() -> new IOException("Artifact is not independently locatable as a module: " + artifact));
        } catch (ArtifactNotFoundException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public Artifact locateModule(String moduleName, String version) throws IOException {
        Artifact canonical = ArtifactCandidates.locationCoordinate(moduleName, version);
        return locate(new DefaultArtifact(canonical.getGroupId(), canonical.getArtifactId(), "jar", version));
    }

    public ModuleDescriptor moduleDescriptor(Artifact canonical) throws IOException {
        return TransportTrace.withContext(
                "resolve " + canonical.getArtifactId() + "@" + canonical.getVersion(),
                () -> {
                    Artifact target = locate(canonical);
                    ModuleIdentity identity = probeModuleIdentity(target);
                    if (identity == null) {
                        throw new FileNotFoundException(target.toString());
                    }
                    var descriptor = identity.descriptor();
                    if (descriptor == null) {
                        var builder = ModuleDescriptor.newAutomaticModule(canonical.getArtifactId()).packages(identity.packages());
                        if (identity.mainClass() != null) {
                            builder.mainClass(identity.mainClass());
                        }
                        identity.provides().forEach(builder::provides);
                        try {
                            builder.version(canonical.getVersion());
                        } catch (IllegalArgumentException _) {
                            // Maven versions need not be valid JPMS module versions.
                        }
                        descriptor = builder.build();
                    }
                    if (!descriptor.name().equals(canonical.getArtifactId())) {
                        throw new IOException("Artifact "
                                + target
                                + " provides module "
                                + descriptor.name()
                                + ", not "
                                + canonical.getArtifactId());
                    }
                    return descriptor;
                });
    }

    @Override
    protected void implPeek(PeekTask task) throws Exception {
        resource(task.getLocation()
                     .getPath());
    }

    @Override
    protected void implGet(GetTask task) throws Exception {
        Resource resource = resource(task.getLocation()
                .getPath());
        resource.checksums().forEach(task::setChecksum);
        try (var output = task.newOutputStream()) {
            task.getListener().transportStarted(0, resource.contentLength());
            try {
                resource.writeTo(new ProgressOutputStream(output, task.getListener()));
            } catch (CancelledTransfer e) {
                throw e.cancellation;
            }
        }
    }

    @Override
    public ResourceTransporter.Resource resource(URI location) throws Exception {
        return resource(location.getPath());
    }

    private Resource resource(String path) throws Exception {
        String context = resolutionContext(path);
        if (context == null) {
            return resourceInContext(path);
        }
        return TransportTrace.withContext(context, () -> resourceInContext(path));
    }

    private static String resolutionContext(String path) {
        String contentPath = path;
        for (String extension : CHECKSUM_EXTENSIONS.keySet()) {
            String suffix = "." + extension;
            if (contentPath.endsWith(suffix)) {
                contentPath = contentPath.substring(0, contentPath.length() - suffix.length());
                break;
            }
        }
        Artifact requested = parseArtifactPath(contentPath);
        if (requested != null && isCanonical(requested)) {
            return "resolve " + requested.getArtifactId() + "@" + requested.getVersion();
        }
        Artifact metadata = parseMetadataPath(URI.create(contentPath));
        if (metadata != null && isCanonical(metadata)) {
            return "resolve " + metadata.getArtifactId() + " versions";
        }
        return null;
    }

    private Resource resourceInContext(String path) throws Exception {
        for (var checksum : CHECKSUM_EXTENSIONS.entrySet()) {
            String suffix = "." + checksum.getKey();
            if (path.endsWith(suffix)) {
                String contentPath = path.substring(0, path.length() - suffix.length());
                Resource content = resourceInContext(contentPath);
                String value = content.checksums().get(checksum.getValue());
                if (value == null) {
                    value = checksum(content, checksum.getValue());
                }
                trace("generate %s checksum for %s", checksum.getValue(), contentPath);
                return bytes(value.getBytes(StandardCharsets.US_ASCII), Map.of());
            }
        }

        Artifact metadata = parseMetadataPath(URI.create(path));
        if (metadata != null && isCanonical(metadata)) {
            trace("generate canonical metadata %s:%s", metadata.getGroupId(), metadata.getArtifactId());
            var model = metadataModel(metadata);
            return generatedResource(output -> serializeMetadata(output, model));
        }

        Artifact requested = parseArtifactPath(path);
        if (requested == null || !isCanonical(requested)) {
            throw new FileNotFoundException(path);
        }
        trace("canonical resource %s", requested);

        Artifact target = locate(requested);
        if (requested.getClassifier().isEmpty() && requested.getExtension().equals("pom")) {
            trace("generate consumer POM %s from %s", requested, target);
            var model = consumerPom(requested, target);
            return generatedResource(output -> serializeModel(output, model));
        }
        if (requested.getClassifier().isEmpty() && requested.getExtension().equals("jar.hash")) {
            trace("generate module hash %s from %s", requested, target);
            byte[] hash = moduleHash(target).getBytes(StandardCharsets.US_ASCII);
            return generatedResource(output -> output.write(hash));
        }
        Artifact targetArtifact = new DefaultArtifact(target.getGroupId(), target.getArtifactId(), requested.getClassifier(),
                requested.getExtension(), requested.getVersion());
        trace("map %s from %s", requested, targetArtifact);
        return artifactResource(targetArtifact);
    }

    private static Resource generatedResource(ContentWriter writer) throws IOException, NoSuchAlgorithmException {
        var measurement = new MeasuringOutputStream();
        writer.writeTo(measurement);
        return new Resource(measurement.count, measurement.checksums(), writer);
    }

    private static Resource bytes(byte[] data, Map<String, String> checksums) {
        return new Resource(data.length, checksums, output -> output.write(data));
    }

    private static String checksum(Resource resource, String algorithm) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance(algorithm);
        resource.writeTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
        return HexFormat.of().formatHex(digest.digest());
    }

    private Metadata metadataModel(Artifact metadata) throws IOException {
        Artifact requested = new DefaultArtifact(metadata.getGroupId(), metadata.getArtifactId(), "pom", AbstractLocationTransporter.LOCATION_SENTINEL_VERSION);
        BackingCoordinate target = locateCoordinate(requested);
        trace("metadata %s:%s versions from %s:%s", metadata.getGroupId(), metadata.getArtifactId(),
                target.groupId(), target.artifactId());
        var versions = resolveVersionRangeFresh(target.artifact("jar", "[0,)"));
        if (versions.isEmpty()) {
            throw new FileNotFoundException(metadata.toString());
        }

        var versionStrings = versions.stream()
                .map(Object::toString)
                .toList();
        String latest = versionStrings.getLast();
        var versioning = Versioning.newBuilder()
                .latest(latest)
                .release(latest)
                .versions(versionStrings)
                .build();
        var model = Metadata.newBuilder()
                .groupId(metadata.getGroupId())
                .artifactId(metadata.getArtifactId())
                .versioning(versioning)
                .build();
        return model;
    }

    private record BackingCoordinate(String groupId, String artifactId) {
        Artifact artifact(String extension, String version) {
            return new DefaultArtifact(groupId, artifactId, extension, version);
        }
    }

    private Artifact locate(Artifact requested) throws IOException {
        BackingCoordinate location = locateCoordinate(requested);
        Artifact target = location.artifact("jar", requested.getVersion());
        trace("map %s -> %s", requested, target);
        return target;
    }

    private BackingCoordinate locateCoordinate(Artifact requested) throws IOException {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate(requested.getArtifactId(), requested.getVersion());
        ArtifactResult result;
        try {
            result = system.resolveArtifact(locationSession, new ArtifactRequest(location, locationRepositories, null));
        } catch (ArtifactResolutionException e) {
            result = e.getResult();
            if (result == null || result.isMissing()) {
                var missing = new FileNotFoundException(requested.toString());
                missing.initCause(e);
                throw missing;
            }
            throw new IOException("Failed to locate module " + requested.getArtifactId() + "@" + requested.getVersion(), e);
        }
        Artifact locationPom = result.getArtifact();
        if (locationPom == null || locationPom.getPath() == null) {
            throw new FileNotFoundException(requested.toString());
        }
        Model model;
        try (var input = Files.newInputStream(locationPom.getPath())) {
            model = new MavenStaxReader().read(input);
        } catch (XMLStreamException e) {
            throw new IOException("Invalid module location POM " + locationPom, e);
        }
        var distribution = model.getDistributionManagement();
        var relocation = distribution == null ? null : distribution.getRelocation();
        if (relocation == null
                || relocation.getGroupId() == null
                || relocation.getGroupId().isBlank()
                || relocation.getArtifactId() == null
                || relocation.getArtifactId().isBlank()) {
            throw new IOException("Module location POM has no relocation: " + locationPom);
        }
        return new BackingCoordinate(relocation.getGroupId(), relocation.getArtifactId());
    }

    private Resource artifactResource(Artifact target) throws IOException {
        Artifact resolved = tryResolveArtifact(target);
        if (resolved == null || resolved.getPath() == null) {
            throw new FileNotFoundException(target.toString());
        }
        trace("copy %s from %s", target, resolved.getPath());
        Path path = resolved.getPath();
        return new Resource(Files.size(path), readProvidedChecksums(path),
                output -> Files.copy(path, output));
    }

    private String moduleHash(Artifact target) throws IOException {
        Artifact resolved = tryResolveArtifact(target);
        if (resolved == null || resolved.getPath() == null) {
            throw new FileNotFoundException(target.toString());
        }
        var references = ModuleFinder.of(resolved.getPath()).findAll();
        if (references.size() != 1) {
            throw new IOException("Artifact does not contain exactly one module: " + target);
        }
        ModuleHash hash = ModuleHash.moduleSha256(references.iterator()
                .next());
        return hash + "\n";
    }

    private static Map<String, String> readProvidedChecksums(Path artifactPath) throws IOException {
        var result = new LinkedHashMap<String, String>();
        for (var checksum : CHECKSUM_EXTENSIONS.entrySet()) {
            var checksumPath = artifactPath.resolveSibling(artifactPath.getFileName() + "." + checksum.getKey());
            if (!Files.isRegularFile(checksumPath)) {
                continue;
            }
            String value = Files.readString(checksumPath).strip();
            int whitespace = value.indexOf(' ');
            if (whitespace >= 0) {
                value = value.substring(0, whitespace);
            }
            if (!value.isEmpty()) {
                result.put(checksum.getValue(), value);
            }
        }
        return Map.copyOf(result);
    }

    private Model consumerPom(Artifact requested, Artifact target) throws IOException {
        ModuleIdentity identity = probeModuleIdentity(target);
        if (identity == null) {
            throw new FileNotFoundException(target.toString());
        }
        var dependencies = identity.descriptor() == null
                ? automaticModuleConsumerPomDependencies(target)
                : explicitModuleConsumerPomDependencies(identity.descriptor(), target);
        trace("consumer POM %s has %d dependencies", target, dependencies.size());
        var model = Model.newBuilder()
                .namespaceUri("http://maven.apache.org/POM/4.0.0")
                .modelVersion("4.0.0")
                .groupId(requested.getGroupId())
                .artifactId(requested.getArtifactId())
                .version(requested.getVersion())
                .packaging("jar")
                .properties(consumerProperties(target))
                .dependencies(dependencies)
                .build();
        return model;
    }

    private static Map<String, String> consumerProperties(Artifact target) {
        return Map.of(MavenArtifactOrigin.PROPERTY, MavenArtifactOrigin.of(target)
                .toString());
    }

    private record BackingArtifact(Artifact artifact, String scope) {}

    /**
     * An automatic module has no requires directives. Expose Maven Resolver's selected backing closure as direct
     * canonical dependencies; exclusions preserve that selection when a client resolves the generated POM.
     */
    private List<Dependency> automaticModuleConsumerPomDependencies(Artifact target) throws IOException {
        var transitiveExclusion = Exclusion.newBuilder()
                .groupId("*")
                .artifactId("*")
                .build();
        return selectedBackingArtifactsByModuleName(target, true).entrySet().stream()
                .map(entry -> {
                    Artifact artifact = entry.getValue().artifact();
                    Artifact canonical = ArtifactCandidates.locationCoordinate(entry.getKey(), artifact.getVersion());
                    return Dependency.newBuilder()
                            .groupId(canonical.getGroupId())
                            .artifactId(canonical.getArtifactId())
                            .version(artifact.getVersion())
                            .scope(entry.getValue().scope())
                            .exclusions(List.of(transitiveExclusion))
                            .build();
                })
                .toList();
    }

    /**
     * The module descriptor defines the generated dependency edges. Backing Maven metadata is traversed only to locate
     * the artifact and version corresponding to each required module name.
     */
    private List<Dependency> explicitModuleConsumerPomDependencies(ModuleDescriptor descriptor, Artifact target)
            throws IOException {
        var backingArtifacts = locateBackingArtifactsByModuleName(target);
        var dependencies = new ArrayList<Dependency>();
        for (var requirement : descriptor.requires()) {
            var moduleName = requirement.name();
            var backingArtifact = backingArtifacts.get(moduleName);
            if (backingArtifact == null && ModuleFinder.ofSystem()
                    .find(moduleName)
                    .isPresent()) {
                continue;
            }

            String version = backingArtifact == null ? requirement.compiledVersion()
                    .map(Object::toString)
                    .orElse(null)
                    : backingArtifact.artifact().getVersion();
            var optional = MavenDependency.isOptional(requirement);
            if (version == null) {
                if (optional) {
                    continue;
                }
                throw new IOException("No version found for required module " + moduleName + " in " + target);
            }

            Artifact canonical = ArtifactCandidates.locationCoordinate(moduleName, version);
            var dependency = Dependency.newBuilder()
                    .groupId(canonical.getGroupId())
                    .artifactId(canonical.getArtifactId())
                    .version(version)
                    .scope("compile");
            if (optional) {
                dependency.optional("true");
            }
            dependencies.add(dependency.build());
        }
        return List.copyOf(dependencies);
    }

    private record BackingTraversalNode(Artifact artifact, boolean includeOptionalDependencies) {}

    private LinkedHashMap<String, BackingArtifact> locateBackingArtifactsByModuleName(Artifact target)
            throws IOException {
        var backingArtifacts = new LinkedHashMap<String, BackingArtifact>();
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<BackingTraversalNode>();
        queue.add(new BackingTraversalNode(target, true));
        visited.add(target.getGroupId() + ":" + target.getArtifactId());
        while (!queue.isEmpty()) {
            BackingTraversalNode current = queue.removeFirst();
            for (var dependency : readArtifactDescriptor(current.artifact()).getDependencies()) {
                if (!Set.of("compile", "runtime", "provided").contains(dependency.getScope()) || (!current.includeOptionalDependencies() && dependency.isOptional())) {
                    continue;
                }
                Artifact artifact = dependency.getArtifact();
                String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
                if (visited.contains(key)) {
                    continue;
                }

                ModuleIdentity identity = probeModuleIdentity(artifact);
                if (identity == null) {
                    if (dependency.isOptional()) {
                        continue;
                    }
                    throw new IOException("Artifact not found: " + artifact);
                }
                visited.add(key);
                String moduleName = effectiveModuleName(artifact, identity);
                recordLocation(moduleName, artifact);
                String scope = "runtime".equals(dependency.getScope()) ? "runtime" : "compile";
                backingArtifacts.putIfAbsent(moduleName, new BackingArtifact(artifact, scope));
                queue.addLast(new BackingTraversalNode(artifact, false));
            }
        }
        return backingArtifacts;
    }

    private LinkedHashMap<String, BackingArtifact> selectedBackingArtifactsByModuleName(
            Artifact target, boolean requireLocatable) throws IOException {
        var rootDependency = new com.netflix.tools.jig.internal.org.eclipse.aether.graph.Dependency(target, "compile");
        var request = new CollectRequest(rootDependency, repositories);
        if (session.getScopeManager() != null) {
            session.getScopeManager()
                   .getResolutionScope("runtime")
                   .ifPresent(request::setResolutionScope);
        }

        DependencyNode root;
        try {
            root = system.collectDependencies(session, request).getRoot();
        } catch (DependencyCollectionException e) {
            throw new IOException("Failed to collect dependencies for " + target, e);
        }

        var selected = new LinkedHashMap<String, BackingArtifact>();
        var queue = new ArrayDeque<DependencyNode>(root.getChildren());
        while (!queue.isEmpty()) {
            var node = queue.removeFirst();
            if (node.getData().get(ConflictResolver.NODE_DATA_WINNER) != null) {
                continue;
            }
            var dependency = node.getDependency();
            if (dependency == null || (requireLocatable && dependency.isOptional())) {
                continue;
            }
            Artifact artifact = dependency.getArtifact();
            Optional<String> locatedModule;
            try {
                locatedModule = moduleNameFor(artifact);
            } catch (ArtifactNotFoundException e) {
                throw new IOException(e.getMessage(), e);
            }
            String moduleName;
            if (locatedModule.isPresent()) {
                moduleName = locatedModule.get();
            } else if (requireLocatable) {
                throw new IOException("Artifact is not independently locatable as a module: " + artifact);
            } else {
                ModuleIdentity identity = probeModuleIdentity(artifact);
                if (identity == null) {
                    throw new IOException("Artifact not found: " + artifact);
                }
                moduleName = effectiveModuleName(artifact, identity);
            }
            recordLocation(moduleName, artifact);
            String scope = "runtime".equals(dependency.getScope()) ? "runtime" : "compile";
            var prior = selected.get(moduleName);
            if (prior == null) {
                selected.put(moduleName, new BackingArtifact(artifact, scope));
            } else if (!prior.artifact().equals(artifact)) {
                throw new IOException("Multiple artifacts provide module "
                        + moduleName
                        + ": "
                        + prior.artifact()
                        + " and "
                        + artifact);
            } else if (prior.scope().equals("runtime") && scope.equals("compile")) {
                selected.put(moduleName, new BackingArtifact(artifact, scope));
            }
            queue.addAll(node.getChildren());
        }
        return selected;
    }

    private void recordLocation(String moduleName, Artifact target) throws IOException {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate(moduleName, target.getVersion());
        Path installed = locationSession.getLocalRepositoryManager().getAbsolutePathForLocalArtifact(location);
        if (Files.isRegularFile(installed)) {
            Artifact existing = AbstractLocationTransporter.parseRelocationTarget(installed);
            if (existing != null
                    && existing.getGroupId().equals(target.getGroupId())
                    && existing.getArtifactId().equals(target.getArtifactId())) {
                return;
            }
            throw new IOException("Conflicting cached location for module " + moduleName + "@" + target.getVersion());
        }

        Path temporary = Files.createTempFile("jig-module-location-", ".pom");
        try {
            Files.write(temporary, AbstractLocationTransporter.buildRelocationPom(
                    location.getGroupId(), location.getArtifactId(), location.getVersion(),
                    target.getGroupId(), target.getArtifactId()));
            system.install(locationSession, new InstallRequest().addArtifact(location.setPath(temporary)));
        } catch (InstallationException e) {
            throw new IOException("Failed to record location of module " + moduleName + "@" + target.getVersion(), e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isCanonical(Artifact artifact) {
        return ArtifactCandidates.locationCoordinate(artifact.getArtifactId(), null)
                .getGroupId()
                .equals(artifact.getGroupId());
    }
}
