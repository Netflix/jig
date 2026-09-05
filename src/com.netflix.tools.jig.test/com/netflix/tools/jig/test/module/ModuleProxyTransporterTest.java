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

package com.netflix.tools.jig.test.module;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.ClassFileFormatVersion;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;

import com.netflix.module.ModuleHash;
import com.netflix.tools.jig.internal.org.apache.maven.api.metadata.Metadata;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.metadata.v4.MetadataStaxReader;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.collection.CollectRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.DependencyRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.module.AetherModuleResolver;
import com.netflix.tools.jig.module.AetherModuleResolver.Result;
import com.netflix.tools.jig.module.ArtifactCandidates;
import com.netflix.tools.jig.module.MavenArtifactOrigin;
import com.netflix.tools.jig.module.Trace;
import com.netflix.tools.jig.module.maven.transport.ModuleLocationTransporterFactory;
import com.netflix.tools.jig.module.maven.transport.ModuleProxyTransporter;
import com.netflix.tools.jig.module.maven.transport.ModuleProxyTransporterFactory;
import com.netflix.tools.jig.test.module.TestRepositorySystem.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleProxyTransporterTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsRepositoriesThatCouldNotProvideAModule() throws Exception {
        Path upstreamRepository = Files.createDirectories(tempDir.resolve("missing-upstream"));
        var upstream = fileRepository(upstreamRepository);
        var locationFactory = new ModuleLocationTransporterFactory();

        try (var upstreamContext = TestRepositorySystem.create(tempDir.resolve("missing-proxy-local"),
                List.of(upstream), Map.of(ModuleLocationTransporterFactory.NAME, locationFactory))) {
            locationFactory.configure(upstreamContext.system(), upstreamContext.session(), upstreamContext.repositories(),
                    upstreamContext.repositories());
            var factory = new ModuleProxyTransporterFactory();
            factory.configure(upstreamContext.system(), upstreamContext.session(), upstreamContext.session(),
                    upstreamContext.repositories());
            try (var context = TestRepositorySystem.create(tempDir.resolve("missing-local"), List.of(), Map.of(ModuleProxyTransporterFactory.NAME, factory))) {
                var modules = new Builder("jig-modules", "default", "jig+module://virtual").build();
                var canonicalPom = new DefaultArtifact("com.example", "com.example.missing", "pom", "1.0");

                var failure = assertThrows(ArtifactResolutionException.class,
                        () -> context.system().resolveArtifact(context.session(), new ArtifactRequest(canonicalPom, List.of(modules), null)));
                var output = new StringWriter();
                failure.printStackTrace(new PrintWriter(output));

                assertTrue(output.toString().contains("Module com.example.missing@1.0 was not found in repositories: " + "upstream (" + upstream.getUrl() + ")"),
                        output.toString());
            }
        }
    }

    @Test
    void resolvesUnnamedJarAtCanonicalModuleCoordinateWithoutChangingContent() throws Exception {
        Path upstreamRepository = tempDir.resolve("upstream");
        var upstreamArtifact = installUnnamedArtifact(upstreamRepository);
        var upstream = fileRepository(upstreamRepository);
        Path localRepository = tempDir.resolve("local");
        var locationFactory = new ModuleLocationTransporterFactory();

        try (var upstreamContext = TestRepositorySystem.create(tempDir.resolve("proxy-local"), List.of(upstream), Map.of(ModuleLocationTransporterFactory.NAME, locationFactory))) {
            locationFactory.configure(upstreamContext.system(), upstreamContext.session(), upstreamContext.repositories(),
                    upstreamContext.repositories());
            assertChecksumResources(upstreamContext, upstreamArtifact);
            var factory = new ModuleProxyTransporterFactory();
            factory.configure(upstreamContext.system(), upstreamContext.session(), upstreamContext.session(),
                    upstreamContext.repositories());
            try (var context = TestRepositorySystem.create(localRepository, List.of(), Map.of(ModuleProxyTransporterFactory.NAME, factory))) {
                var modules = new Builder("jig-modules", "default", "jig+module://virtual").build();
                var canonicalRange = new DefaultArtifact("com.example", "com.example.library", "pom", "[0,)");
                var versionRange = context.system().resolveVersionRange(context.session(), new VersionRangeRequest(canonicalRange, List.of(modules), null));
                assertEquals(List.of("1.0", "1.1"),
                        versionRange.getVersions().stream()
                                .map(Object::toString)
                                .toList());
                var redirectedRange = new DefaultArtifact("com.example", "com.example.redirected", "pom", "[0,)");
                var redirectedVersions = context.system().resolveVersionRange(context.session(), new VersionRangeRequest(redirectedRange, List.of(modules), null));
                assertEquals(List.of("2.0", "2.1"),
                        redirectedVersions.getVersions().stream()
                                .map(Object::toString)
                                .toList());
                var nativeRange = new DefaultArtifact("com.example", "com.example.canonical", "pom", "[0,)");
                var nativeVersions = context.system().resolveVersionRange(context.session(), new VersionRangeRequest(nativeRange, List.of(modules), null));
                assertEquals(List.of("1.0"),
                        nativeVersions.getVersions().stream()
                                .map(Object::toString)
                                .toList());

                var canonicalJar = new DefaultArtifact("com.example", "com.example.library", "jar", "1.0");
                var jarResult = context.system().resolveArtifact(context.session(), new ArtifactRequest(canonicalJar, List.of(modules), null));
                Path resolvedJar = jarResult.getArtifact().getPath();

                assertEquals("com.example.library-1.0.jar", resolvedJar.getFileName()
                        .toString());
                assertArrayEquals(sha256(upstreamArtifact.jar()), sha256(resolvedJar));
                try (var zip = new ZipFile(resolvedJar.toFile())) {
                    assertFalse(zip.stream()
                            .anyMatch(entry -> entry.getName().equals("module-info.class")));
                }
                var descriptor = ModuleFinder.of(resolvedJar)
                        .find("com.example.library")
                        .orElseThrow()
                        .descriptor();
                assertTrue(descriptor.isAutomatic());
                assertEquals("com.example.library", descriptor.name());
                var canonicalHash = new DefaultArtifact("com.example", "com.example.library", "jar.hash", "1.0");
                var hashResult = context.system().resolveArtifact(context.session(), new ArtifactRequest(canonicalHash, List.of(modules), null));
                assertEquals(
                        ModuleHash.moduleSha256(ModuleFinder.of(resolvedJar)
                                .findAll()
                                .iterator()
                                .next()),
                        ModuleHash.read(hashResult.getArtifact().getPath()));

                assertCanonicalClassifier(context, modules, "sources", upstreamArtifact.sources());
                assertCanonicalClassifier(context, modules, "javadoc", upstreamArtifact.javadoc());
                assertCanonicalMainArtifact(context, modules, "com.example.canonical", "1.0", upstreamArtifact.authorCanonical());
                assertCanonicalMainArtifact(context, modules, "com.example.redirected", "2.0", upstreamArtifact.authorRedirectTarget());

                var canonicalPom = new DefaultArtifact("com.example", "com.example.library", "pom", "1.0");
                var pomResult = context.system().resolveArtifact(context.session(), new ArtifactRequest(canonicalPom, List.of(modules), null));
                var documentBuilderFactory = DocumentBuilderFactory.newInstance();
                documentBuilderFactory.setNamespaceAware(true);
                var document = documentBuilderFactory.newDocumentBuilder().parse(pomResult.getArtifact()
                        .getPath()
                        .toFile());
                String namespace = "http://maven.apache.org/POM/4.0.0";
                assertEquals(namespace, document.getDocumentElement()
                        .getNamespaceURI());
                assertEquals("4.0.0", elementText(document, namespace, "modelVersion"));
                assertEquals("com.example", elementText(document, namespace, "groupId"));
                assertEquals("com.example.library", elementText(document, namespace, "artifactId"));
                assertEquals("1.0", elementText(document, namespace, "version"));

                Model consumerModel;
                try (var input = Files.newInputStream(pomResult.getArtifact()
                        .getPath())) {
                    consumerModel = new MavenStaxReader().read(input);
                }
                assertEquals("pkg:maven/com.example/library@1.0", consumerModel.getProperties()
                        .get(MavenArtifactOrigin.PROPERTY));
                assertEquals(List.of("com.example.bridge", "com.example.runtime", "com.example.core"),
                        consumerModel.getDependencies().stream()
                                .map(com.netflix.tools.jig.internal.org.apache.maven.api.model.Dependency::getArtifactId)
                                .toList());
                for (var dependency : consumerModel.getDependencies()) {
                    assertEquals("com.example", dependency.getGroupId());
                    assertEquals("1.0", dependency.getVersion());
                    assertEquals("jar", dependency.getType());
                    assertEquals(1, dependency.getExclusions()
                            .size());
                    assertEquals("*",
                            dependency.getExclusions()
                                      .getFirst()
                                      .getGroupId());
                    assertEquals("*",
                            dependency.getExclusions()
                                      .getFirst()
                                      .getArtifactId());
                }
                assertEquals("compile",
                        consumerModel.getDependencies()
                                     .get(0)
                                     .getScope());
                assertEquals("runtime",
                        consumerModel.getDependencies()
                                     .get(1)
                                     .getScope());
                assertEquals("compile",
                        consumerModel.getDependencies()
                                     .get(2)
                                     .getScope());
                assertFalse(consumerModel.getDependencies().stream()
                        .anyMatch(dependency -> dependency.getArtifactId().contains("excluded")));

                var rootDependency = new com.netflix.tools.jig.internal.org.eclipse.aether.graph.Dependency(canonicalJar, "compile");
                var collectRequest = new CollectRequest(rootDependency, List.of(modules));
                if (context.session().getScopeManager() != null) {
                    context.session()
                           .getScopeManager()
                           .getResolutionScope("runtime")
                           .ifPresent(collectRequest::setResolutionScope);
                }
                var dependencyResult = context.system().resolveDependencies(context.session(), new DependencyRequest(collectRequest, null));
                var resolvedModuleNames = dependencyResult.getArtifactResults().stream()
                        .map(result -> result.getArtifact().getArtifactId())
                        .collect(Collectors.toSet());
                assertEquals(Set.of("com.example.library", "com.example.bridge", "com.example.runtime", "com.example.core"), resolvedModuleNames);
                assertFalse(resolvedModuleNames.contains("com.example.excluded"));
                for (var result : dependencyResult.getArtifactResults()) {
                    var artifact = result.getArtifact();
                    assertEquals(artifact.getArtifactId() + "-1.0.jar",
                            artifact.getPath()
                                    .getFileName()
                                    .toString());
                    assertEquals(
                            artifact.getArtifactId(),
                            ModuleFinder.of(artifact.getPath())
                                    .find(artifact.getArtifactId())
                                    .orElseThrow()
                                    .descriptor()
                                    .name());
                }

                var rootDescriptor = ModuleDescriptor.newModule("com.example.app")
                        .requires(Set.of(), "com.example.library", Version.parse("1.0"))
                        .requires(Set.of(), "com.example.core", Version.parse("2.0"))
                        .build();
                var traceBytes = new ByteArrayOutputStream();
                Result resolvedModules;
                try (var metadata = new ModuleProxyTransporter(upstreamContext.system(), upstreamContext.session(), upstreamContext.session(),
                             upstreamContext.repositories());
                     var traceOutput = new PrintStream(traceBytes)) {
                    resolvedModules = Trace.callWithOutput(traceOutput,
                            () -> new AetherModuleResolver(context.system(), context.session(), List.of(modules), metadata::moduleDescriptor)
                                    .resolve(List.of(rootDescriptor), false, Set.of(), true));
                }
                String resolutionTrace = traceBytes.toString();
                assertTrue(resolutionTrace.contains("selected com.example.library@1.0 automatic"), resolutionTrace);
                assertTrue(resolutionTrace.contains("mediate com.example.core@1.0 -> com.example.core@2.0"), resolutionTrace);
                assertTrue(resolutionTrace.contains("supplemental root com.example.runtime " + "(explicit module dependency of automatic module " + "com.example.library)"), resolutionTrace);
                assertEquals(
                        Set.of("com.example.library", "com.example.bridge", "com.example.runtime", "com.example.core"),
                        resolvedModules.finder().findAll().stream()
                                .map(reference -> reference.descriptor().name())
                                .collect(Collectors.toSet()));
                assertEquals(Set.of("com.example.runtime"), resolvedModules.supplementalRoots());
                assertEquals(Set.of("com.example.bridge", "com.example.runtime", "com.example.core"),
                        resolvedModules.dependencies().get("com.example.library"));
                var libraryDescriptor = resolvedModules.finder()
                        .find("com.example.library")
                        .orElseThrow()
                        .descriptor();
                assertEquals(descriptor, libraryDescriptor);
                assertEquals("com.example.library.Library", libraryDescriptor.mainClass()
                        .orElseThrow());
                assertTrue(libraryDescriptor.provides().stream()
                        .anyMatch(provides -> provides.service().equals("com.example.Service") && provides.providers().equals(List.of("com.example.library.Library"))));
                assertTrue(resolvedModules.finder()
                        .find("com.example.core")
                        .orElseThrow()
                        .location()
                        .isEmpty());
                assertArrayEquals(sha256(upstreamArtifact.sources()),
                        sha256(resolvedModules.sources().get("com.example.library")));

                assertCanonicalClassifier(context, modules, "annotations", upstreamArtifact.annotations());
            }

            Files.delete(upstreamArtifact.jar());
            var secondFactory = new ModuleProxyTransporterFactory();
            secondFactory.configure(upstreamContext.system(), upstreamContext.session(), upstreamContext.session(),
                    upstreamContext.repositories());
            try (var context = TestRepositorySystem.create(localRepository, List.of(), Map.of(ModuleProxyTransporterFactory.NAME, secondFactory))) {
                var modules = new Builder("jig-modules", "default", "jig+module://virtual").build();
                assertCanonicalClassifier(context, modules, "verification", upstreamArtifact.verification());
            }
        }
    }

    @Test
    void explicitModulePrefersVersionsInTransitiveMavenClosure() throws Exception {
        Path upstreamRepository = tempDir.resolve("upstream-explicit");
        installUnnamedArtifact(upstreamRepository);
        addMissingOptionalDependency(upstreamRepository, "bridge");
        installExplicitArtifact(upstreamRepository);
        var upstream = fileRepository(upstreamRepository);
        var locationFactory = new ModuleLocationTransporterFactory();

        try (var upstreamContext = TestRepositorySystem.create(tempDir.resolve("proxy-local-explicit"),
                List.of(upstream), Map.of(ModuleLocationTransporterFactory.NAME, locationFactory))) {
            locationFactory.configure(upstreamContext.system(), upstreamContext.session(), upstreamContext.repositories(),
                    upstreamContext.repositories());
            var factory = new ModuleProxyTransporterFactory();
            factory.configure(upstreamContext.system(), upstreamContext.session(), upstreamContext.session(),
                    upstreamContext.repositories());
            try (var context = TestRepositorySystem.create(tempDir.resolve("local-explicit"), List.of(), Map.of(ModuleProxyTransporterFactory.NAME, factory))) {
                var modules = new Builder("jig-modules", "default", "jig+module://virtual").build();
                var canonicalPom = new DefaultArtifact("com.example", "com.example.explicit", "pom", "1.0");
                var result = context.system().resolveArtifact(context.session(), new ArtifactRequest(canonicalPom, List.of(modules), null));

                Model model;
                try (var input = Files.newInputStream(result.getArtifact()
                        .getPath())) {
                    model = new MavenStaxReader().read(input);
                }
                assertEquals(1, model.getDependencies()
                                     .size());
                var dependency = model.getDependencies().getFirst();
                assertEquals("com.example", dependency.getGroupId());
                assertEquals("com.example.core", dependency.getArtifactId());
                assertEquals("1.0", dependency.getVersion());
                assertEquals("jar", dependency.getType());
                assertEquals("compile", dependency.getScope());
                assertTrue(dependency.getExclusions()
                                     .isEmpty());

                var rootDescriptor = ModuleDescriptor.newModule("com.example.app")
                        .requires(Set.of(), "com.example.explicit", Version.parse("1.0"))
                        .build();
                var traceBytes = new ByteArrayOutputStream();
                Result resolvedModules;
                try (var metadata = new ModuleProxyTransporter(upstreamContext.system(), upstreamContext.session(), upstreamContext.session(),
                             upstreamContext.repositories());
                     var traceOutput = new PrintStream(traceBytes)) {
                    resolvedModules = Trace.callWithOutput(traceOutput, () -> new AetherModuleResolver(context.system(), context.session(), List.of(modules), metadata::moduleDescriptor)
                            .resolve(List.of(rootDescriptor), false));
                }
                String resolutionTrace = traceBytes.toString();
                assertFalse(resolutionTrace.contains("canonical resource com.example:com.example.explicit:jar:1.0"), resolutionTrace);
                assertFalse(resolutionTrace.contains("generate module hash"), resolutionTrace);
                assertTrue(resolvedModules.hashes()
                        .isEmpty());
                assertTrue(resolvedModules.supplementalRoots()
                        .isEmpty());

                var cachedTraceBytes = new ByteArrayOutputStream();
                Result cachedModules;
                try (var metadata = new ModuleProxyTransporter(upstreamContext.system(), upstreamContext.session(), upstreamContext.session(),
                             upstreamContext.repositories());
                     var traceOutput = new PrintStream(cachedTraceBytes)) {
                    cachedModules = Trace.callWithOutput(traceOutput, () -> new AetherModuleResolver(context.system(), context.session(), List.of(modules), metadata::moduleDescriptor)
                            .resolve(List.of(rootDescriptor), false));
                }
                assertEquals(resolvedModules.hashes(), cachedModules.hashes());
                assertFalse(cachedTraceBytes.toString().contains("generate module hash"),
                        cachedTraceBytes.toString());
            }
        }
    }

    private static void assertChecksumResources(Context upstream, UpstreamArtifact artifact) throws Exception {
        var transporter = new ModuleProxyTransporter(upstream.system(), upstream.session(), upstream.session(),
                upstream.repositories());
        try {
            String base = "com/example/com.example.library/1.0/com.example.library-1.0";
            var jarTask = new GetTask(URI.create(base + ".jar"));
            var traceBytes = new ByteArrayOutputStream();
            try (var traceOutput = new PrintStream(traceBytes)) {
                Trace.withOutput(traceOutput, () -> transporter.get(jarTask));
            }
            String trace = traceBytes.toString();
            assertTrue(trace.lines()
                            .allMatch(line -> line.startsWith("[resolve com.example.library@1.0")),
                    trace);
            assertTrue(trace.contains("canonical resource " + "com.example:com.example.library:jar:1.0"), trace);
            assertTrue(trace.contains("map com.example:com.example.library:jar:1.0 " + "-> com.example:library:jar:1.0"), trace);
            assertTrue(trace.contains("project com.example:com.example.library:jar:1.0 " + "from com.example:library:jar:1.0"), trace);
            assertTrue(trace.contains("copy com.example:library:jar:1.0 from "), trace);
            assertArrayEquals(Files.readAllBytes(artifact.jar()), jarTask.getDataBytes());

            assertTrue(jarTask.getChecksums()
                              .isEmpty());
            var algorithms = Map.of("SHA-512", "sha512", "SHA-256", "sha256", "SHA-1", "sha1",
                    "MD5", "md5");
            var upstreamJar = new DefaultArtifact("com.example", "library", "jar", "1.0");
            Path cachedUpstreamJar = upstream.session()
                    .getLocalRepositoryManager()
                    .getAbsolutePathForRemoteArtifact(upstreamJar, upstream.repositories().getFirst(),
                            null);
            String providedSha256 = checksumHex("SHA-256", jarTask.getDataBytes()).toUpperCase();
            Files.writeString(cachedUpstreamJar.resolveSibling(cachedUpstreamJar.getFileName() + ".sha256"), providedSha256);

            var providedJarTask = new GetTask(URI.create(base + ".jar"));
            transporter.get(providedJarTask);
            assertArrayEquals(jarTask.getDataBytes(), providedJarTask.getDataBytes());
            assertEquals(Map.of("SHA-256", providedSha256), providedJarTask.getChecksums());

            for (var algorithm : algorithms.entrySet()) {
                String expected = algorithm.getKey().equals("SHA-256") ? providedSha256 : checksumHex(algorithm.getKey(), jarTask.getDataBytes());
                var sidecar = new GetTask(URI.create(base + ".jar." + algorithm.getValue()));
                transporter.get(sidecar);
                assertEquals(expected, sidecar.getDataString()
                        .strip());
                assertTrue(sidecar.getChecksums()
                                  .isEmpty());
            }

            var metadataTask = new GetTask(URI.create("com/example/com.example.library/maven-metadata.xml"));
            traceBytes.reset();
            try (var traceOutput = new PrintStream(traceBytes)) {
                Trace.withOutput(traceOutput, () -> transporter.get(metadataTask));
            }
            String metadataTrace = traceBytes.toString();
            assertTrue(metadataTrace.contains("generate canonical metadata " + "com.example:com.example.library"), metadataTrace);
            assertFalse(metadataTrace.contains("com.example:com.example.library::"), metadataTrace);
            assertTrue(metadataTrace.contains("metadata com.example:com.example.library " + "versions from com.example:library"), metadataTrace);
            assertFalse(metadataTrace.contains("com.example:library:jar:0"), metadataTrace);
            assertEquals(Set.of("SHA-1", "MD5"),
                    metadataTask.getChecksums().keySet());
            Metadata metadata;
            try (var input = new ByteArrayInputStream(metadataTask.getDataBytes())) {
                metadata = new MetadataStaxReader().read(input);
            }
            assertEquals(List.of("1.0", "1.1"),
                    metadata.getVersioning().getVersions());

            var pomTask = new GetTask(URI.create(base + ".pom"));
            traceBytes.reset();
            try (var traceOutput = new PrintStream(traceBytes)) {
                Trace.withOutput(traceOutput, () -> transporter.get(pomTask));
            }
            String pomTrace = traceBytes.toString();
            assertTrue(pomTrace.lines()
                               .allMatch(line -> line.startsWith("[resolve com.example.library@1.0")),
                    pomTrace);
            assertTrue(pomTrace.contains("consumer POM com.example:library:jar:1.0"), pomTrace);
            assertEquals(Set.of("SHA-1", "MD5"),
                    pomTask.getChecksums().keySet());
            var pomSidecar = new GetTask(URI.create(base + ".pom.sha256"));
            transporter.get(pomSidecar);
            assertEquals(checksumHex("SHA-256", pomTask.getDataBytes()),
                    pomSidecar.getDataString().strip());
        } finally {
            transporter.close();
        }
    }

    private static void assertCanonicalMainArtifact(Context context, RemoteRepository modules, String moduleName,
            String version, Path upstreamArtifact)
            throws Exception {
        var coordinate = ArtifactCandidates.locationCoordinate(moduleName, version);
        var canonical = new DefaultArtifact(coordinate.getGroupId(), coordinate.getArtifactId(), "jar", version);
        var result = context.system().resolveArtifact(context.session(), new ArtifactRequest(canonical, List.of(modules), null));
        Path resolved = result.getArtifact().getPath();
        assertEquals(moduleName + "-" + version + ".jar",
                resolved.getFileName().toString());
        assertArrayEquals(sha256(upstreamArtifact), sha256(resolved));
        assertEquals(
                moduleName,
                ModuleFinder.of(resolved)
                        .find(moduleName)
                        .orElseThrow()
                        .descriptor()
                        .name());
    }

    private static void assertCanonicalClassifier(Context context, RemoteRepository modules, String classifier,
            Path upstreamArtifact)
            throws Exception {
        var canonical = new DefaultArtifact("com.example", "com.example.library", classifier, "jar", "1.0");
        var result = context.system().resolveArtifact(context.session(), new ArtifactRequest(canonical, List.of(modules), null));
        Path resolved = result.getArtifact().getPath();
        assertEquals("com.example.library-1.0-" + classifier + ".jar",
                resolved.getFileName().toString());
        assertArrayEquals(sha256(upstreamArtifact), sha256(resolved));
    }

    private record UpstreamArtifact(
            Path jar,
            Path sources,
            Path javadoc,
            Path annotations,
            Path verification,
            Path authorCanonical,
            Path authorRedirectTarget) {}

    private static UpstreamArtifact installUnnamedArtifact(Path repository) throws IOException {
        Path versionDir = repository.resolve("com/example/library/1.0");
        Files.createDirectories(versionDir);
        Path jar = versionDir.resolve("library-1.0.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write(("Manifest-Version: 1.0\r\n" + "Main-Class: com.example.library.Library\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("com/example/library/Library.class"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("META-INF/services/com.example.Service"));
            out.write("com.example.library.Library\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path sources = versionDir.resolve("library-1.0-sources.jar");
        writeArchive(sources, "com/example/library/Library.java");
        Path javadoc = versionDir.resolve("library-1.0-javadoc.jar");
        writeArchive(javadoc, "com/example/library/Library.html");
        Path annotations = versionDir.resolve("library-1.0-annotations.jar");
        writeArchive(annotations, "com/example/library/annotations.xml");
        Path verification = versionDir.resolve("library-1.0-verification.jar");
        writeArchive(verification, "com/example/library/verification.xml");
        Files.writeString(versionDir.resolve("library-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>library</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>bridge</artifactId>
                      <version>1.0</version>
                      <exclusions>
                        <exclusion>
                          <groupId>com.example</groupId>
                          <artifactId>excluded</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>runtime</artifactId>
                      <version>1.0</version>
                      <scope>runtime</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path nextVersionDir = repository.resolve("com/example/library/1.1");
        Files.createDirectories(nextVersionDir);
        writeArchive(nextVersionDir.resolve("library-1.1.jar"), "com/example/library/Library.class");
        Files.writeString(nextVersionDir.resolve("library-1.1.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>library</artifactId>
                  <version>1.1</version>
                </project>
                """);
        Files.writeString(repository.resolve("com/example/library/maven-metadata.xml"),
                """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>library</artifactId>
                  <versioning>
                    <latest>1.1</latest>
                    <release>1.1</release>
                    <versions>
                      <version>1.0</version>
                      <version>1.1</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        installDependency(repository, "bridge",
                """
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>core</artifactId>
                    <version>1.0</version>
                  </dependency>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>excluded</artifactId>
                    <version>1.0</version>
                  </dependency>
                </dependencies>
                """);
        installDependency(repository, "core", "");
        installSimpleArtifact(repository, "core", "2.0");
        writeArtifactMetadata(repository, "core", List.of("1.0", "2.0"));
        installDependency(repository, "excluded", "");
        installExplicitDependency(repository, "runtime");
        Path authorCanonical = installDependency(repository, "com.example.canonical", "");
        writeArtifactMetadata(repository, "com.example.canonical", List.of("1.0"));
        installDependency(repository, "redirect-target", "");
        installAuthorRedirect(repository);
        Path authorRedirectTarget = repository.resolve("com/example/redirect-target-v2/2.0/redirect-target-v2-2.0.jar");
        return new UpstreamArtifact(jar, sources, javadoc, annotations, verification, authorCanonical,
                authorRedirectTarget);
    }

    private static void addMissingOptionalDependency(Path repository, String artifactId) throws IOException {
        Path pom = repository.resolve("com/example/" + artifactId + "/1.0/" + artifactId + "-1.0.pom");
        String dependency =
                """
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>missing-optional</artifactId>
                    <version>1.0</version>
                    <optional>true</optional>
                  </dependency>
                """;
        Files.writeString(pom, Files.readString(pom)
                .replace("</dependencies>", dependency + "</dependencies>"));
    }

    private static void installExplicitArtifact(Path repository) throws IOException {
        Path versionDir = repository.resolve("com/example/explicit/1.0");
        Files.createDirectories(versionDir);
        var attribute = ModuleAttribute.of(ModuleDesc.of("com.example.explicit"),
                builder -> {
                    builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
                    builder.requires(ModuleDesc.of("com.example.core"), Set.of(), "2.0");
                });
        byte[] moduleInfo = ClassFile.of().buildModule(attribute, builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_9.major(), 0));
        try (var out = new ZipOutputStream(Files.newOutputStream(versionDir.resolve("explicit-1.0.jar")))) {
            out.putNextEntry(new ZipEntry("module-info.class"));
            out.write(moduleInfo);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("com/example/explicit/Library.class"));
            out.closeEntry();
        }
        Files.writeString(versionDir.resolve("explicit-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>explicit</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>bridge</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    private static Path installDependency(Path repository, String artifactId, String dependencies) throws IOException {
        Path versionDir = repository.resolve("com/example/" + artifactId + "/1.0");
        Files.createDirectories(versionDir);
        writeArchive(versionDir.resolve(artifactId + "-1.0.jar"), "com/example/" + artifactId + "/Type.class");
        Files.writeString(versionDir.resolve(artifactId + "-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0</version>
                  %s
                </project>
                """
                        .formatted(artifactId, dependencies));
        return versionDir.resolve(artifactId + "-1.0.jar");
    }

    private static void installExplicitDependency(Path repository, String artifactId) throws IOException {
        Path versionDir = repository.resolve("com/example/" + artifactId + "/1.0");
        Files.createDirectories(versionDir);
        var attribute = ModuleAttribute.of(ModuleDesc.of("com.example." + artifactId),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        byte[] moduleInfo = ClassFile.of().buildModule(attribute, builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_9.major(), 0));
        try (var out = new ZipOutputStream(Files.newOutputStream(versionDir.resolve(artifactId + "-1.0.jar")))) {
            out.putNextEntry(new ZipEntry("module-info.class"));
            out.write(moduleInfo);
            out.closeEntry();
        }
        Files.writeString(versionDir.resolve(artifactId + "-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0</version>
                </project>
                """
                        .formatted(artifactId));
    }

    private static void installAuthorRedirect(Path repository) throws IOException {
        Path artifactDir = repository.resolve("com/example/com.example.redirected");
        Path versionDir = artifactDir.resolve("1.0");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("com.example.redirected-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.redirected</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <distributionManagement>
                    <relocation>
                      <groupId>com.example</groupId>
                      <artifactId>redirect-target</artifactId>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        Path secondVersionDir = artifactDir.resolve("2.0");
        Files.createDirectories(secondVersionDir);
        Files.writeString(secondVersionDir.resolve("com.example.redirected-2.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.redirected</artifactId>
                  <version>2.0</version>
                  <packaging>pom</packaging>
                  <distributionManagement>
                    <relocation>
                      <groupId>com.example</groupId>
                      <artifactId>redirect-target-v2</artifactId>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        Files.writeString(artifactDir.resolve("maven-metadata.xml"),
                """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.redirected</artifactId>
                  <versioning>
                    <versions>
                      <version>1.0</version>
                      <version>2.0</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        installSimpleArtifact(repository, "redirect-target", "1.1");
        installSimpleArtifact(repository, "redirect-target-v2", "2.0");
        installSimpleArtifact(repository, "redirect-target-v2", "2.1");
        writeArtifactMetadata(repository, "redirect-target", List.of("1.0", "1.1"));
        writeArtifactMetadata(repository, "redirect-target-v2", List.of("2.0", "2.1"));
    }

    private static void installSimpleArtifact(Path repository, String artifactId, String version) throws IOException {
        Path versionDir = repository.resolve("com/example/" + artifactId + "/" + version);
        Files.createDirectories(versionDir);
        writeArchive(versionDir.resolve(artifactId + "-" + version + ".jar"), "com/example/" + artifactId + "/Type.class");
        Files.writeString(versionDir.resolve(artifactId + "-" + version + ".pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """
                        .formatted(artifactId, version));
    }

    private static void writeArtifactMetadata(Path repository, String artifactId, List<String> versions) throws IOException {
        Path artifactDir = repository.resolve("com/example/" + artifactId);
        String versionElements = versions.stream()
                .map(version -> "<version>" + version + "</version>")
                .collect(Collectors.joining());
        Files.writeString(artifactDir.resolve("maven-metadata.xml"),
                """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <versioning><versions>%s</versions></versioning>
                </metadata>
                """
                        .formatted(artifactId, versionElements));
    }

    private static void writeArchive(Path path, String entryName) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.closeEntry();
        }
    }

    private static RemoteRepository fileRepository(Path repository) {
        var policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        return new Builder("upstream", "default", repository.toUri()
                .toString())
                .setReleasePolicy(policy)
                .setSnapshotPolicy(new RepositoryPolicy(false, null, null))
                .build();
    }

    private static String elementText(Document document, String namespace, String localName) {
        return document.getElementsByTagNameNS(namespace, localName)
                       .item(0)
                       .getTextContent();
    }

    private static String checksumHex(String algorithm, byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm)
                .digest(content));
    }

    private static byte[] sha256(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
