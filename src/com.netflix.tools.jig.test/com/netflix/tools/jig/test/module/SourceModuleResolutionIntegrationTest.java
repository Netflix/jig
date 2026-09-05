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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.Diagnostic.Kind;

import com.netflix.module.ModuleRuntimeAccessAttribute;
import com.netflix.module.compile.ModuleCompiler;
import com.netflix.tools.jig.module.ModulePathReference;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.SourceModuleFinder;
import com.netflix.tools.jig.module.SourceModuleReference;
import com.netflix.tools.jig.module.SourceModuleReference.CompilationEnvironment;
import com.netflix.tools.jig.module.SourceModuleReference.Dependency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.netflix.module.test.ModuleCompilerMeasurements.compilations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SourceModuleResolutionIntegrationTest {

    private static ModuleRepositorySession session;

    @TempDir
    private static Path localRepo;

    @BeforeAll
    static void setUp() {
        session = ModuleRepositorySession.create(localRepo, List.of(ModuleRepositorySession.centralRepository()));
    }

    @AfterAll
    static void tearDown() {
        session.close();
    }

    @Test
    @DisplayName("source module resolves through canonical Aether module repository")
    void sourceModuleResolvesThroughCanonicalRepository(@TempDir Path tempDir) throws IOException {
        var sourceRoot = tempDir.resolve("src");
        var moduleDir = sourceRoot.resolve("com.example.canonical.app");
        Files.createDirectories(moduleDir);
        Files.writeString(moduleDir.resolve("module-info.java"),
                """
                module com.example.canonical.app {
                    requires org.slf4j; // @2.0.17
                }
                """);

        var sourceFinder = SourceModuleFinder.of(sourceRoot);
        var resolution = ModuleResolution.resolve(session, sourceFinder, List.of("com.example.canonical.app"), true, true);
        var config = resolution.configuration();
        assertTrue(config.findModule("com.example.canonical.app")
                         .isPresent());
        var application = (SourceModuleReference) resolution.finder()
                .find("com.example.canonical.app")
                .orElseThrow();
        assertThrows(IllegalStateException.class, application::open);
        assertEquals(moduleDir, resolution.sources()
                .get("com.example.canonical.app"));
        var slf4j = config.findModule("org.slf4j").orElseThrow();
        assertTrue(slf4j.reference()
                        .location()
                        .isEmpty());
        assertEquals("org.slf4j-2.0.17.jar",
                assertInstanceOf(ModulePathReference.class, slf4j.reference()).modulePath()
                        .getFileName()
                        .toString());
        assertFalse(slf4j.reference()
                         .descriptor()
                         .isAutomatic());
        assertEquals("org.slf4j-2.0.17-sources.jar",
                resolution.sources()
                          .get("org.slf4j")
                          .getFileName()
                          .toString());
    }

    @Test
    void staticResolutionIncludesCanonicalOptionalDependencies(@TempDir Path tempDir) throws IOException {
        var sourceRoot = tempDir.resolve("src");
        var moduleDir = sourceRoot.resolve("com.example.junit.app");
        Files.createDirectories(moduleDir);
        Files.writeString(moduleDir.resolve("module-info.java"),
                """
                module com.example.junit.app {
                    requires static org.junit.jupiter; // @6.1.2
                }
                """);
        var sourceFinder = SourceModuleFinder.of(sourceRoot);

        var compileResolution = ModuleResolution.resolve(session, sourceFinder, List.of("com.example.junit.app"), true, false);
        assertTrue(compileResolution.configuration()
                .findModule("org.junit.jupiter")
                .isPresent());
        assertTrue(compileResolution.configuration()
                .findModule("org.apiguardian.api")
                .isPresent());
        assertTrue(compileResolution.configuration()
                .findModule("org.jspecify")
                .isPresent());

        var runtimeResolution = ModuleResolution.resolve(session, sourceFinder, List.of("com.example.junit.app"), false, false);
        assertTrue(runtimeResolution.finder()
                .find("org.junit.jupiter")
                .isEmpty());
        assertTrue(runtimeResolution.finder()
                .find("org.apiguardian.api")
                .isEmpty());
        assertTrue(runtimeResolution.finder()
                .find("org.jspecify")
                .isEmpty());
    }

    @Test
    void automaticModuleFoldsDependenciesWhosePackagesItContains(@TempDir Path tempDir) throws IOException {
        var sourceRoot = tempDir.resolve("src");
        var moduleDir = sourceRoot.resolve("com.example.cowsay.app");
        Files.createDirectories(moduleDir);
        Files.writeString(moduleDir.resolve("module-info.java"),
                """
                module com.example.cowsay.app {
                    requires com.github.ricksbrown.cowsay; // @1.1.0
                }
                """);

        var resolution = ModuleResolution.resolve(session, SourceModuleFinder.of(sourceRoot), List.of("com.example.cowsay.app"), false,
                false);

        assertTrue(resolution.configuration()
                             .findModule("com.github.ricksbrown.cowsay")
                             .isPresent());
        assertTrue(resolution.finder()
                             .find("org.apache.commons.lang3")
                             .isEmpty());
        assertTrue(resolution.finder()
                             .find("org.apache.maven.plugin.api")
                             .isEmpty());
        assertTrue(resolution.finder()
                             .find("com.github.ricksbrown.cowjar")
                             .isPresent());
    }

    @Test
    void implementationChangeDoesNotRecompileDependentSourceModule(@TempDir Path tempDir) throws Exception {
        var sourceRoot = tempDir.resolve("src");
        var dependencySource = Files.createDirectories(sourceRoot.resolve("dependency.mod/dependency"));
        Files.writeString(dependencySource.getParent()
                .resolve("module-info.java"),
                "module dependency.mod { exports dependency; }");
        var apiSource = dependencySource.resolve("Api.java");
        Files.writeString(apiSource, "package dependency; public class Api { private static final int INTERNAL = 1; public static final int VALUE = INTERNAL; public static int value() { return 1; } }");
        var applicationSource = Files.createDirectories(sourceRoot.resolve("application.mod/application"));
        Files.writeString(applicationSource.getParent()
                .resolve("module-info.java"),
                "module application.mod { requires dependency.mod; }");
        Files.writeString(applicationSource.resolve("Application.java"), "package application; public class Application { public int value() { return dependency.Api.VALUE; } }");
        var outputs = tempDir.resolve("outputs");

        var firstCompiler = new ModuleCompiler(outputs);
        var firstFinder = SourceModuleFinder.of(sourceRoot);
        var firstDependency = (SourceModuleReference) firstFinder.find("dependency.mod").orElseThrow();
        var firstApplication = (SourceModuleReference) firstFinder.find("application.mod").orElseThrow();
        firstDependency.bind(environment(firstCompiler, List.of()));
        firstApplication.bind(environment(firstCompiler, List.of(new Dependency("dependency.mod", firstDependency, null, false, false))));
        var firstCompiledDependency = firstDependency.compiledModule();
        try (var ignored = firstApplication.open()) {
            assertTrue(ignored.find("application/Application.class")
                              .isPresent());
        }
        assertEquals(2, compilations(firstCompiler));

        Files.writeString(apiSource, "package dependency; public class Api { private static final int INTERNAL = 1; public static final int VALUE = INTERNAL; public static int value() { return 2; } }");
        var nextCompiler = new ModuleCompiler(outputs);
        var nextFinder = SourceModuleFinder.of(sourceRoot);
        var nextDependency = (SourceModuleReference) nextFinder.find("dependency.mod").orElseThrow();
        var nextApplication = (SourceModuleReference) nextFinder.find("application.mod").orElseThrow();
        nextDependency.bind(environment(nextCompiler, List.of()));
        nextApplication.bind(environment(nextCompiler, List.of(new Dependency("dependency.mod", nextDependency, null, false, false))));
        var nextCompiledDependency = nextDependency.compiledModule();
        try (var ignored = nextApplication.open()) {
            assertTrue(ignored.find("application/Application.class")
                              .isPresent());
        }

        assertNotEquals(firstCompiledDependency.hash(), nextCompiledDependency.hash());
        assertEquals(firstCompiledDependency.compilationHash(), nextCompiledDependency.compilationHash());
        assertEquals(1, compilations(nextCompiler));

        Files.writeString(apiSource, "package dependency; public class Api { private static final int INTERNAL = 2; public static final int VALUE = INTERNAL; public static int value() { return 2; } }");
        var changedCompiler = new ModuleCompiler(outputs);
        var changedFinder = SourceModuleFinder.of(sourceRoot);
        var changedDependency = (SourceModuleReference) changedFinder.find("dependency.mod").orElseThrow();
        var changedApplication = (SourceModuleReference) changedFinder.find("application.mod").orElseThrow();
        changedDependency.bind(environment(changedCompiler, List.of()));
        changedApplication.bind(environment(changedCompiler, List.of(new Dependency("dependency.mod", changedDependency, null, false, false))));
        var changedCompiledDependency = changedDependency.compiledModule();
        try (var ignored = changedApplication.open()) {
            assertTrue(ignored.find("application/Application.class")
                              .isPresent());
        }

        assertNotEquals(nextCompiledDependency.compilationHash(), changedCompiledDependency.compilationHash());
        assertEquals(2, compilations(changedCompiler));
    }

    @Test
    void compilationExcludesNestedSourceModules(@TempDir Path tempDir) throws Exception {
        var sourceRoot = tempDir.resolve("src");
        var outer = Files.createDirectories(sourceRoot.resolve("outer.mod/p"));
        Files.writeString(outer.getParent()
                               .resolve("module-info.java"),
                "module outer.mod {}");
        Files.writeString(outer.resolve("Outer.java"), "package p; public class Outer {}");
        var nested = Files.createDirectories(outer.getParent()
                .resolve("fixtures/nested.mod/q"));
        Files.writeString(nested.getParent()
                                .resolve("module-info.java"),
                "module nested.mod {}");
        Files.writeString(nested.resolve("Nested.java"), "package q; public class Nested {}");

        var reference = (SourceModuleReference) SourceModuleFinder.of(sourceRoot)
                .find("outer.mod")
                .orElseThrow();
        reference.bind(new CompilationEnvironment(new ModuleCompiler(tempDir.resolve("cache")), List.of(),
                dependency -> {
                    throw new AssertionError("Module has no dependencies");
                },
                null, null));

        try (var reader = reference.open()) {
            assertTrue(reader.find("p/Outer.class")
                             .isPresent());
            assertTrue(reader.find("q/Nested.class")
                             .isEmpty());
        }
    }

    @Test
    void systemExportUsesTheSystemImage(@TempDir Path tempDir) throws Exception {
        var source = Files.createDirectories(tempDir.resolve("src/simple.mod"));
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @addExports java.base/jdk.internal.misc=simple.mod */
                module simple.mod {}
                """);
        var packageDirectory = Files.createDirectories(source.resolve("p"));
        Files.writeString(packageDirectory.resolve("Example.java"),
                """
                package p;
                public class Example {
                    public jdk.internal.misc.Unsafe unsafe;
                }
                """);
        var reference = (SourceModuleReference) SourceModuleFinder.of(tempDir.resolve("src"))
                .find("simple.mod")
                .orElseThrow();
        reference.bind(new CompilationEnvironment(new ModuleCompiler(tempDir.resolve("cache")), List.of(),
                dependency -> {
                    throw new AssertionError("Module has no dependencies");
                },
                null, null));

        try (var reader = reference.open()) {
            assertTrue(reader.find("p/Example.class")
                             .isPresent());
        }
    }

    @Test
    void compilesIndependentSourceModulesConcurrently(@TempDir Path tempDir) throws Exception {
        var sourceRoot = tempDir.resolve("src");
        for (var moduleName : List.of("first.mod", "second.mod")) {
            var module = Files.createDirectories(sourceRoot.resolve(moduleName));
            Files.writeString(module.resolve("module-info.java"), "module " + moduleName + " {}\n");
            var packageDirectory = Files.createDirectories(module.resolve("p"));
            Files.writeString(packageDirectory.resolve("Broken.java"), "package p; class Broken { Missing type; }\n");
        }

        var diagnostics = ConcurrentHashMap.<URI>newKeySet();
        var entered = new CountDownLatch(2);
        var concurrent = new AtomicBoolean(true);
        var compiler = new ModuleCompiler(tempDir.resolve("cache"),
                diagnostic -> {
                    if (diagnostic.getKind() != Kind.ERROR || diagnostic.getSource() == null || !diagnostics.add(diagnostic.getSource().toUri())) {
                        return;
                    }
                    entered.countDown();
                    try {
                        if (!entered.await(5, TimeUnit.SECONDS)) {
                            concurrent.set(false);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        concurrent.set(false);
                    }
                });
        var finder = SourceModuleFinder.of(sourceRoot);
        var references = List.of("first.mod", "second.mod").stream()
                .map(name -> (SourceModuleReference) finder.find(name).orElseThrow())
                .toList();
        var environment = new CompilationEnvironment(compiler, List.of(), dependency -> {
            throw new AssertionError("Module has no dependencies");
        },
                null, null);
        references.forEach(reference -> reference.bind(environment));

        assertThrows(IOException.class, () -> SourceModuleReference.compileAll(references));
        assertTrue(concurrent.get());
        assertEquals(0, entered.getCount());
    }

    @Test
    @DisplayName("open() on an unbound source module throws IllegalStateException")
    void unboundSourceModuleOpenThrows(@TempDir Path tempDir) throws IOException {
        var sourceRoot = tempDir.resolve("src");
        var moduleDir = sourceRoot.resolve("simple.mod");
        Files.createDirectories(moduleDir);

        Files.writeString(moduleDir.resolve("module-info.java"), "module simple.mod {}");

        var sourceFinder = SourceModuleFinder.of(sourceRoot);
        var ref = sourceFinder.find("simple.mod").orElseThrow();
        assertThrows(IllegalStateException.class, ref::open);
    }

    private static CompilationEnvironment environment(ModuleCompiler compiler, List<Dependency> dependencies) {
        return new CompilationEnvironment(compiler, dependencies, dependency -> {
            throw new AssertionError("Unexpected binary dependency: " + dependency.name());
        },
                null,
                null);
    }

    @Test
    void openReusesCompilationFromTheSourceReference(@TempDir Path tempDir) throws Exception {
        var source = Files.createDirectories(tempDir.resolve("src/simple.mod"));
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @enableNativeAccess simple.mod */
                module simple.mod {}
                """);
        var packageDirectory = Files.createDirectories(source.resolve("p"));
        Files.writeString(packageDirectory.resolve("Example.java"), "package p; public class Example {}\n");
        Files.writeString(source.resolve("application.properties"), "enabled=true\n");
        var compiler = new ModuleCompiler(tempDir.resolve("cache"));
        var environment = new CompilationEnvironment(compiler, List.of(), dependency -> {
            throw new AssertionError("Module has no dependencies");
        },
                null, null);
        var firstReference = (SourceModuleReference) SourceModuleFinder.of(tempDir.resolve("src"))
                .find("simple.mod")
                .orElseThrow();
        firstReference.bind(environment);
        try (var first = firstReference.open()) {
            assertTrue(first.find("module-info.class")
                            .isPresent());
            assertTrue(first.find("p/Example.class")
                            .isPresent());
            assertEquals("enabled=true\n", StandardCharsets.UTF_8
                    .decode(first.read("application.properties").orElseThrow())
                    .toString());
            var moduleInfoBuffer = first.read("module-info.class").orElseThrow();
            var moduleInfoBytes = new byte[moduleInfoBuffer.remaining()];
            moduleInfoBuffer.get(moduleInfoBytes);
            var moduleInfo = ClassFile.of(ModuleRuntimeAccessAttribute.mapperOption()).parse(moduleInfoBytes);
            assertEquals(List.of("simple.mod"),
                    moduleInfo.findAttribute(ModuleRuntimeAccessAttribute.mapper())
                              .orElseThrow()
                              .options()
                              .enableNativeAccess());
        }

        assertEquals(1, compilations(compiler));

        var secondReference = (SourceModuleReference) SourceModuleFinder.of(tempDir.resolve("src"))
                .find("simple.mod")
                .orElseThrow();
        secondReference.bind(environment);
        try (var second = secondReference.open()) {
            assertTrue(second.find("module-info.class")
                             .isPresent());
        }
        assertEquals(1, compilations(compiler));
    }
}
