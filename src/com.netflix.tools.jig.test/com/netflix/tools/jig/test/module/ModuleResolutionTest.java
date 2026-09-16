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
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes.Name;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import com.netflix.module.ModuleInfoHash;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.module.AetherModuleResolver.Result;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.ModuleResolution.IntegrityMode;
import com.netflix.tools.jig.module.SourceModuleFinder;
import com.netflix.tools.jig.module.SourceModuleReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModuleResolutionTest {

    @Test
    void resolvesSourceDirectoryWhenRequested(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        var sourceModules = SourceModuleFinder.of(directory.resolve("src"));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var resolution = ModuleResolution.resolve(repository, sourceModules, List.of("com.example.application"), false, true);

            var module = resolution.observableModules()
                                   .find("com.example.application")
                                   .orElseThrow();
            assertTrue(module instanceof SourceModuleReference);
            assertEquals(source, resolution.sources()
                    .get("com.example.application"));
            assertTrue(resolution.configuration()
                                 .findModule("com.example.application")
                                 .isPresent());
        }
    }

    @Test
    void sourceResolutionDoesNotOpenRepositoryModulesToHashThem(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.application {
                    requires com.example.dependency; // @1.0
                }
                """);
        ModuleReference delegate = ModuleFinder.of(automaticJar(directory, "com.example.dependency"))
                .find("com.example.dependency")
                .orElseThrow();
        var opens = new AtomicInteger();
        ModuleReference lazy = new ModuleReference(delegate.descriptor(), null) {
            @Override
            public ModuleReader open() throws IOException {
                opens.incrementAndGet();
                return delegate.open();
            }
        };
        ModuleFinder repositoryModules = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return name.equals("com.example.dependency") ? Optional.of(lazy) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.of(lazy);
            }
        };

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(repositoryModules, new LinkedHashSet<>(),
                                Map.of("com.example.dependency", "1.0"), Map.of()),
                SourceModuleFinder.of(directory.resolve("src")),
                List.of("com.example.application"),
                false,
                true);

        assertEquals(0, opens.get());
        assertTrue(resolution.hashes()
                             .isEmpty());
    }

    @Test
    void resolvesVersionedExternalRootsWithoutSourceModules(@TempDir Path directory) throws Exception {
        var declarations = new AtomicReference<Collection<ModuleDescriptor>>();
        ModuleFinder repositoryModules = ModuleFinder.of(automaticJar(directory, "com.example.external"));

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) -> {
                    declarations.set(List.copyOf(roots));
                    return new Result(repositoryModules, new LinkedHashSet<>(),
                            Map.of(), Map.of());
                },
                ModuleFinder.of(),
                List.of(),
                Map.of("com.example.external", "1.2.3"),
                false,
                false);

        assertEquals(Set.of("com.example.external"), Set.copyOf(resolution.configurationRoots()));
        var requirement = declarations.get().stream()
                .flatMap(declaration -> declaration.requires().stream())
                .filter(candidate -> candidate.name().equals("com.example.external"))
                .findFirst()
                .orElseThrow();
        assertEquals("1.2.3",
                requirement.compiledVersion()
                           .orElseThrow()
                           .toString());
    }

    @Test
    void resolvesUnsatisfiedRequirementsFromFixedBinaryClosure(@TempDir Path directory) throws Exception {
        Path application = explicitJar(directory, "com.example.application", Map.of("com.example.library", "1.0"));
        Path library = explicitJar(directory, "com.example.library", Map.of("com.example.dependency", "2.0"));
        ModuleFinder repositoryModules = ModuleFinder.of(automaticJar(directory, "com.example.dependency"));
        var declarations = new AtomicReference<Collection<ModuleDescriptor>>();

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) -> {
                    declarations.set(List.copyOf(roots));
                    return new Result(repositoryModules, new LinkedHashSet<>(),
                            Map.of(), Map.of());
                },
                ModuleFinder.of(application, library),
                List.of("com.example.application"),
                false,
                false);

        assertEquals(Set.of("com.example.library"),
                declarations.get().stream()
                        .map(ModuleDescriptor::name)
                        .collect(Collectors.toSet()));
        assertTrue(resolution.configuration()
                             .findModule("com.example.dependency")
                             .isPresent());
    }

    @Test
    void addRequiresVersionsAnUnsatisfiedBinaryRequirement(@TempDir Path directory) throws Exception {
        Path application = explicitJar(directory, "com.example.application", Collections.singletonMap("com.example.dependency", null));
        Path repository = directory.resolve("repository");
        installAutomaticModule(repository, "com.example.dependency", "2.0");
        var remote = new Builder("test", "default",
                repository.toUri().toString()).build();

        try (var session = ModuleRepositorySession.create(directory.resolve("cache"), List.of(remote))) {
            var resolution = ModuleResolution.resolve(session, ModuleFinder.of(application), List.of("com.example.application"),
                    Map.of("com.example.dependency", "2.0"), false, false);

            assertEquals("2.0", resolution.repositoryVersions()
                    .get("com.example.dependency"));
        }
    }

    @Test
    void ignoresUnversionedStaticBinaryRequirementAtRuntime(@TempDir Path directory) throws Exception {
        Path application = explicitJar(directory, "com.example.application",
                Collections.singletonMap("com.example.annotations", null),
                Set.of(AccessFlag.STATIC_PHASE, AccessFlag.TRANSITIVE));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var resolution = ModuleResolution.resolve(repository, ModuleFinder.of(application),
                    List.of("com.example.application"), false, false);

            assertTrue(resolution.configuration()
                    .findModule("com.example.application")
                    .isPresent());
            assertTrue(resolution.observableModules()
                    .find("com.example.annotations")
                    .isEmpty());
        }
    }

    @Test
    void ignoresStaticRequirementsWhenNoSourceModulesAreCompiled(@TempDir Path directory) throws Exception {
        Path application = explicitJar(directory, "com.example.application",
                Collections.singletonMap("com.example.annotations", null),
                Set.of(AccessFlag.STATIC_PHASE, AccessFlag.TRANSITIVE));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var resolution = ModuleResolution.resolve(repository, ModuleFinder.of(application),
                    List.of("com.example.application"), true, false);

            assertTrue(resolution.configuration()
                    .findModule("com.example.application")
                    .isPresent());
            assertTrue(resolution.observableModules()
                    .find("com.example.annotations")
                    .isEmpty());
        }
    }

    @Test
    void ignoresNonTransitiveStaticRequirementOfCompiledSourceDependency(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                "module com.example.application { requires com.example.library; }",
                "Application.java", "final class Application {}");
        Path library = explicitJar(directory, "com.example.library",
                Collections.singletonMap("com.example.annotations", null),
                Set.of(AccessFlag.STATIC_PHASE));
        ModuleFinder modules = ModuleFinder.compose(SourceModuleFinder.of(sources), ModuleFinder.of(library));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var resolution = ModuleResolution.resolve(repository, modules,
                    List.of("com.example.application"), true, false);

            assertTrue(resolution.configuration()
                    .findModule("com.example.library")
                    .isPresent());
            assertTrue(resolution.observableModules()
                    .find("com.example.annotations")
                    .isEmpty());
        }
    }

    @Test
    void requiresTransitiveStaticRequirementOfCompiledSourceDependency(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                "module com.example.application { requires com.example.library; }",
                "Application.java", "final class Application {}");
        Path library = explicitJar(directory, "com.example.library",
                Collections.singletonMap("com.example.annotations", null),
                Set.of(AccessFlag.STATIC_PHASE, AccessFlag.TRANSITIVE));
        ModuleFinder modules = ModuleFinder.compose(SourceModuleFinder.of(sources), ModuleFinder.of(library));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var failure = assertThrows(FindException.class,
                    () -> ModuleResolution.resolve(repository, modules,
                            List.of("com.example.application"), true, false));

            assertEquals("No version declared for module com.example.annotations", failure.getMessage());
        }
    }

    @Test
    void selectsTransitiveStaticRequirementOfCompiledSourceDependency(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                "module com.example.application { requires com.example.library; }",
                "Application.java", "final class Application {}");
        Path library = explicitJar(directory, "com.example.library",
                Collections.singletonMap("com.example.annotations", null),
                Set.of(AccessFlag.STATIC_PHASE, AccessFlag.TRANSITIVE));
        Path annotations = explicitJar(directory, "com.example.annotations");
        ModuleFinder modules = ModuleFinder.compose(SourceModuleFinder.of(sources), ModuleFinder.of(library, annotations));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var resolution = ModuleResolution.resolve(repository, modules,
                    List.of("com.example.application"), true, false);

            assertEquals(Set.of("com.example.annotations"), resolution.staticRoots());
            assertTrue(resolution.configuration()
                    .findModule("com.example.annotations")
                    .isPresent());
        }
    }

    @Test
    void ignoresTransitiveStaticRequirementBeyondNonTransitiveDependency(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                "module com.example.application { requires com.example.library; }",
                "Application.java", "final class Application {}");
        Path library = explicitJar(directory, "com.example.library", Map.of("com.example.internal", "1.0"));
        Path internal = explicitJar(directory, "com.example.internal",
                Collections.singletonMap("com.example.annotations", null),
                Set.of(AccessFlag.STATIC_PHASE, AccessFlag.TRANSITIVE));
        ModuleFinder modules = ModuleFinder.compose(SourceModuleFinder.of(sources), ModuleFinder.of(library, internal));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var resolution = ModuleResolution.resolve(repository, modules,
                    List.of("com.example.application"), true, false);

            assertTrue(resolution.configuration()
                    .findModule("com.example.internal")
                    .isPresent());
            assertTrue(resolution.observableModules()
                    .find("com.example.annotations")
                    .isEmpty());
        }
    }

    @Test
    void resolvesSystemModuleOutsideApplicationBootRoots(@TempDir Path directory) throws Exception {
        String systemModule = ModuleFinder.ofSystem().findAll().stream()
                .map(reference -> reference.descriptor().name())
                .filter(name -> ModuleLayer.boot()
                        .findModule(name)
                        .isEmpty())
                .sorted()
                .findFirst()
                .orElse(null);
        assumeTrue(systemModule != null, "Boot layer already contains every system module");
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.application {
                    requires %s;
                }
                """
                        .formatted(systemModule));

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(ModuleFinder.of(), new LinkedHashSet<>(),
                                Map.of(), Map.of()),
                SourceModuleFinder.of(directory.resolve("src")),
                List.of("com.example.application"),
                false,
                false);

        assertTrue(resolution.configuration()
                             .findModule(systemModule)
                             .isPresent());
        assertTrue(resolution.observableModules()
                             .find(systemModule)
                             .isPresent());
        assertTrue(!resolution.hashes().containsKey(systemModule));
    }

    @Test
    void fixedVersionedSystemRequirementsDoNotReachTheRepository(@TempDir Path directory) throws Exception {
        String systemVersion = ModuleFinder.ofSystem()
                .find("java.logging")
                .orElseThrow()
                .descriptor()
                .rawVersion()
                .orElseThrow();
        Path application = explicitJar(directory, "com.example.application", Map.of("java.logging", systemVersion));

        ModuleResolution.resolve(
                (roots, includeStatics, includeSources) -> {
                    assertFalse(requiredModules(roots).contains("java.logging"));
                    return new Result(ModuleFinder.of(), new LinkedHashSet<>(),
                            Map.of(), Map.of());
                },
                ModuleFinder.of(application),
                List.of("com.example.application"),
                false,
                false);
    }

    @Test
    void versionedSystemRequirementSelectsExplicitRepositoryReplacementForCompilation(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.application {
                    requires java.sql; // @1.0
                }
                """);
        Path replacement = explicitSystemModuleReplacement(directory, "java.sql");
        ModuleFinder repositoryModules = ModuleFinder.of(replacement);

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) -> {
                    assertTrue(requiredModules(roots).contains("java.sql"));
                    return new Result(repositoryModules, new LinkedHashSet<>(),
                            Map.of("java.sql", "1.0"), Map.of());
                },
                SourceModuleFinder.of(directory.resolve("src")),
                List.of("com.example.application"),
                false,
                false);

        assertEquals(Set.of("java.sql"), resolution.systemOverrides());
        var selected = resolution.observableModules()
                .find("java.sql")
                .orElseThrow();
        assertEquals(replacement.toUri(),
                selected.location().orElseThrow());
        assertSame(selected,
                resolution.configuration()
                          .findModule("java.sql")
                          .orElseThrow()
                          .reference());
    }

    @Test
    void addedSystemRequirementSelectsExplicitRepositoryReplacement(@TempDir Path directory) throws Exception {
        Path replacement = explicitSystemModuleReplacement(directory, "java.sql");
        ModuleFinder repositoryModules = ModuleFinder.of(replacement);

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) -> {
                    assertTrue(requiredModules(roots).contains("java.sql"));
                    return new Result(repositoryModules, new LinkedHashSet<>(),
                            Map.of("java.sql", "1.0"), Map.of());
                },
                ModuleFinder.of(),
                List.of(),
                Map.of("java.sql", "1.0"),
                false,
                false);

        assertEquals(Set.of("java.sql"), resolution.systemOverrides());
        var selected = resolution.observableModules()
                .find("java.sql")
                .orElseThrow();
        assertEquals(replacement.toUri(),
                selected.location().orElseThrow());
        assertSame(selected,
                resolution.configuration()
                          .findModule("java.sql")
                          .orElseThrow()
                          .reference());
    }

    @Test
    void addedRequirementsMustMatchFixedModuleVersions(@TempDir Path directory) throws Exception {
        ModuleFinder fixedModules = ModuleFinder.of(automaticJar(directory, "com.example.fixed"));

        var failure = assertThrows(
                FindException.class,
                () -> ModuleResolution.resolve(
                        (roots, includeStatics, includeSources) ->
                                new Result(ModuleFinder.of(), new LinkedHashSet<>(),
                                        Map.of(), Map.of()),
                        fixedModules,
                        List.of(),
                        Map.of("com.example.fixed", "2.0"),
                        false,
                        false));

        assertEquals(
                "Module com.example.fixed requires version 2.0 " + "but the fixed module has version 1.0",
                failure.getMessage());
    }

    @Test
    void identifiesSelectedSourceModulesThatShadowSystemModules(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/java.logging");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module java.logging {}");

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(ModuleFinder.of(), new LinkedHashSet<>(),
                                Map.of(), Map.of()),
                SourceModuleFinder.of(directory.resolve("src")),
                List.of("java.logging"),
                false,
                false);

        assertEquals(Set.of("java.logging"), resolution.systemOverrides());
        assertTrue(resolution.observableModules()
                             .find("java.logging")
                             .orElseThrow()
                instanceof SourceModuleReference);
    }

    @Test
    void resolvesObservableAutomaticModulesWithoutSupplementalRoots(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.application {
                    requires auto.parent; // @1.0
                }
                """);
        var sourceModules = SourceModuleFinder.of(directory.resolve("src"));

        var repositoryModules = ModuleFinder.of(automaticJar(directory, "auto.parent"), automaticJar(directory, "auto.dependency"));

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(repositoryModules, new LinkedHashSet<>(),
                                Map.of(), Map.of()),
                sourceModules,
                List.of("com.example.application"),
                false,
                false);

        assertEquals(List.of("com.example.application"), List.copyOf(resolution.configurationRoots()));
        assertTrue(resolution.configuration()
                             .findModule("auto.parent")
                             .isPresent());
        assertTrue(resolution.configuration()
                             .findModule("auto.dependency")
                             .isPresent());
    }

    @Test
    void resolvesAutomaticModulesAlongsideSourceOverridesOfBootModules(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                """
                module com.example.application {
                    requires auto.parent; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        source(sources, "com.netflix.tools.jig", "module com.netflix.tools.jig {}", "Jig.java", "final class Jig {}");
        ModuleFinder repositoryModules = ModuleFinder.of(automaticJar(directory, "auto.parent"), automaticJar(directory, "auto.dependency"));
        ModuleHash parentHash = ModuleHash.moduleSha256(repositoryModules.find("auto.parent")
                .orElseThrow());
        ModuleHash dependencyHash = ModuleHash.moduleSha256(repositoryModules.find("auto.dependency")
                .orElseThrow());

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(repositoryModules, new LinkedHashSet<>(),
                                Map.of("auto.parent", "1.0", "auto.dependency", "1.0"), Map.of()),
                SourceModuleFinder.of(sources),
                List.of("com.example.application", "com.netflix.tools.jig"),
                Map.of(),
                false,
                false,
                IntegrityMode.UPDATE);

        assertTrue(resolution.configuration()
                             .findModule("auto.parent")
                             .isPresent());
        assertTrue(resolution.configuration()
                             .findModule("com.netflix.tools.jig")
                             .orElseThrow()
                             .reference()
                instanceof SourceModuleReference);
        assertEquals(ModuleInfoHash.newBuilder()
                .put("auto.dependency", "1.0", dependencyHash)
                .put("auto.parent", "1.0", parentHash)
                .build(),
                ModuleInfoHash.read(sources.resolve("com.example.application/module-info.hash")));
    }

    @Test
    void selectsOnlyRequestedSourceModules(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application", "module com.example.application {}", "Application.java", "final class Application {}");
        source(sources, "com.example.library", "module com.example.library {}", "Library.java", "final class Library {}");
        source(sources, "com.example.incoming",
                """
                module com.example.incoming {
                    requires com.example.application; // @1.0
                }
                """,
                "Incoming.java", "final class Incoming {}");

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            var resolution = ModuleResolution.resolve(repository, SourceModuleFinder.of(sources), List.of("com.example.application", "com.example.library"), false,
                    true);

            assertEquals(Set.of("com.example.application", "com.example.library"),
                    resolution.moduleSources().keySet());
            assertEquals(resolution.moduleSources(), resolution.sources());
        }
    }

    @Test
    void resolvesUnselectedSourceModulesFromTheRepository(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                """
                module com.example.application {
                    requires com.example.library; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        source(sources, "com.example.library", "module com.example.library {}", "Library.java", "final class Library {}");
        ModuleFinder repositoryModules = ModuleFinder.of(automaticJar(directory, "com.example.library"));

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) -> {
                    return new Result(repositoryModules, new LinkedHashSet<>(),
                            Map.of(), Map.of());
                },
                SourceModuleFinder.of(sources),
                List.of("com.example.application"),
                false,
                false);

        assertEquals(Set.of("com.example.application"),
                resolution.moduleSources().keySet());
        assertTrue(!(resolution.observableModules()
                               .find("com.example.library")
                               .orElseThrow()
                instanceof SourceModuleReference));
    }

    @Test
    void selectsUnversionedStaticSourceRequirementsForCompilation(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                """
                module com.example.application {
                    requires static com.example.launcher;
                }
                """,
                "Application.java", "final class Application {}");
        source(sources, "com.example.launcher", "module com.example.launcher {}", "Launcher.java", "final class Launcher {}");

        var resolution = ModuleResolution.resolve(
                (roots, includeStatics, includeSources) -> {
                    assertTrue(includeStatics);
                    return new Result(ModuleFinder.of(), new LinkedHashSet<>(),
                            Map.of(), Map.of());
                },
                SourceModuleFinder.of(sources),
                List.of("com.example.application"),
                true,
                false);

        assertEquals(Set.of("com.example.application", "com.example.launcher"),
                resolution.moduleSources().keySet());
        assertEquals(Set.of("com.example.launcher"), resolution.staticRoots());
    }

    @Test
    void sourceOverrideOfBootModuleRemainsRuntimeReachable(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        source(sources, "com.example.application",
                """
                module com.example.application {
                    requires com.netflix.tools.jig;
                }
                """,
                "Application.java", "final class Application {}");
        source(sources, "com.netflix.tools.jig", "module com.netflix.tools.jig {}", "Jig.java", "final class Jig {}");
        ModuleFinder finder = SourceModuleFinder.of(sources);
        Configuration configuration = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(), Set.of("com.example.application"));

        assertTrue(configuration.findModule("com.netflix.tools.jig")
                                .orElseThrow()
                                .reference()
                instanceof SourceModuleReference);
        assertEquals(Set.of(), ModuleResolution.staticOnlyModules(configuration, Set.of("com.example.application")));
    }

    @Test
    void ignoresModuleInfoHashByDefault(@TempDir Path directory) throws Exception {
        Path module = source(directory.resolve("src"), "com.example.application", "module com.example.application {}",
                "Application.java", "final class Application {}");
        ModuleInfoHash.newBuilder()
                .put("com.example.unexpected", new ModuleHash(Type.MODULE, "sha256", "0000000000000000000000000000000000000000000000000000000000000000"))
                .build()
                .write(module.resolve("module-info.hash"));
        var sourceModules = SourceModuleFinder.of(directory.resolve("src"));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            ModuleResolution.resolve(repository, sourceModules, List.of("com.example.application"), false, false);
        }
    }

    @Test
    void verificationAllowsEntriesForModulesThatAreNoLongerResolved(@TempDir Path directory) throws Exception {
        Path module = source(directory.resolve("src"), "com.example.application",
                """
                module com.example.application {
                    requires com.example.library; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        ModuleFinder repositoryModules = ModuleFinder.of(explicitJar(directory, "com.example.library"));
        ModuleHash hash = ModuleHash.moduleSha256(repositoryModules.find("com.example.library")
                .orElseThrow());
        ModuleInfoHash.newBuilder()
                .put("com.example.library", hash)
                .put("com.example.unexpected", new ModuleHash(Type.MODULE, "sha256", "0000000000000000000000000000000000000000000000000000000000000000"))
                .build()
                .write(module.resolve("module-info.hash"));

        ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(repositoryModules, new LinkedHashSet<>(),
                                Map.of("com.example.library", "1.0"), Map.of()),
                SourceModuleFinder.of(directory.resolve("src")),
                List.of("com.example.application"),
                Map.of(),
                false,
                false,
                IntegrityMode.VERIFY);
    }

    @Test
    void verifiesRepositoryModuleResolvedFromParentConfiguration(@TempDir Path directory) throws Exception {
        ModuleFinder repositoryModules = ModuleFinder.of(explicitJar(directory, "com.netflix.tools.jig"));
        var dependency = repositoryModules.find("com.netflix.tools.jig").orElseThrow();
        Path module = source(directory.resolve("src"), "com.example.application",
                """
                module com.example.application {
                    requires com.netflix.tools.jig; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        ModuleInfoHash.newBuilder()
                .put("com.netflix.tools.jig", ModuleHash.moduleSha256(dependency))
                .build()
                .write(module.resolve("module-info.hash"));

        ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(repositoryModules, new LinkedHashSet<>(),
                                Map.of("com.netflix.tools.jig", "1.0"), Map.of()),
                SourceModuleFinder.of(directory.resolve("src")),
                List.of("com.example.application"),
                Map.of(),
                false,
                false,
                IntegrityMode.VERIFY);
    }

    @Test
    void verificationDoesNotRequireHashFileWithoutHashedModules(@TempDir Path directory) throws Exception {
        var module = source(directory.resolve("src"), "com.example.application", "module com.example.application {}",
                "Application.java", "final class Application {}");
        var sourceModules = SourceModuleFinder.of(directory.resolve("src"));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            ModuleResolution.resolve(repository, sourceModules, List.of("com.example.application"), false, false,
                    IntegrityMode.VERIFY);
        }

        assertFalse(Files.exists(module.resolve("module-info.hash")));
    }

    @Test
    void verificationRequiresHashFileForHashedModules(@TempDir Path directory) throws Exception {
        source(directory.resolve("src"), "com.example.application",
                """
                module com.example.application {
                    requires com.example.library; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        var repositoryModules = ModuleFinder.of(explicitJar(directory, "com.example.library"));
        var hash = ModuleHash.moduleSha256(repositoryModules.find("com.example.library")
                .orElseThrow());

        var failure = assertThrows(
                FindException.class,
                () -> ModuleResolution.resolve(
                        (roots, includeStatics, includeSources) ->
                                new Result(repositoryModules, new LinkedHashSet<>(),
                                        Map.of("com.example.library", "1.0"), Map.of()),
                        SourceModuleFinder.of(directory.resolve("src")),
                        List.of("com.example.application"),
                        Map.of(),
                        true,
                        false,
                        IntegrityMode.VERIFY));
        assertTrue(failure.getMessage()
                          .startsWith("Module hash file does not exist: "));
    }

    @Test
    void verificationRejectsAModulePathModuleWithoutAHash(@TempDir Path directory) throws Exception {
        Path module = source(directory.resolve("src"), "com.example.application",
                """
                module com.example.application {
                    requires com.example.library; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        ModuleInfoHash.newBuilder()
                .build()
                .write(module.resolve("module-info.hash"));
        ModuleFinder repositoryModules = ModuleFinder.of(explicitJar(directory, "com.example.library"));
        ModuleHash hash = ModuleHash.moduleSha256(repositoryModules.find("com.example.library")
                .orElseThrow());

        assertThrows(
                FindException.class,
                () -> ModuleResolution.resolve(
                        (roots, includeStatics, includeSources) ->
                                new Result(repositoryModules, new LinkedHashSet<>(),
                                        Map.of("com.example.library", "1.0"), Map.of()),
                        SourceModuleFinder.of(directory.resolve("src")),
                        List.of("com.example.application"),
                        Map.of(),
                        true,
                        false,
                        IntegrityMode.VERIFY));
    }

    @Test
    void updateRemovesModuleInfoHashWithoutHashedModules(@TempDir Path directory) throws Exception {
        Path module = source(directory.resolve("src"), "com.example.application", "module com.example.application {}",
                "Application.java", "final class Application {}");
        Path hashFile = module.resolve("module-info.hash");
        ModuleInfoHash.newBuilder()
                .put("com.example.stale", new ModuleHash(Type.MODULE, "sha256", "0000000000000000000000000000000000000000000000000000000000000000"))
                .build()
                .write(hashFile);
        var sourceModules = SourceModuleFinder.of(directory.resolve("src"));

        try (var repository = ModuleRepositorySession.create(directory.resolve("repository"), List.of())) {
            ModuleResolution.resolve(repository, sourceModules, List.of("com.example.application"), false, false,
                    IntegrityMode.UPDATE);
        }

        assertFalse(Files.exists(hashFile));
    }

    @Test
    void updateRejectsChangedContentForAnUnversionedModule(@TempDir Path directory) throws Exception {
        Path module = source(directory.resolve("src"), "com.example.application",
                """
                module com.example.application {
                    requires static com.example.library; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        ModuleFinder repositoryModules = ModuleFinder.of(explicitJar(directory, "com.example.library"));
        ModuleHash observed = ModuleHash.moduleSha256(repositoryModules.find("com.example.library")
                .orElseThrow());
        ModuleHash expected = new ModuleHash(Type.MODULE, "sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        Path hashFile = module.resolve("module-info.hash");
        ModuleInfoHash.newBuilder()
                .put("com.example.library", expected)
                .build()
                .write(hashFile);

        assertThrows(
                FindException.class,
                () -> ModuleResolution.resolve(
                        (roots, includeStatics, includeSources) ->
                                new Result(repositoryModules, new LinkedHashSet<>(),
                                        Map.of("com.example.library", "1.0"), Map.of()),
                        SourceModuleFinder.of(directory.resolve("src")),
                        List.of("com.example.application"),
                        Map.of(),
                        true,
                        false,
                        IntegrityMode.UPDATE));

        assertEquals(expected,
                ModuleInfoHash.read(hashFile)
                        .get("com.example.library")
                        .orElseThrow());
    }

    @Test
    void updateIncludesUnversionedStaticModulesWithoutUsingRepositoryVersions(@TempDir Path directory) throws Exception {
        Path module = source(directory.resolve("src"), "com.example.application",
                """
                module com.example.application {
                    requires static com.example.library; // @1.0
                }
                """,
                "Application.java", "final class Application {}");
        ModuleFinder repositoryModules = ModuleFinder.of(explicitJar(directory, "com.example.library"));
        ModuleHash hash = ModuleHash.moduleSha256(repositoryModules.find("com.example.library")
                .orElseThrow());

        ModuleResolution.resolve(
                (roots, includeStatics, includeSources) ->
                        new Result(repositoryModules, new LinkedHashSet<>(),
                                Map.of("com.example.library", "1.0"), Map.of()),
                SourceModuleFinder.of(directory.resolve("src")),
                List.of("com.example.application"),
                Map.of(),
                true,
                false,
                IntegrityMode.UPDATE);

        assertEquals(hash,
                ModuleInfoHash.read(module.resolve("module-info.hash"))
                        .get("com.example.library")
                        .orElseThrow());
        assertEquals("com.example.library=" + hash + System.lineSeparator(),
                Files.readString(module.resolve("module-info.hash")));
    }

    private static Set<String> requiredModules(Collection<ModuleDescriptor> descriptors) {
        return descriptors.stream()
                .flatMap(descriptor -> descriptor.requires().stream())
                .map(Requires::name)
                .collect(Collectors.toSet());
    }

    private static Path source(Path root, String moduleName, String moduleInfo,
            String relativeSource, String content)
            throws Exception {
        Path module = root.resolve(moduleName);
        Files.createDirectories(module);
        Files.writeString(module.resolve("module-info.java"), moduleInfo);
        Path source = module.resolve(relativeSource);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return module;
    }

    private static Path automaticJar(Path directory, String moduleName) throws Exception {
        Path jar = directory.resolve(moduleName + "-1.0.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(jar), manifest)) {}
        return jar;
    }

    private static void installAutomaticModule(Path repository, String moduleName, String version) throws Exception {
        String group = moduleName.substring(0, moduleName.lastIndexOf('.'));
        Path versionDirectory = repository.resolve(group.replace('.', '/') + "/" + moduleName + "/" + version);
        Files.createDirectories(versionDirectory);
        Path jar = versionDirectory.resolve(moduleName + "-" + version + ".jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(jar), manifest)) {}
        Files.writeString(
                versionDirectory.resolve(moduleName + "-" + version + ".pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """
                        .formatted(moduleName.substring(0, moduleName.lastIndexOf('.')), moduleName, version));
    }

    private static Path explicitSystemModuleReplacement(Path directory, String moduleName) throws Exception {
        // An observable JDK module stands in for an explicit custom tool module linked into the system image.
        return explicitJar(directory, moduleName);
    }

    private static Path explicitJar(Path directory, String moduleName) throws Exception {
        return explicitJar(directory, moduleName, Map.of());
    }

    private static Path explicitJar(Path directory, String moduleName, Map<String, String> requirements) throws Exception {
        return explicitJar(directory, moduleName, requirements, Set.of());
    }

    private static Path explicitJar(Path directory, String moduleName, Map<String, String> requirements,
            Set<AccessFlag> requirementFlags) throws Exception {
        Path jar = directory.resolve(moduleName + "-explicit-1.0.jar");
        byte[] descriptor = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(moduleName),
                builder -> {
                    builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
                    requirements.forEach((name, version) -> builder.requires(ModuleDesc.of(name), requirementFlags, version));
                }));
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("module-info.class"));
            output.write(descriptor);
            output.closeEntry();
        }
        return jar;
    }
}
