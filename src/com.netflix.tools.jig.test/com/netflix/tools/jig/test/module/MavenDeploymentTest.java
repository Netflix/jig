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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDeploymentTest {
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
        Files.writeString(directory.resolve("metadata-parent.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example.metadata</groupId>
                  <artifactId>metadata-parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <publication.description>Example module family</publication.description>
                    <publication.url>https://example.com/modules</publication.url>
                  </properties>
                  <licenses>
                    <license>
                      <name>Apache-2.0</name>
                      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                    </license>
                  </licenses>
                </project>
                """);
        Path consumerPom = Files.writeString(artifacts.resolve("consumer.pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example.metadata</groupId>
                    <artifactId>metadata-parent</artifactId>
                    <version>1.0</version>
                    <relativePath>../metadata-parent.xml</relativePath>
                  </parent>
                  <artifactId>module-metadata</artifactId>
                  <name>${project.groupId}:${project.artifactId}</name>
                  <description>${publication.description}</description>
                  <url>${publication.url}</url>
                </project>
                """);

        try (var session = ModuleRepositorySession.create(directory.resolve("session"), List.of());
             var deployment = MavenDeployment.create(artifacts, consumerPom, session)) {
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
            assertEquals("com.example:com.example.application", model.getName());
            assertEquals("Example module family", model.getDescription());
            assertEquals("https://example.com/modules", model.getUrl());
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
    void deploysToARepositorySelectedByPath(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, artifacts.resolve("com.example.application.jar"));
        Files.writeString(artifacts.resolve("com.example.application-linux-x86_64.jmod"), "linux jmod");
        Files.writeString(artifacts.resolve("consumer.pom"),
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

    @Test
    void rejectsConsumerPomForInstall(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, artifacts.resolve("com.example.application.jar"));
        var output = new StringWriter();
        var errors = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(output, true),
                new PrintWriter(errors, true),
                "maven",
                "install",
                "--merge-consumer-pom",
                directory.resolve("consumer.pom").toString(),
                artifacts.toString());

        assertEquals(2, result);
        assertTrue(errors.toString().contains("--merge-consumer-pom applies only to Maven deployment"),
                errors.toString());
    }

    @Test
    void installsAndDeploysThroughResolver(@TempDir Path directory) throws Exception {
        Path artifacts = Files.createDirectory(directory.resolve("artifacts"));
        createModuleJar(directory, artifacts.resolve("com.example.application.jar"));
        Path sessionDirectory = directory.resolve("session");

        try (var session = ModuleRepositorySession.create(sessionDirectory, List.of());
             var deployment = MavenDeployment.create(artifacts, null, session)) {
            session.install(deployment.artifacts());
        }

        Path installed = sessionDirectory.resolve("repository/maven/com/example/com.example.application/1.2.3");
        assertTrue(Files.isRegularFile(installed.resolve("com.example.application-1.2.3.jar")));
        assertTrue(Files.isRegularFile(installed.resolve("com.example.application-1.2.3.pom")));
        assertTrue(Files.isRegularFile(installed.getParent()
                .resolve("maven-metadata-local.xml")));

        Path repository = directory.resolve("deployed");
        try (var session = ModuleRepositorySession.create(directory.resolve("deployment-session"), List.of());
             var deployment = MavenDeployment.create(artifacts, null, session)) {
            session.deploy(deployment.artifacts(), new Builder("test", "default", repository.toUri()
                    .toString())
                    .build());
        }

        Path deployed = repository.resolve("com/example/com.example.application/1.2.3");
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3.jar")));
        assertTrue(Files.isRegularFile(deployed.resolve("com.example.application-1.2.3.pom")));
    }

    private static void createModuleJar(Path directory, Path jar) throws Exception {
        Path source = Files.createDirectories(directory.resolve("source"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}\n");
        Path classes = Files.createDirectories(directory.resolve("classes"));
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
                "com.example.application",
                ModuleFinder.of(jar)
                        .findAll()
                        .iterator()
                        .next()
                        .descriptor()
                        .name());
    }

    private static Model readModel(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return new MavenStaxReader().read(input);
        }
    }
}
