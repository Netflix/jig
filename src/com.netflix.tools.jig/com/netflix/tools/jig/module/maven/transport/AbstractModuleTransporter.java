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
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.lang.model.SourceVersion;
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

    /** Module identity and descriptor metadata observed in a module artifact. */
    public record ModuleIdentity(String moduleName, String osgiModuleName, ModuleDescriptor descriptor,
            Set<String> packages, String mainClass, Map<String, List<String>> provides) {

        private static final Pattern VERSIONED_MODULE_INFO = Pattern.compile("META-INF/versions/(\\d+)/module-info\\.class");
        private static final Pattern VERSIONED_ENTRY = Pattern.compile("META-INF/versions/(\\d+)/(.+)");

        public ModuleIdentity {
            if (descriptor != null) {
                moduleName = descriptor.name();
                packages = descriptor.packages();
                mainClass = descriptor.mainClass().orElse(null);
                var descriptorProvides = new LinkedHashMap<String, List<String>>();
                descriptor.provides().forEach(provide -> descriptorProvides.put(provide.service(), provide.providers()));
                provides = descriptorProvides;
            }
            packages = Set.copyOf(packages);
            var copiedProvides = new LinkedHashMap<String, List<String>>();
            provides.forEach((service, providers) -> copiedProvides.put(service, List.copyOf(providers)));
            provides = Map.copyOf(copiedProvides);
        }

        public static ModuleIdentity parseJarEntries(Set<String> entryNames, Function<String, byte[]> reader) {
            Manifest manifest = null;
            if (entryNames.contains(JarFile.MANIFEST_NAME)) {
                var bytes = reader.apply(JarFile.MANIFEST_NAME);
                try {
                    manifest = new Manifest(new ByteArrayInputStream(bytes));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            var manifestIdentity = manifestIdentity(manifest);
            var multiRelease = manifest != null && "true".equalsIgnoreCase(manifest.getMainAttributes()
                    .getValue(Name.MULTI_RELEASE));
            var moduleInfoEntry = findModuleInfo(entryNames, multiRelease);
            if (moduleInfoEntry != null) {
                var bytes = reader.apply(moduleInfoEntry);
                var descriptor = ModuleDescriptor.read(ByteBuffer.wrap(bytes));
                return new ModuleIdentity(null, null, descriptor, Set.of(), null,
                        Map.of());
            }

            return new ModuleIdentity(manifestIdentity.moduleName(), manifestIdentity.osgiModuleName(), null,
                    packages(entryNames, multiRelease), manifestIdentity.mainClass(), serviceProviders(entryNames, reader));
        }

        private static ModuleIdentity manifestIdentity(Manifest manifest) {
            if (manifest == null) {
                return new ModuleIdentity(null, null, null, Set.of(), null,
                        Map.of());
            }
            var attributes = manifest.getMainAttributes();
            return new ModuleIdentity(attributes.getValue("Automatic-Module-Name"), osgiModuleName(attributes.getValue("Bundle-SymbolicName")), null,
                    Set.of(), attributes.getValue(Name.MAIN_CLASS), Map.of());
        }

        private static String osgiModuleName(String header) {
            if (header == null) {
                return null;
            }
            var parameters = header.indexOf(';');
            var name = (parameters < 0 ? header : header.substring(0, parameters)).strip();
            return SourceVersion.isName(name) ? name : null;
        }

        private static Map<String, List<String>> serviceProviders(Set<String> entryNames, Function<String, byte[]> reader) {
            var prefix = "META-INF/services/";
            var provides = new TreeMap<String, List<String>>();
            entryNames.stream()
                    .filter(name -> name.startsWith(prefix) && name.length() > prefix.length())
                    .sorted()
                    .forEach(name -> {
                        var providers = Arrays.stream(new String(reader.apply(name), StandardCharsets.UTF_8).split("\\R"))
                                .map(line -> line.replaceFirst("#.*", "").strip())
                                .filter(line -> !line.isEmpty())
                                .distinct()
                                .toList();
                        if (!providers.isEmpty()) {
                            provides.put(name.substring(prefix.length()), providers);
                        }
                    });
            return Map.copyOf(provides);
        }

        private static Set<String> packages(Set<String> entryNames, boolean multiRelease) {
            var packages = new TreeSet<String>();
            var runtimeVersion = JarFile.runtimeVersion().feature();
            for (var original : entryNames) {
                var entry = original;
                var versioned = VERSIONED_ENTRY.matcher(entry);
                if (versioned.matches()) {
                    var version = Integer.parseInt(versioned.group(1));
                    if (!multiRelease || version < JarFile.baseVersion().feature() || version > runtimeVersion) {
                        continue;
                    }
                    entry = versioned.group(2);
                }
                if (!entry.endsWith(".class") || entry.equals("module-info.class")) {
                    continue;
                }
                var separator = entry.lastIndexOf('/');
                if (separator <= 0 || entry.startsWith("META-INF/")) {
                    continue;
                }
                packages.add(entry.substring(0, separator)
                                  .replace('/', '.'));
            }
            return Set.copyOf(packages);
        }

        public static ModuleIdentity parseJar(Path jarPath) {
            var reference = ModuleFinder.of(jarPath).findAll().stream()
                    .findFirst()
                    .orElseThrow();
            var descriptor = reference.descriptor();
            if (!descriptor.isAutomatic()) {
                return new ModuleIdentity(null, null, descriptor, Set.of(), null,
                        Map.of());
            }

            var manifestIdentity = manifestIdentity(jarPath);
            var provides = descriptor.provides().stream()
                    .collect(Collectors.toMap(Provides::service, Provides::providers));
            return new ModuleIdentity(
                    manifestIdentity.moduleName(),
                    manifestIdentity.osgiModuleName(),
                    null,
                    descriptor.packages(),
                    descriptor.mainClass().orElse(null),
                    provides);
        }

        private static ModuleIdentity manifestIdentity(Path jarPath) {
            try (var jar = new JarFile(jarPath.toFile())) {
                return manifestIdentity(jar.getManifest());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static ModuleIdentity parseJarEntries(Path jarPath) {
            try (var zf = new ZipFile(jarPath.toFile())) {
                var entryNames = zf.stream()
                        .map(ZipEntry::getName)
                        .collect(Collectors.toSet());
                return parseJarEntries(entryNames, name -> {
                    try (var in = zf.getInputStream(zf.getEntry(name))) {
                        return in.readAllBytes();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public static ModuleIdentity parseJmod(Path jmodPath) {
            try (var zf = new ZipFile(jmodPath.toFile())) {
                var entryNames = zf.stream()
                        .map(ZipEntry::getName)
                        .collect(Collectors.toSet());
                return parseJmodEntries(entryNames, name -> {
                    try (var in = zf.getInputStream(zf.getEntry(name))) {
                        return in.readAllBytes();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public static ModuleIdentity parseJmodEntries(Set<String> entryNames, Function<String, byte[]> reader) {
            var prefix = "classes/";
            var classes = entryNames.stream()
                    .filter(name -> name.startsWith(prefix))
                    .map(name -> name.substring(prefix.length()))
                    .collect(Collectors.toSet());
            return parseJarEntries(classes, name -> reader.apply(prefix + name));
        }

        private static String findModuleInfo(Set<String> entryNames, boolean multiRelease) {
            var best = entryNames.contains("module-info.class") ? "module-info.class" : null;
            if (!multiRelease) {
                return best;
            }
            var highestVersion = -1;
            var runtimeVersion = JarFile.runtimeVersion().feature();
            for (var name : entryNames) {
                var matcher = VERSIONED_MODULE_INFO.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                var version = Integer.parseInt(matcher.group(1));
                if (version >= JarFile.baseVersion().feature() && version <= runtimeVersion && version > highestVersion) {
                    highestVersion = version;
                    best = name;
                }
            }
            return best;
        }
    }

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
