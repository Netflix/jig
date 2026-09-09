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

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.module.ModulePomGenerator;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.SourceModuleFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModulePomGeneratorTest {

    @Test
    @SuppressWarnings("deprecation") // Maven 4 retains these accessors for Maven 3 POMs.
    void writesReactorAndModulePoms(@TempDir Path directory) throws Exception {
        Path project = directory.resolve("example");
        Path sources = project.resolve("src");
        Path application = Files.createDirectories(sources.resolve("com.example.application"));
        Path library = Files.createDirectories(sources.resolve("com.example.library"));
        Files.writeString(application.resolve("module-info.java"),
                """
                /** @release 25 */
                module com.example.application {
                    requires com.example.library;
                    requires static com.example.annotations; // @4.0
                    requires static transitive com.example.compile.api; // @5.0
                }
                """);
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}\n");
        Files.writeString(application.resolve("module-info.hash"), "com.example.transitive@5.0=module:sha256:" + "0".repeat(64) + "\n");
        ModuleFinder sourceFinder = SourceModuleFinder.of(sources);
        Configuration configuration = Configuration.resolve(sourceFinder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.ofSystem(), Set.of("com.example.application", "com.example.library"));
        var resolution = new ModuleResolution(
                sourceFinder,
                configuration,
                new LinkedHashSet<>(List.of("com.example.application", "com.example.library")),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of());
        Path moduleRepository = directory.resolve("repository/modules");

        ModulePomGenerator.generate(resolution, project, moduleRepository);

        var parent = readModel(project.resolve("pom.xml"));
        assertEquals("local", parent.getGroupId());
        assertEquals("example-parent", parent.getArtifactId());
        assertEquals("0", parent.getVersion());
        assertEquals("pom", parent.getPackaging());
        assertEquals(
                List.of("src/com.example.application/module-info.pom", "src/com.example.library/module-info.pom"),
                parent.getModules());

        var applicationPom = readModel(application.resolve("module-info.pom"));
        assertEquals("local", applicationPom.getParent()
                .getGroupId());
        assertEquals("example-parent", applicationPom.getParent()
                .getArtifactId());
        assertEquals("../../pom.xml", applicationPom.getParent()
                .getRelativePath());
        assertEquals("com.example", applicationPom.getGroupId());
        assertEquals("com.example.application", applicationPom.getArtifactId());
        assertEquals("0", applicationPom.getVersion());
        assertEquals("25", applicationPom.getProperties()
                .get("maven.compiler.release"));
        assertEquals("${project.basedir}", applicationPom.getBuild()
                .getSourceDirectory());
        var resource = applicationPom.getBuild()
                .getResources()
                .getFirst();
        assertEquals("${project.basedir}", resource.getDirectory());
        assertTrue(resource.getExcludes()
                           .contains("module-info.pom"));
        assertTrue(resource.getExcludes()
                           .contains("*.pom"));
        var libraryDependency = applicationPom.getDependencies().stream()
                .filter(dependency -> dependency.getArtifactId().equals("com.example.library"))
                .findFirst()
                .orElseThrow();
        assertEquals("0", libraryDependency.getVersion());
        var annotationsDependency = applicationPom.getDependencies().stream()
                .filter(dependency -> dependency.getArtifactId().equals("com.example.annotations"))
                .findFirst()
                .orElseThrow();
        assertEquals("true", annotationsDependency.getOptional());
        var compileApiDependency = applicationPom.getDependencies().stream()
                .filter(dependency -> dependency.getArtifactId().equals("com.example.compile.api"))
                .findFirst()
                .orElseThrow();
        assertEquals("5.0", compileApiDependency.getVersion());
        assertNull(compileApiDependency.getOptional());
        var transitiveDependency = applicationPom.getDependencies().stream()
                .filter(dependency -> dependency.getArtifactId().equals("com.example.transitive"))
                .findFirst()
                .orElseThrow();
        assertEquals("5.0", transitiveDependency.getVersion());
        assertEquals("true", transitiveDependency.getOptional());

        assertEquals(
                "-Dmaven.repo.local.tail=" + moduleRepository.toAbsolutePath().normalize() + System.lineSeparator(),
                Files.readString(project.resolve(".mvn/maven.config")));
    }

    @Test
    void requiresProjectDirectoryToContainSourceModules(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("sources");
        Path application = Files.createDirectories(sources.resolve("com.example.application"));
        Files.writeString(application.resolve("module-info.java"), "module com.example.application {}\n");
        ModuleFinder sourceFinder = SourceModuleFinder.of(sources);
        Configuration configuration = Configuration.resolve(sourceFinder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.ofSystem(), Set.of("com.example.application"));
        var resolution = new ModuleResolution(
                sourceFinder,
                configuration,
                new LinkedHashSet<>(List.of("com.example.application")),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of());

        var error = assertThrows(IllegalArgumentException.class,
                () -> ModulePomGenerator.generate(resolution, directory.resolve("project"), directory.resolve("repository/modules")));

        assertTrue(error.getMessage()
                        .contains("must contain"));
    }

    @Test
    void preservesMaintainedRootMetadata(@TempDir Path directory) throws Exception {
        Path module = Files.createDirectories(directory.resolve("com.example.application"));
        Files.writeString(module.resolve("module-info.java"), "module com.example.application {}\n");
        Files.writeString(directory.resolve("pom.xml"),
                """
                          <project xmlns="http://maven.apache.org/POM/4.0.0">
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>example-parent</artifactId>
                            <version>1.0-SNAPSHOT</version>
                            <name>Example</name>
                            <description>Example modules</description>
                          </project>
                          """);
        ModuleFinder sourceFinder = SourceModuleFinder.of(directory);
        Configuration configuration = Configuration.resolve(sourceFinder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.ofSystem(), Set.of("com.example.application"));
        var resolution = new ModuleResolution(
                sourceFinder,
                configuration,
                new LinkedHashSet<>(List.of("com.example.application")),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of());

        ModulePomGenerator.generate(resolution, directory, directory.resolve("repository"));

        Model root = readModel(directory.resolve("pom.xml"));
        assertEquals("com.example", root.getGroupId());
        assertEquals("example-parent", root.getArtifactId());
        assertEquals("1.0-SNAPSHOT", root.getVersion());
        assertEquals("Example", root.getName());
        assertEquals("Example modules", root.getDescription());
        assertEquals(List.of("com.example.application/module-info.pom"), root.getModules());
        Model modulePom = readModel(module.resolve("module-info.pom"));
        assertEquals("com.example", modulePom.getParent()
                .getGroupId());
        assertEquals("example-parent", modulePom.getParent()
                .getArtifactId());
        assertEquals("1.0-SNAPSHOT", modulePom.getParent()
                .getVersion());
        assertEquals("../pom.xml", modulePom.getParent()
                .getRelativePath());
    }

    private static Model readModel(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return new MavenStaxReader().read(input);
        }
    }
}
