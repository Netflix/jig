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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;

import com.netflix.tools.jig.Jig;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.module.MavenDeployment;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDeploymentTest {
    @Test
    void publishesNamedAutomaticModule(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createAutomaticModuleJar("com.example.application",
                artifacts.resolve("com.example.application.jar"));
        Files.writeString(artifacts.resolve("com.example.application.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.application</artifactId>
                  <version>1.2.3</version>
                  <packaging>jar</packaging>
                  <name>Example application</name>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>example-library</artifactId>
                      <version>4.5.6</version>
                      <scope>compile</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>com.acme.library</artifactId>
                      <version>7.8.9</version>
                    </dependency>
                    <dependency>
                      <groupId>java.sql</groupId>
                      <artifactId>java.sql</artifactId>
                      <version>25</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path repository = directory.resolve("repository");
        createMavenAutomaticModule(repository, "com.acme", "library", "7.8.9",
                "com.acme.library");
        var upstream = new Builder("test", "default", repository.toUri().toString()).build();

        try (var session = ModuleRepositorySession.create(directory.resolve("session"), List.of(upstream));
             var deployment = MavenDeployment.create(artifacts, session, "1.2.3")) {
            Map<String, Artifact> deployed = deployment.artifacts().stream()
                    .collect(Collectors.toMap(artifact -> artifact.getExtension() + ":" + artifact.getClassifier(), artifact -> artifact));
            assertEquals(Set.of("pom:", "jar:"), deployed.keySet());
            Artifact jar = deployed.get("jar:");
            assertEquals("com.example", jar.getGroupId());
            assertEquals("com.example.application", jar.getArtifactId());
            assertEquals("1.2.3", jar.getVersion());
            Model consumer = readModel(deployed.get("pom:").getPath());
            assertEquals("Example application", consumer.getName());
            assertEquals(2, consumer.getDependencies().size());
            var ordinary = consumer.getDependencies().stream()
                    .filter(dependency -> dependency.getArtifactId().equals("example-library"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("org.example", ordinary.getGroupId());
            assertEquals("4.5.6", ordinary.getVersion());
            var module = consumer.getDependencies().stream()
                    .filter(dependency -> dependency.getArtifactId().equals("library"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("com.acme", module.getGroupId());
            assertEquals("7.8.9", module.getVersion());
        }
    }

    @Test
    void rejectsAutomaticModuleWithFilenameDerivedName(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        try (var _ = new JarOutputStream(Files.newOutputStream(
                artifacts.resolve("com.example.application.jar")))) {}

        try (var session = ModuleRepositorySession.create(directory.resolve("session"), List.of())) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> MavenDeployment.create(artifacts, session, "1.2.3"));
            assertEquals("Automatic module has no Automatic-Module-Name: com.example.application.jar",
                    failure.getMessage());
        }
    }

    @Test
    void createsConsumerPomFromFlatModuleArtifacts(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, artifacts.resolve("com.example.application.jar"));
        Files.writeString(artifacts.resolve("com.example.application-sources.jar"), "sources");
        Files.writeString(artifacts.resolve("com.example.application-javadoc.jar"), "javadoc");
        Files.writeString(artifacts.resolve("com.example.application-tests.jar"), "tests");
        Files.writeString(artifacts.resolve("com.example.application.jmod"), "jmod");
        Files.writeString(artifacts.resolve("com.example.application-linux-x86_64.jmod"), "linux jmod");
        Files.writeString(artifacts.resolve("com.example.application-sbom.json"), "sbom");
        Files.writeString(artifacts.resolve("com.example.application.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <name>Example application</name>
                  <description>Example application module</description>
                  <url>https://example.com/application</url>
                  <licenses>
                    <license>
                      <name>Apache-2.0</name>
                      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                    </license>
                  </licenses>
                  <developers>
                    <developer>
                      <name>Example developer</name>
                    </developer>
                  </developers>
                  <scm>
                    <connection>scm:git:https://example.com/application.git</connection>
                    <developerConnection>scm:git:ssh://git@example.com/application.git</developerConnection>
                    <url>https://example.com/application</url>
                  </scm>
                </project>
                """);

        try (var session = ModuleRepositorySession.create(directory.resolve("session"), List.of());
             var deployment = MavenDeployment.create(artifacts, session)) {
            Map<String, Artifact> deployed = deployment.artifacts().stream()
                    .collect(Collectors.toMap(artifact -> artifact.getExtension() + ":" + artifact.getClassifier(), artifact -> artifact));
            assertEquals(Set.of("pom:",
                    "jar:",
                    "jar:sources",
                    "jar:javadoc",
                    "jar:tests",
                    "jmod:",
                    "jmod:linux-x86_64",
                    "json:sbom"), deployed.keySet());
            Artifact pom = deployed.get("pom:");
            assertFalse(Files.readString(pom.getPath()).contains("<!--"));
            assertEquals("com.example", pom.getGroupId());
            assertEquals("com.example.application", pom.getArtifactId());
            assertEquals("1.2.3", pom.getVersion());
            Model model = readModel(pom.getPath());
            assertEquals("Example application", model.getName());
            assertEquals("Example application module", model.getDescription());
            assertEquals("https://example.com/application", model.getUrl());
            assertEquals("Apache-2.0",
                    model.getLicenses()
                         .getFirst()
                         .getName());
            assertEquals("com.example.application", model.getArtifactId());
            assertTrue(model.getDependencies()
                            .isEmpty());
        }
    }

    @Test
    void appliesPublicationMetadataToTheMatchingModule(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, "com.example.api", artifacts.resolve("com.example.api.jar"));
        createModuleJar(directory, "com.example.runtime", artifacts.resolve("com.example.runtime.jar"));
        Files.writeString(artifacts.resolve("com.example.api.pom"), metadata("Example API"));
        Files.writeString(artifacts.resolve("com.example.runtime.pom"), metadata("Example runtime"));

        try (var session = ModuleRepositorySession.create(directory.resolve("session"), List.of());
             var deployment = MavenDeployment.create(artifacts, session)) {
            Artifact apiPom = deployment.artifacts().stream()
                    .filter(artifact -> artifact.getArtifactId().equals("com.example.api"))
                    .filter(artifact -> artifact.getExtension().equals("pom"))
                    .findFirst()
                    .orElseThrow();
            Artifact runtimePom = deployment.artifacts().stream()
                    .filter(artifact -> artifact.getArtifactId().equals("com.example.runtime"))
                    .filter(artifact -> artifact.getExtension().equals("pom"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Example API", readModel(apiPom.getPath()).getName());
            assertEquals("Example runtime", readModel(runtimePom.getPath()).getName());
        }
    }

    @Test
    void deploysNamedAutomaticModule(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createAutomaticModuleJar("com.example.application",
                artifacts.resolve("com.example.application.jar"));
        Path repository = directory.resolve("deployed");
        var output = new StringWriter();
        var errors = new StringWriter();

        int result = new Jig().run(new PrintWriter(output, true), new PrintWriter(errors, true),
                "maven", "deploy", "--module-version", "1.2.3", "--repository",
                repository.toString(), artifacts.toString());

        assertEquals(0, result, errors.toString());
        Path deployed = repository.resolve("com/example/com.example.application/1.2.3");
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3.jar")));
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3.pom")));
    }

    @Test
    void deploysToARepositorySelectedByPath(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, artifacts.resolve("com.example.application.jar"));
        Files.writeString(artifacts.resolve("com.example.application-linux-x86_64.jmod"), "linux jmod");
        Files.writeString(artifacts.resolve("com.example.application.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <name>Example application</name>
                </project>
                """);
        Path repository = directory.resolve("deployed");
        var output = new StringWriter();
        var errors = new StringWriter();

        int result = new Jig().run(new PrintWriter(output, true), new PrintWriter(errors, true), "maven", "deploy",
                "--repository", repository.toString(), artifacts.toString());

        assertEquals(0, result, errors.toString());
        Path deployed = repository.resolve("com/example/com.example.application/1.2.3");
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3.jar")));
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3-linux-x86_64.jmod")));
        Path pom = deployed.resolve("com.example.application-1.2.3.pom");
        assertTrue(Files.isRegularFile(pom));
        assertEquals("Example application", readModel(pom).getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"parent", "groupId", "artifactId", "version", "packaging", "dependencies",
            "properties", "distributionManagement", "build", "profiles"})
    void rejectsElementsOutsideThePublicationMetadataAllowList(String element, @TempDir Path directory)
            throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, artifacts.resolve("com.example.application.jar"));
        Files.writeString(artifacts.resolve("com.example.application.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <%s/>
                </project>
                """.formatted(element));

        try (var session = ModuleRepositorySession.create(directory.resolve("session"), List.of())) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> MavenDeployment.create(artifacts, session));
            assertEquals("com.example.application.pom contains unsupported element " + element,
                    failure.getMessage());
        }
    }

    @Test
    void installsAndDeploysThroughResolver(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, artifacts.resolve("com.example.application.jar"));
        Path sessionDirectory = directory.resolve("session");

        try (var session = ModuleRepositorySession.create(sessionDirectory, List.of());
             var deployment = MavenDeployment.create(artifacts, session)) {
            session.install(deployment.artifacts());
        }

        Path installed = sessionDirectory.resolve("repository/maven/com/example/com.example.application/1.2.3");
        assertTrue(Files.isRegularFile(installed.resolve("com.example.application-1.2.3.jar")));
        assertTrue(Files.isRegularFile(installed.resolve("com.example.application-1.2.3.pom")));
        assertTrue(Files.isRegularFile(installed.getParent()
                .resolve("maven-metadata-local.xml")));

        Path repository = directory.resolve("deployed");
        try (var session = ModuleRepositorySession.create(directory.resolve("deployment-session"), List.of());
             var deployment = MavenDeployment.create(artifacts, session)) {
            session.deploy(deployment.artifacts(), new Builder("test", "default", repository.toUri()
                    .toString())
                    .build());
        }

        Path deployed = repository.resolve("com/example/com.example.application/1.2.3");
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3.jar")));
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3.pom")));
    }

    private static void createModuleJar(Path directory, Path jar) throws Exception {
        createModuleJar(directory, "com.example.application", jar);
    }

    private static void createModuleJar(Path directory, String moduleName, Path jar) throws Exception {
        Path source = Files.createDirectories(directory.resolve("source").resolve(moduleName));
        Files.writeString(source.resolve("module-info.java"), "module " + moduleName + " {}\n");
        Path classes = Files.createDirectories(directory.resolve("classes").resolve(moduleName));
        int result = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "--module-version",
                "1.2.3",
                "-d",
                classes.toString(),
                source.resolve("module-info.java").toString());
        assertEquals(0, result);
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(path)
                        .toString()
                        .replace('\\', '/')));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        assertEquals(
                moduleName,
                ModuleFinder.of(jar)
                        .findAll()
                        .iterator()
                        .next()
                        .descriptor()
                        .name());
    }

    private static void createAutomaticModuleJar(String moduleName, Path jar) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(jar), manifest)) {}
        assertTrue(ModuleFinder.of(jar)
                               .find(moduleName)
                               .orElseThrow()
                               .descriptor()
                               .isAutomatic());
    }

    private static void createMavenAutomaticModule(Path repository, String groupId,
            String artifactId, String version, String moduleName) throws Exception {
        Path directory = repository.resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version);
        Files.createDirectories(directory);
        createAutomaticModuleJar(moduleName,
                directory.resolve(artifactId + "-" + version + ".jar"));
        Files.writeString(directory.resolve(artifactId + "-" + version + ".pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version));
    }

    private static String metadata(String name) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <name>%s</name>
                </project>
                """.formatted(name);
    }

    private static Model readModel(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return new MavenStaxReader().read(input);
        }
    }
}
