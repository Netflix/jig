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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;

import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.module.ConsumerPomGenerator;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.SourceModuleFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsumerPomGeneratorTest {

    @Test
    void writesCanonicalModuleDependencies(@TempDir Path directory) throws Exception {
        Path sourceDirectory = directory.resolve("src/com.example.application");
        Files.createDirectories(sourceDirectory);
        Files.writeString(sourceDirectory.resolve("module-info.java"),
                """
                module com.example.application {
                    requires com.example.library; // @2.3
                    requires static com.example.annotations; // @4.0
                    requires java.sql; // @25
                }
                """);
        ModuleFinder sourceFinder = SourceModuleFinder.of(directory.resolve("src"));

        Path library = directory.resolve("com.example.library-2.3.jar");
        try (var _ = new JarOutputStream(Files.newOutputStream(library))) {}
        Path annotations = directory.resolve("com.example.annotations-4.0.jar");
        try (var _ = new JarOutputStream(Files.newOutputStream(annotations))) {}
        ModuleFinder finder = ModuleFinder.compose(sourceFinder, ModuleFinder.of(library, annotations));
        Configuration configuration = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.ofSystem(), Set.of("com.example.application"));
        var resolution = new ModuleResolution(
                finder,
                configuration,
                new LinkedHashSet<>(List.of("com.example.application")),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of());
        Path output = directory.resolve("poms");

        ConsumerPomGenerator.generate(resolution, "1.0", output);

        Model model;
        try (var input = Files.newInputStream(output.resolve("com.example.application/com.example.application-1.0.pom"))) {
            model = new MavenStaxReader().read(input);
        }
        var dependency = model.getDependencies().stream()
                .filter(value -> value.getArtifactId().equals("com.example.library"))
                .findFirst()
                .orElseThrow();
        assertEquals("com.example", dependency.getGroupId());
        assertEquals("com.example.library", dependency.getArtifactId());
        assertEquals("2.3", dependency.getVersion());
        var optionalDependency = model.getDependencies().stream()
                .filter(value -> value.getArtifactId().equals("com.example.annotations"))
                .findFirst()
                .orElseThrow();
        assertEquals("4.0", optionalDependency.getVersion());
        assertEquals("true", optionalDependency.getOptional());
        var versionedSystemDependency = model.getDependencies().stream()
                .filter(value -> value.getArtifactId().equals("java.sql"))
                .findFirst()
                .orElseThrow();
        assertEquals("java.sql", versionedSystemDependency.getGroupId());
        assertEquals("25", versionedSystemDependency.getVersion());
        assertEquals(Map.of(), model.getProperties());
        assertEquals("jar", model.getPackaging());

        assertThrows(FileAlreadyExistsException.class, () -> ConsumerPomGenerator.generate(resolution, "1.0", output));
    }
}
