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

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.ModuleRuntimeAccessOptions.PackageAccess;
import com.netflix.tools.jig.module.SourceModuleFinder;
import com.netflix.tools.jig.module.SourceModuleReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SourceModuleFinderTest {

    private static SourceModuleFinder finder;

    @BeforeAll
    static void setUp() {
        Path sourceModulePath = testResourcePath("source-modules");
        // Stub versioned finder — not needed for parsing, only for open()
        finder = SourceModuleFinder.of(sourceModulePath);
    }

    @Test
    @DisplayName("finds a simple module")
    void findsSimpleModule() {
        Optional<ModuleReference> ref = finder.find("simple.mod");
        assertTrue(ref.isPresent());
        assertEquals("simple.mod",
                ref.get()
                   .descriptor()
                   .name());
    }

    @Test
    @DisplayName("returns empty for unknown module")
    void unknownModule() {
        assertTrue(finder.find("nonexistent")
                         .isEmpty());
    }

    @Test
    @DisplayName("findAll returns all scanned modules")
    void findAll() {
        Set<String> names = finder.findAll().stream()
                .map(ref -> ref.descriptor().name())
                .collect(Collectors.toSet());
        assertTrue(names.contains("simple.mod"));
        assertTrue(names.contains("with.requires"));
        assertTrue(names.contains("with.constraints"));
        assertTrue(names.contains("with.exports"));
    }

    @Test
    @DisplayName("parses requires directives")
    void parsesRequires() {
        ModuleDescriptor d = finder.find("with.requires")
                .orElseThrow()
                .descriptor();

        assertRequires(d, "java.logging", Set.of());
        assertRequires(d, "java.sql", Set.of(Modifier.TRANSITIVE));
        assertRequires(d, "java.compiler", Set.of(Modifier.STATIC));
    }

    @Test
    @DisplayName("parses exports directives")
    void parsesExports() {
        ModuleDescriptor d = finder.find("with.exports")
                .orElseThrow()
                .descriptor();

        Set<String> unqualified = d.exports().stream()
                .filter(e -> e.targets().isEmpty())
                .map(Exports::source)
                .collect(Collectors.toSet());
        assertTrue(unqualified.contains("com.example.api"));

        Optional<Exports> qualified = d.exports().stream()
                .filter(e -> e.source().equals("com.example.internal"))
                .findFirst();
        assertTrue(qualified.isPresent());
        assertTrue(qualified.get()
                            .targets()
                            .contains("trusted.module"));
    }

    @Test
    @DisplayName("returns SourceModuleReference")
    void returnsMetadataReference() {
        ModuleReference ref = finder.find("simple.mod").orElseThrow();
        assertInstanceOf(SourceModuleReference.class, ref);
    }

    @Test
    @DisplayName("parses release from doclet tag")
    void parsesRelease() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("with.constraints").orElseThrow();
        assertEquals(17, ref.sourceModule()
                            .release());
    }

    @Test
    @DisplayName("leaves an unspecified release unset")
    void leavesReleaseUnspecified() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("simple.mod").orElseThrow();
        assertNull(ref.sourceModule()
                      .release());
    }

    @Test
    @DisplayName("parses enableNativeAccess from doclet tag")
    void parsesNativeAccess() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("with.constraints").orElseThrow();
        assertEquals(List.of("with.constraints"),
                ref.sourceModule()
                   .runtimeAccessOptions()
                   .enableNativeAccess());
    }

    @Test
    @DisplayName("parses processWith from doclet tags")
    void parsesProcessors() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("with.constraints").orElseThrow();
        assertEquals(List.of("com.example.processor"),
                ref.sourceModule().processors());
    }

    @Test
    @DisplayName("parses addOpens from doclet tag")
    void parsesAddOpens() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("with.constraints").orElseThrow();
        assertTrue(ref.sourceModule()
                      .runtimeAccessOptions()
                      .addOpens()
                      .contains(new PackageAccess("java.base", "java.lang", "with.constraints")));
    }

    @Test
    @DisplayName("parses addExports from doclet tag")
    void parsesAddExports() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("with.constraints").orElseThrow();
        assertTrue(ref.sourceModule()
                      .runtimeAccessOptions()
                      .addExports()
                      .contains(new PackageAccess("java.base", "jdk.internal.misc", "with.constraints")));
    }

    // -- version opinions from // @ comments --

    @Test
    @DisplayName("parses version opinions from // @ comments on requires")
    void parsesVersionOpinions() {
        ModuleDescriptor d = finder.find("with.versions")
                .orElseThrow()
                .descriptor();

        Requires lib = d.requires().stream()
                .filter(r -> r.name().equals("com.example.lib"))
                .findFirst()
                .orElseThrow();
        assertTrue(lib.compiledVersion()
                      .isPresent());
        assertEquals("1.2.3",
                lib.compiledVersion()
                   .get()
                   .toString());
    }

    @Test
    @DisplayName("version opinions work with transitive modifier")
    void versionWithTransitive() {
        ModuleDescriptor d = finder.find("with.versions")
                .orElseThrow()
                .descriptor();

        Requires api = d.requires().stream()
                .filter(r -> r.name().equals("com.example.api"))
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of(Modifier.TRANSITIVE), api.modifiers());
        assertTrue(api.compiledVersion()
                      .isPresent());
        assertEquals("2.0.0",
                api.compiledVersion()
                   .get()
                   .toString());
    }

    @Test
    @DisplayName("version opinions work with static modifier")
    void versionWithStatic() {
        ModuleDescriptor d = finder.find("with.versions")
                .orElseThrow()
                .descriptor();

        Requires opt = d.requires().stream()
                .filter(r -> r.name().equals("com.example.optional"))
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of(Modifier.STATIC), opt.modifiers());
        assertTrue(opt.compiledVersion()
                      .isPresent());
        assertEquals("0.9.1",
                opt.compiledVersion()
                   .get()
                   .toString());
    }

    @Test
    @DisplayName("requires without // @ comment has no compiled version")
    void noVersionWithoutComment() {
        ModuleDescriptor d = finder.find("with.versions")
                .orElseThrow()
                .descriptor();

        Requires noVersion = d.requires().stream()
                .filter(r -> r.name().equals("com.example.noversion"))
                .findFirst()
                .orElseThrow();
        assertTrue(noVersion.compiledVersion()
                            .isEmpty());
    }

    // -- main class from @mainClass tag --

    @Test
    @DisplayName("parses mainClass from doclet tag")
    void parsesMainClass() {
        ModuleDescriptor d = finder.find("with.main")
                .orElseThrow()
                .descriptor();
        assertTrue(d.mainClass()
                    .isPresent());
        assertEquals("com.example.main.App", d.mainClass()
                .get());
        assertTrue(d.packages()
                    .containsAll(Set.of("com.example.api", "com.example.main")));
    }

    @Test
    void includesConcealedSourcePackages(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module com.example.application {}\n");
        var internal = Files.createDirectories(directory.resolve("com/example/internal"));
        Files.writeString(internal.resolve("Implementation.java"),
                """
                package com.example.internal;
                final class Implementation {}
                """);

        var descriptor = SourceModuleFinder.of(directory)
                .find("com.example.application")
                .orElseThrow()
                .descriptor();

        assertTrue(descriptor.packages()
                             .contains("com.example.internal"));
    }

    @Test
    @DisplayName("module without @mainClass has no main class")
    void noMainClassWithoutTag() {
        ModuleDescriptor d = finder.find("simple.mod")
                .orElseThrow()
                .descriptor();
        assertTrue(d.mainClass()
                    .isEmpty());
    }

    // -- provides --

    @Test
    @DisplayName("parses provides directive")
    void parsesProvides() {
        ModuleDescriptor d = finder.find("with.provides")
                .orElseThrow()
                .descriptor();
        var provides = d.provides().stream()
                .filter(p -> p.service().equals("java.lang.System.LoggerFinder"))
                .findFirst()
                .orElseThrow();
        assertTrue(provides.providers()
                           .contains("com.example.logging.CustomLoggerFinder"));
    }

    // -- open modules, opens, uses --

    @Test
    @DisplayName("parses open module")
    void parsesOpenModule() {
        ModuleDescriptor d = finder.find("open.mod")
                .orElseThrow()
                .descriptor();
        assertTrue(d.isOpen());
    }

    @Test
    @DisplayName("non-open module is not open")
    void nonOpenModule() {
        ModuleDescriptor d = finder.find("simple.mod")
                .orElseThrow()
                .descriptor();
        assertFalse(d.isOpen());
    }

    @Test
    @DisplayName("parses uses directive")
    void parsesUses() {
        ModuleDescriptor d = finder.find("with.services")
                .orElseThrow()
                .descriptor();
        assertTrue(d.uses()
                    .contains("java.nio.file.spi.FileSystemProvider"));
    }

    @Test
    @DisplayName("parses unqualified opens")
    void parsesOpens() {
        ModuleDescriptor d = finder.find("with.services")
                .orElseThrow()
                .descriptor();
        assertTrue(d.opens().stream()
                .anyMatch(o -> o.source().equals("com.example.internal") && o.targets().isEmpty()));
    }

    @Test
    @DisplayName("parses qualified opens")
    void parsesQualifiedOpens() {
        ModuleDescriptor d = finder.find("with.services")
                .orElseThrow()
                .descriptor();
        var qualified = d.opens().stream()
                .filter(o -> o.source().equals("com.example.reflect"))
                .findFirst()
                .orElseThrow();
        assertTrue(qualified.targets()
                            .contains("java.base"));
    }

    @Test
    @DisplayName("module without access requirements has EMPTY access")
    void emptyConstraints() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("simple.mod").orElseThrow();
        assertEquals(ModuleRuntimeAccessOptions.EMPTY, ref.sourceModule()
                .runtimeAccessOptions());
    }

    @Test
    @DisplayName("binary location is absent until source content is projected")
    void binaryLocationIsAbsent() {
        ModuleReference ref = finder.find("simple.mod").orElseThrow();
        assertTrue(ref.location()
                      .isEmpty());
    }

    @Test
    @DisplayName("source module retains source directory")
    void sourceDirectory() {
        SourceModuleReference ref = (SourceModuleReference) finder.find("simple.mod").orElseThrow();
        assertTrue(ref.sourceModule()
                      .sourceDirectory()
                      .toString()
                      .endsWith("simple.mod"));
    }

    // -- helpers --

    private static void assertRequires(ModuleDescriptor d, String name, Set<Modifier> modifiers) {
        Requires req = d.requires().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing requires " + name));
        assertEquals(modifiers, req.modifiers(), "wrong modifiers for requires " + name);
    }

    private static Path testResourcePath(String name) {
        return Path.of("src", "com.netflix.tools.jig.test", name)
                .toAbsolutePath()
                .normalize();
    }
}
