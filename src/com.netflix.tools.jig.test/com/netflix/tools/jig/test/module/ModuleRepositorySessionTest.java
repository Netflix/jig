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

import java.io.IOException;
import java.lang.module.FindException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes.Name;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import com.netflix.module.ModuleHash;
import com.netflix.tools.jig.internal.org.apache.maven.settings.RepositoryPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.module.ModuleOrigin;
import com.netflix.tools.jig.module.ModulePathReference;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.SourceModuleFinder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRepositorySessionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void listVersionsAlwaysRefreshesRepositoryMetadata() throws Exception {
        var repository = temporaryDirectory.resolve("versions-repository");
        publishVersion(repository, "1.0", List.of("1.0"));
        var remote = new Builder("test", "default", repository.toUri()
                .toString())
                .build();

        try (var session = ModuleRepositorySession.create(temporaryDirectory.resolve("versions-cache"), List.of(remote))) {
            assertEquals(Set.of("1.0"), Set.copyOf(session.listVersions("com.example.library")));

            publishVersion(repository, "1.1", List.of("1.0", "1.1"));

            assertEquals(Set.of("1.0", "1.1"), Set.copyOf(session.listVersions("com.example.library")));
        }
    }

    @Test
    void mergesDistributionAndUserMavenSettings() throws Exception {
        Path javaHome = temporaryDirectory.resolve("jdk");
        Path distributionSettings = javaHome.resolve("conf/com.netflix.tools.jig/settings.xml");
        Files.createDirectories(distributionSettings.getParent());
        Files.writeString(distributionSettings,
                """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <mirrors>
                    <mirror>
                      <id>company</id>
                      <url>https://artifacts.example.com/maven</url>
                      <mirrorOf>*</mirrorOf>
                    </mirror>
                  </mirrors>
                  <servers>
                    <server>
                      <id>company</id>
                      <username>distribution-user</username>
                    </server>
                  </servers>
                </settings>
                """);
        Path userSettings = temporaryDirectory.resolve("home/.m2/settings.xml");
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings,
                """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <localRepository>/user/repository</localRepository>
                  <servers>
                    <server>
                      <id>company</id>
                      <username>user</username>
                      <password>token</password>
                    </server>
                  </servers>
                </settings>
                """);

        var settings = ModuleRepositorySession.readSettings(distributionSettings, userSettings);

        assertEquals("/user/repository", settings.getLocalRepository());
        assertEquals("https://artifacts.example.com/maven",
                settings.getMirrors()
                        .getFirst()
                        .getUrl());
        assertEquals("user",
                settings.getServers()
                        .getFirst()
                        .getUsername());
        assertEquals("token",
                settings.getServers()
                        .getFirst()
                        .getPassword());
    }

    @Test
    void preservesMavenSettingsExpressions(@TempDir Path directory) throws Exception {
        Path userSettings = directory.resolve(".m2/settings.xml");
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings,
                """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <servers>
                    <server>
                      <id>github</id>
                      <username>${env.GITHUB_ACTOR}</username>
                      <password>${env.GITHUB_TOKEN}</password>
                    </server>
                  </servers>
                </settings>
                """);

        var settings = ModuleRepositorySession.readSettings(directory.resolve("distribution-settings.xml"), userSettings);

        assertEquals("${env.GITHUB_ACTOR}",
                settings.getServers()
                        .getFirst()
                        .getUsername());
        assertEquals("${env.GITHUB_TOKEN}",
                settings.getServers()
                        .getFirst()
                        .getPassword());
    }

    @Test
    void decryptsMavenSettingsCredentials(@TempDir Path directory) throws Exception {
        Path userSettings = directory.resolve(".m2/settings.xml");
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings,
                """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <servers>
                    <server>
                      <id>central</id>
                      <username>token-user</username>
                      <password>{L6L/HbmrY+cH+sNkphnq3fguYepTpM04WlIXb8nB1pk=}</password>
                      <passphrase>{L6L/HbmrY+cH+sNkphnq3fguYepTpM04WlIXb8nB1pk=}</passphrase>
                    </server>
                  </servers>
                  <proxies>
                    <proxy>
                      <id>example</id>
                      <active>true</active>
                      <protocol>https</protocol>
                      <host>proxy.example.com</host>
                      <password>{L6L/HbmrY+cH+sNkphnq3fguYepTpM04WlIXb8nB1pk=}</password>
                    </proxy>
                  </proxies>
                </settings>
                """);
        Files.writeString(userSettings.resolveSibling("settings-security.xml"),
                """
                <settingsSecurity>
                  <master>{KDvsYOFLlXgH4LU8tvpzAGg5otiosZXvfdQq0yO86LU=}</master>
                </settingsSecurity>
                """);

        var settings = ModuleRepositorySession.readSettings(directory.resolve("distribution-settings.xml"), userSettings);

        assertEquals("password",
                settings.getServers()
                        .getFirst()
                        .getPassword());
        assertEquals("password",
                settings.getServers()
                        .getFirst()
                        .getPassphrase());
        assertEquals("password",
                settings.getProxies()
                        .getFirst()
                        .getPassword());
    }

    @Test
    void distributionSettingsUseTheModuleNamespace() {
        Path javaHome = temporaryDirectory.resolve("jdk");

        assertEquals(javaHome.resolve("conf/com.netflix.tools.jig/settings.xml"), ModuleRepositorySession.distributionSettings(javaHome));
    }

    @Test
    void cacheDirectoryUsesLocalApplicationDataOnWindows() {
        Path userHome = temporaryDirectory.resolve("home");
        Path localApplicationData = temporaryDirectory.resolve("local-application-data");

        assertEquals(localApplicationData.resolve("com.netflix.tools.jig"), ModuleRepositorySession.cacheDirectory("Windows 11", userHome, Map.of("LOCALAPPDATA", localApplicationData.toString())));
        assertEquals(userHome.resolve("AppData/Local/com.netflix.tools.jig"), ModuleRepositorySession.cacheDirectory("Windows 11", userHome, Map.of()));
    }

    @Test
    void cacheDirectoryUsesXdgConventionsOnOtherPlatforms() {
        Path userHome = temporaryDirectory.resolve("home");
        Path xdgCache = temporaryDirectory.resolve("xdg-cache");

        assertEquals(xdgCache.resolve("com.netflix.tools.jig"), ModuleRepositorySession.cacheDirectory("Linux", userHome, Map.of("XDG_CACHE_HOME", xdgCache.toString())));
        assertEquals(userHome.resolve(".cache/com.netflix.tools.jig"), ModuleRepositorySession.cacheDirectory("Mac OS X", userHome, Map.of()));
    }

    @Test
    void mavenRepositoryPolicyUsesResolverDefaultsWhenValuesAreOmitted() {
        var settingsPolicy = new RepositoryPolicy();
        settingsPolicy.setEnabled(true);
        settingsPolicy.setUpdatePolicy("");
        settingsPolicy.setChecksumPolicy("");

        var policy = ModuleRepositorySession.repositoryPolicy(settingsPolicy, true);

        assertEquals("daily", policy.getArtifactUpdatePolicy());
        assertEquals("warn", policy.getChecksumPolicy());
    }

    @Test
    void looksUpTheModuleNameOfAMavenPackage() throws Exception {
        Path repository = temporaryDirectory.resolve("repository");
        Path version = Files.createDirectories(repository.resolve("com/example/example-library/1.0.0"));
        Files.writeString(version.resolve("example-library-1.0.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>example-library</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "com.example.library");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", "com.example.alternative");
        try (var _ = new JarOutputStream(Files.newOutputStream(version.resolve("example-library-1.0.0.jar")), manifest)) {
            // The manifest is sufficient for automatic module identification.
        }
        var remote = new Builder("test", "default", repository.toUri()
                .toString())
                .build();

        String repositoryUrl = URLEncoder.encode(repository.toUri()
                .toString(),
                StandardCharsets.UTF_8);
        ModuleOrigin identification = ModuleOrigin.parse("pkg:maven/com.example/example-library@1.0.0?repository_url=" + repositoryUrl);
        try (var session = ModuleRepositorySession.create(temporaryDirectory.resolve("local"), List.of(remote))) {
            assertEquals("com.example.library", session.lookupModule(identification));

            ModuleOrigin unknownRepository = ModuleOrigin.parse("pkg:maven/com.example/example-library@1.0.0?" + "repository_url=https%3A%2F%2Funconfigured.example.com%2Fmaven");
            assertThrows(FindException.class, () -> session.lookupModule(unknownRepository));

            Path unrelatedVersion = Files.createDirectories(repository.resolve("unrelated/example/odd-artifact/1.0.0"));
            Files.writeString(unrelatedVersion.resolve("odd-artifact-1.0.0.pom"),
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>unrelated.example</groupId>
                      <artifactId>odd-artifact</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            var unrelatedManifest = new Manifest();
            unrelatedManifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
            unrelatedManifest.getMainAttributes().putValue("Automatic-Module-Name", "com.example.unrelated");
            try (var _ = new JarOutputStream(Files.newOutputStream(unrelatedVersion.resolve("odd-artifact-1.0.0.jar")), unrelatedManifest)) {
                // The manifest is sufficient for automatic module identification.
            }
            ModuleOrigin unrelated = ModuleOrigin.parse("pkg:maven/unrelated.example/odd-artifact@1.0.0?repository_url=" + repositoryUrl);
            assertThrows(FindException.class, () -> session.lookupModule(unrelated));
        }
    }

    @Test
    void looksUpOsgiBundleSymbolicNameWhenNoJavaModuleNameIsDeclared() throws Exception {
        Path repository = temporaryDirectory.resolve("osgi-repository");
        Path version = Files.createDirectories(repository.resolve("com/example/com.example.special/1.0"));
        Files.writeString(version.resolve("com.example.special-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.special</artifactId>
                  <version>1.0</version>
                </project>
                """);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", "com.example.special;singleton:=true");
        try (var _ = new JarOutputStream(Files.newOutputStream(version.resolve("com.example.special-1.0.jar")), manifest)) {}
        var remote = new Builder("test", "default", repository.toUri()
                .toString())
                .build();
        String repositoryUrl = URLEncoder.encode(repository.toUri()
                .toString(),
                StandardCharsets.UTF_8);
        ModuleOrigin identification = ModuleOrigin.parse("pkg:maven/com.example/com.example.special@1.0?repository_url=" + repositoryUrl);

        try (var session = ModuleRepositorySession.create(temporaryDirectory.resolve("osgi-local"), List.of(remote))) {
            assertEquals("com.example.special", session.lookupModule(identification));
        }
    }

    @Test
    void looksUpTheLatestModuleNameForAnUnversionedMavenPackage() throws Exception {
        Path repository = temporaryDirectory.resolve("latest-lookup-repository");
        publishLookupArtifact(repository, "2.0.0", "com.example.library", List.of("1.0.0", "2.0.0"));
        var remote = new Builder("test", "default", repository.toUri()
                .toString())
                .build();
        String repositoryUrl = URLEncoder.encode(repository.toUri()
                .toString(),
                StandardCharsets.UTF_8);
        ModuleOrigin identification = ModuleOrigin.parse("pkg:maven/com.example/example-library?repository_url=" + repositoryUrl);

        try (var session = ModuleRepositorySession.create(temporaryDirectory.resolve("latest-lookup-cache"), List.of(remote))) {
            assertEquals("com.example.library", session.lookupModule(identification));
        }
    }

    @Test
    void reusesResolvedModuleWithCachedMavenMetadata() throws Exception {
        Path upstream = temporaryDirectory.resolve("canonical-upstream");
        Path version = Files.createDirectories(upstream.resolve("com/example/com.example.library/1.0"));
        Files.writeString(version.resolve("com.example.library-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.library</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>com.example.child</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(version.getParent()
                .resolve("maven-metadata.xml"),
                """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.library</artifactId>
                  <versioning>
                    <latest>1.0</latest>
                    <release>1.0</release>
                    <versions><version>1.0</version></versions>
                  </versioning>
                </metadata>
                """);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "com.example.library");
        try (var _ = new JarOutputStream(Files.newOutputStream(version.resolve("com.example.library-1.0.jar")), manifest)) {}
        Path childVersion = Files.createDirectories(upstream.resolve("com/example/com.example.child/1.0"));
        Files.writeString(childVersion.resolve("com.example.child-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.child</artifactId>
                  <version>1.0</version>
                </project>
                """);
        var childManifest = new Manifest();
        childManifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        childManifest.getMainAttributes().putValue("Automatic-Module-Name", "com.example.child");
        try (var _ = new JarOutputStream(Files.newOutputStream(childVersion.resolve("com.example.child-1.0.jar")), childManifest)) {}

        Path sources = temporaryDirectory.resolve("canonical-sources");
        Path application = Files.createDirectories(sources.resolve("com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires com.example.library; // @1.0
                }
                """);
        Path cache = temporaryDirectory.resolve("canonical-cache");
        var missing = new Builder("missing", "default", temporaryDirectory.resolve("missing")
                .toUri()
                .toString())
                .build();
        var published = new Builder("published", "default", upstream.toUri()
                .toString())
                .build();
        Path artifact = Path.of("com/example/com.example.library/1.0/com.example.library-1.0.jar");
        Path mavenArtifact = cache.resolve("repository/maven").resolve(artifact);
        Path frontendArtifact;
        ModuleHash originalHash;

        try (var session = ModuleRepositorySession.create(cache, List.of(missing, published))) {
            var resolution = assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> ModuleResolution.resolve(session, SourceModuleFinder.of(sources), List.of("com.example.application"), false,
                            false));
            assertTrue(resolution.configuration()
                                 .findModule("com.example.library")
                                 .isPresent());
            assertTrue(resolution.configuration()
                                 .findModule("com.example.child")
                                 .isPresent());
            assertTrue(Files.isRegularFile(mavenArtifact));
            var library = resolution.finder()
                                    .find("com.example.library")
                                    .orElseThrow();
            frontendArtifact = assertInstanceOf(ModulePathReference.class, library).modulePath();
            originalHash = ModuleHash.moduleSha256(library);
            for (var reference : resolution.finder().findAll()) {
                if (reference instanceof ModulePathReference module && reference != library) {
                    module.modulePath();
                }
            }
            assertTrue(Files.isRegularFile(frontendArtifact));
            assertTrue(frontendArtifact.startsWith(cache.resolve("repository/modules")));
        }

        assertTrue(Files.isRegularFile(mavenArtifact));
        assertTrue(Files.isRegularFile(frontendArtifact));
        assertTrue(Files.readString(mavenArtifact.resolveSibling("_remote.repositories"))
                .contains("com.example.library-1.0.jar>published="));
        assertTrue(Files.isRegularFile(cache.resolve("repository/locations/com/example/module")
                .resolve("com.example.library/1.0/com.example.library-1.0.pom")));

        deleteTree(upstream);
        try (var session = ModuleRepositorySession.create(cache, List.of(published))) {
            var resolution = ModuleResolution.resolve(session, SourceModuleFinder.of(sources), List.of("com.example.application"), false,
                    false);
            var cached = resolution.finder()
                                   .find("com.example.library")
                                   .orElseThrow();
            assertTrue(resolution.configuration()
                                 .findModule("com.example.child")
                                 .isPresent());
            var modulePath = assertInstanceOf(ModulePathReference.class, cached).modulePath();
            assertTrue(cached.location()
                             .isEmpty());
            assertTrue(Files.isRegularFile(modulePath));
            assertEquals(originalHash, ModuleHash.moduleSha256(cached));
            try (var reader = cached.open()) {
                assertTrue(reader.find("META-INF/MANIFEST.MF")
                                 .isPresent());
            }
        }
    }

    @Test
    void missingArtifactsAreCachedAcrossRepositorySessions() throws Exception {
        Path cache = temporaryDirectory.resolve("missing-artifact-cache");
        var artifact = new DefaultArtifact("com.example", "missing-library", "jar", "1.0");

        try (var repository = new MissingRepository()) {
            try (var session = ModuleRepositorySession.create(cache, List.of(repository.remote()));
                 var proxy = session.newModuleTransporter()) {
                assertThrows(IOException.class, () -> proxy.lookupModuleName(artifact));
            }
            int coldRequests = repository.requests();
            assertTrue(coldRequests > 0);

            try (var session = ModuleRepositorySession.create(cache, List.of(repository.remote()));
                 var proxy = session.newModuleTransporter()) {
                assertThrows(IOException.class, () -> proxy.lookupModuleName(artifact));
            }

            assertEquals(coldRequests, repository.requests());
        }
    }

    @Test
    void smartExecutorCompletesFailedFutureWhenTaskThrowsError() throws Exception {
        try (var executor = ModuleRepositorySession.virtualThreadExecutor()) {
            var future = executor.submit(() -> {
                throw new AssertionError("linkage failure");
            });

            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertInstanceOf(AssertionError.class, failure.getCause());
        }
    }

    @Test
    void resolvesCanonicalJmodsForTargetPlatform() throws Exception {
        Path repository = temporaryDirectory.resolve("jmods");
        Path version = Files.createDirectories(repository.resolve("com/example/com.example.tool/1.0"));
        Path jmod = version.resolve("com.example.tool-1.0-osx-aarch_64.jmod");
        Files.writeString(jmod, "jmod");
        var remote = new Builder("test", "default", repository.toUri()
                .toString())
                .build();

        try (var session = ModuleRepositorySession.create(temporaryDirectory.resolve("jmod-local"), List.of(remote))) {
            Map<String, Path> paths = session.resolveJmodPath(Map.of("com.example.tool", "1.0", "com.example.jar.only", "1.0"), "osx-aarch_64");
            assertEquals(Set.of("com.example.tool"), paths.keySet());
            Path resolved = paths.get("com.example.tool");
            assertEquals(jmod.getFileName(), resolved.getFileName());
            assertEquals("jmod", Files.readString(resolved));
        }
    }

    @Test
    void resolvesPlatformJarWhenPublishedAndFallsBackWhenMissing() throws Exception {
        var repository = temporaryDirectory.resolve("platform-jars");
        var nativeVersion = Files.createDirectories(repository.resolve("com/example/com.example.nativebinding/1.0"));
        Files.writeString(nativeVersion.resolve("com.example.nativebinding-1.0.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.nativebinding</artifactId>
                  <version>1.0</version>
                </project>
                """);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "com.example.nativebinding");
        try (var _ = new JarOutputStream(Files.newOutputStream(nativeVersion.resolve("com.example.nativebinding-1.0.jar")), manifest)) {}
        var nativeJar = nativeVersion.resolve("com.example.nativebinding-1.0-osx-aarch_64.jar");
        Files.writeString(nativeJar, "native");
        var remote = new Builder("test", "default", repository.toUri()
                .toString())
                .build();

        try (var session = ModuleRepositorySession.create(temporaryDirectory.resolve("platform-local"), List.of(remote))) {
            var paths = session.resolveTargetJarPath(Map.of("com.example.nativebinding", "1.0", "com.example.universal", "1.0"), "osx-aarch_64");

            assertEquals(Set.of("com.example.nativebinding"), paths.keySet());
            assertEquals(nativeJar.getFileName(),
                    paths.get("com.example.nativebinding").getFileName());
        }
    }

    private static final class MissingRepository implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        private MissingRepository() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                try (exchange) {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            server.start();
        }

        private RemoteRepository remote() {
            return new Builder("missing", "default", "http://127.0.0.1:" + server.getAddress().getPort() + "/")
                    .build();
        }

        private int requests() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void publishVersion(Path repository, String version, List<String> versions) throws Exception {
        var artifact = "com.example.library";
        var root = repository.resolve("com/example").resolve(artifact);
        var versionDirectory = Files.createDirectories(root.resolve(version));
        try (var _ = new JarOutputStream(Files.newOutputStream(versionDirectory.resolve(artifact + "-" + version + ".jar")))) {}
        Files.writeString(versionDirectory.resolve(artifact + "-" + version + ".pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.library</artifactId>
                  <version>%s</version>
                </project>
                """
                        .formatted(version));
        var entries = versions.stream()
                .map(entry -> "      <version>" + entry + "</version>")
                .collect(Collectors.joining("\n"));
        Files.writeString(root.resolve("maven-metadata.xml"),
                """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.library</artifactId>
                  <versioning>
                    <latest>%s</latest>
                    <release>%s</release>
                    <versions>
                %s
                    </versions>
                  </versioning>
                </metadata>
                """
                        .formatted(version, version, entries));
    }

    private static void publishLookupArtifact(Path repository, String version, String moduleName,
            List<String> versions)
            throws Exception {
        var root = repository.resolve("com/example/example-library");
        var versionDirectory = Files.createDirectories(root.resolve(version));
        Files.writeString(versionDirectory.resolve("example-library-" + version + ".pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>example-library</artifactId>
                  <version>%s</version>
                </project>
                """
                        .formatted(version));
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(versionDirectory.resolve("example-library-" + version + ".jar")), manifest)) {}
        var entries = versions.stream()
                .map(entry -> "      <version>" + entry + "</version>")
                .collect(Collectors.joining("\n"));
        Files.writeString(root.resolve("maven-metadata.xml"),
                """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>example-library</artifactId>
                  <versioning>
                    <latest>%s</latest>
                    <release>%s</release>
                    <versions>
                %s
                    </versions>
                  </versioning>
                </metadata>
                """
                        .formatted(versions.getLast(), versions.getLast(), entries));
    }
}
