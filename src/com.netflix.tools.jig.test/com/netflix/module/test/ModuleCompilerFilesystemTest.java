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

package com.netflix.module.test;

import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.compile.ModuleCompiler;
import com.netflix.module.compile.ModuleCompiler.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.netflix.module.test.ModuleCompilerMeasurements.compilations;
import static com.netflix.module.test.ModuleCompilerMeasurements.parsedSources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleCompilerFilesystemTest {
    @Test
    void requiresANamedModule() {
        assertThrows(
                NullPointerException.class,
                () -> new Configuration(
                        null,
                        OptionalInt.of(25),
                        false,
                        Optional.empty(),
                        ModuleRuntimeAccessOptions.EMPTY,
                        List.of(),
                        entry -> {
                            throw new AssertionError("No dependencies");
                        }));
    }

    @Test
    void publicCompilerReusesImmutableOutputAcrossWorktrees(@TempDir Path directory) throws Exception {
        var firstSources = directory.resolve("first-sources");
        var secondSources = directory.resolve("second-sources");
        writeSources(firstSources, 1);
        writeSources(secondSources, 1);
        var outputs = directory.resolve("cache");
        var configuration = configuration();

        var firstCompiler = new ModuleCompiler(outputs);
        var first = firstCompiler.compile(firstSources, configuration, 25);
        assertEquals(1, compilations(firstCompiler));
        var firstPath = firstCompiler.modulePath("example.module", first).modulePath();

        var secondCompiler = new ModuleCompiler(directory.resolve("cache"));
        var second = secondCompiler.compile(secondSources, configuration, 25);

        assertEquals(0, compilations(secondCompiler));
        assertEquals(first, second);
        assertEquals(firstPath, secondCompiler.modulePath("example.module", second)
                .modulePath());
        assertEquals(first.hash(), second.hash());
    }

    @Test
    void keepsSourceObservationsSeparateFromConfigurationReconciliation(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("sources");
        writeSources(sources, 1);
        var cacheRoot = directory.resolve("cache");

        new ModuleCompiler(cacheRoot).compile(sources, configuration(), 25);

        var module = cacheRoot.resolve("modules/example.module");
        assertTrue(Files.isDirectory(module.resolve("observations")));
        final Path output;
        try (var configurations = Files.list(module.resolve("configuration"))) {
            output = configurations.filter(Files::isDirectory)
                                   .findFirst()
                                   .orElseThrow();
        }
        assertTrue(Files.isDirectory(output.resolve("reconciliations")));
        assertFalse(Files.exists(output.resolve("observations")));
    }

    @Test
    void newWorktreeReconcilesTheLatestObservations(@TempDir Path directory) throws Exception {
        var firstSources = directory.resolve("first-sources");
        var secondSources = directory.resolve("second-sources");
        writeIndependentSources(firstSources, 1, 1);
        writeIndependentSources(secondSources, 1, 2);
        var cacheRoot = directory.resolve("cache");
        new ModuleCompiler(cacheRoot).compile(firstSources, configuration(), 25);

        var compiler = new ModuleCompiler(cacheRoot);
        compiler.compile(secondSources, configuration(), 25);

        assertEquals(1, compilations(compiler));
        assertEquals(1, parsedSources(compiler));
    }

    @Test
    void sourceRootUsesItsOwnObservationsRatherThanTheLatestOutput(@TempDir Path directory) throws Exception {
        var firstSources = directory.resolve("first-sources");
        var otherSources = directory.resolve("other-sources");
        writeIndependentSources(firstSources, 1, 1);
        writeIndependentSources(otherSources, 10, 10);
        var cacheRoot = directory.resolve("cache");
        new ModuleCompiler(cacheRoot).compile(firstSources, configuration(), 25);
        new ModuleCompiler(cacheRoot).compile(otherSources, configuration(), 25);
        Files.writeString(firstSources.resolve("p/B.java"), independentClass("B", 2));

        var compiler = new ModuleCompiler(cacheRoot);
        compiler.compile(firstSources, configuration(), 25);

        assertEquals(1, compilations(compiler));
        assertEquals(1, parsedSources(compiler));
    }

    @Test
    void changedSourcePublishesANewModuleDirectory(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("sources");
        writeSources(sources, 1);
        var cacheRoot = directory.resolve("cache");
        var firstCompiler = new ModuleCompiler(cacheRoot);
        var first = firstCompiler.compile(sources, configuration(), 25);
        Files.writeString(sources.resolve("p/Api.java"), api(2));

        var secondCompiler = new ModuleCompiler(cacheRoot);
        var second = secondCompiler.compile(sources, configuration(), 25);
        var output = secondCompiler.modulePath("example.module", second).modulePath();

        assertNotEquals(first, second);
        assertEquals(1, compilations(secondCompiler));
        assertTrue(ModuleFinder.of(output)
                .find("example.module")
                .isPresent());
    }

    @Test
    void oneSourceEditCompilesOnlyThatSource(@TempDir Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("sources/p")).getParent();
        Files.writeString(sources.resolve("module-info.java"), "module example.module { exports p; }\n");
        Files.writeString(sources.resolve("p/A.java"),
                """
                package p;
                public class A {
                    static class One {}
                    static class Two {}
                    static class Three {}
                }
                """);
        Files.writeString(sources.resolve("p/B.java"), independentClass("B", 1));
        var root = directory.resolve("cache");
        new ModuleCompiler(root).compile(sources, configuration(), 25);
        Files.writeString(sources.resolve("p/B.java"), independentClass("B", 2));

        var compiler = new ModuleCompiler(root);
        compiler.compile(sources, configuration(), 25);

        assertEquals(1, compilations(compiler));
        assertEquals(1, parsedSources(compiler));
    }

    @Test
    void usesAPatchUntilHalfTheModuleDiffersFromItsBase(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("sources");
        Files.createDirectories(sources.resolve("p"));
        Files.writeString(sources.resolve("module-info.java"), "module example.module { exports p; }\n");
        for (var name : List.of("A", "B", "C", "D")) {
            Files.writeString(sources.resolve("p/" + name + ".java"), independentClass(name, 1));
        }
        var root = directory.resolve("cache");
        var firstCompiler = new ModuleCompiler(root);
        var first = firstCompiler.compile(sources, configuration(), 25);
        var firstPath = firstCompiler.modulePath("example.module", first);
        assertFalse(firstPath.hasPatchModule());

        Files.writeString(sources.resolve("p/A.java"), independentClass("A", 2));
        var patchCompiler = new ModuleCompiler(root);
        var patched = patchCompiler.compile(sources, configuration(), 25);
        var patchedPath = patchCompiler.modulePath("example.module", patched);
        assertTrue(patchedPath.hasPatchModule());
        assertEquals(firstPath.modulePath(), patchedPath.modulePath());
        assertTrue(Files.isRegularFile(patchedPath.patchModulePath()
                .resolve("p/A.class")));
        assertFalse(Files.exists(patchedPath.patchModulePath()
                .resolve("p/B.class")));
        var materialized = patchCompiler.write(patched, directory.resolve("materialized"));
        assertEquals(patched.hash(),
                ModuleHash.moduleSha256(ModuleFinder.of(materialized)
                        .find("example.module")
                        .orElseThrow()));
        var old = FileTime.from(Instant.EPOCH);
        Files.setLastModifiedTime(patchedPath.modulePath(), old);
        Files.setLastModifiedTime(patchedPath.patchModulePath(), old);
        patchCompiler.modulePath("example.module", patched);
        assertTrue(Files.getLastModifiedTime(patchedPath.modulePath()).compareTo(old) > 0);
        assertTrue(Files.getLastModifiedTime(patchedPath.patchModulePath()).compareTo(old) > 0);

        var unchanged = new ModuleCompiler(root);
        var reused = unchanged.compile(sources, configuration(), 25);
        assertEquals(0, compilations(unchanged));
        assertEquals(patched, reused);

        Files.writeString(sources.resolve("p/B.java"), independentClass("B", 2));
        Files.writeString(sources.resolve("p/C.java"), independentClass("C", 2));
        var baseCompiler = new ModuleCompiler(root);
        var rebased = baseCompiler.compile(sources, configuration(), 25);
        assertFalse(baseCompiler.modulePath("example.module", rebased)
                                .hasPatchModule());
    }

    @Test
    void aPreviousPatchShadowsItsBaseDuringIncrementalCompilation(@TempDir Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("sources/p")).getParent();
        Files.writeString(sources.resolve("module-info.java"), "module example.module { exports p; }\n");
        Files.writeString(sources.resolve("p/Contracts.java"),
                """
                package p;
                public final class Contracts {
                    public interface Resolver { void original(); }
                }
                """);
        Files.writeString(sources.resolve("p/Implementation.java"),
                """
                package p;
                public final class Implementation implements Contracts.Resolver {
                    @Override public void original() {}
                }
                """);
        for (var name : List.of("A", "B", "C", "D")) {
            Files.writeString(sources.resolve("p/" + name + ".java"), independentClass(name, 1));
        }
        var root = directory.resolve("cache");
        new ModuleCompiler(root).compile(sources, configurationWithoutRelease(), 25);

        Files.writeString(sources.resolve("p/Contracts.java"),
                """
                package p;
                public final class Contracts {
                    public interface Resolver {
                        void original();
                        void added();
                    }
                }
                """);
        Files.writeString(sources.resolve("p/Implementation.java"),
                """
                package p;
                public final class Implementation implements Contracts.Resolver {
                    @Override public void original() {}
                    @Override public void added() {}
                }
                """);
        var patchCompiler = new ModuleCompiler(root);
        var patched = patchCompiler.compile(sources, configurationWithoutRelease(), 25);
        assertTrue(patchCompiler.modulePath("example.module", patched)
                                .hasPatchModule());

        Files.writeString(sources.resolve("p/Implementation.java"),
                """
                package p;
                public final class Implementation implements Contracts.Resolver {
                    @Override public void original() { System.out.println("changed"); }
                    @Override public void added() {}
                }
                """);

        var incrementalCompiler = new ModuleCompiler(root);
        incrementalCompiler.compile(sources, configurationWithoutRelease(), 25);
        assertEquals(1, parsedSources(incrementalCompiler));
    }

    @Test
    void publishesReadOnlyModuleTrees(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("sources");
        writeSources(sources, 1);
        var compiler = new ModuleCompiler(directory.resolve("cache"));
        var compilation = compiler.compile(sources, configuration(), 25);
        var module = compiler.modulePath("example.module", compilation).modulePath();

        assertReadOnlyTree(module);
    }

    @Test
    void concurrentlyPublishesTheSameCompleteModule(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("sources");
        writeSources(sources, 1);
        var resources = Files.createDirectories(sources.resolve("resources"));
        for (int index = 0; index < 1_000; index++) {
            Files.writeString(resources.resolve(Integer.toString(index)), "resource " + index);
        }
        var root = directory.resolve("cache");
        new ModuleCompiler(root).compile(sources, configuration(), 25);
        Files.writeString(sources.resolve("p/Api.java"), api(2));
        var patchCompiler = new ModuleCompiler(root);
        var compilation = patchCompiler.compile(sources, configuration(), 25);
        assertTrue(patchCompiler.modulePath("example.module", compilation)
                                .hasPatchModule());

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var paths = new ConcurrentLinkedQueue<Path>();
        var threads = new ArrayList<Thread>();
        for (int index = 0; index < 2; index++) {
            var compiler = new ModuleCompiler(root);
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    paths.add(compiler.modulePath("example.module", compilation, false).modulePath());
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }));
        }
        ready.await();
        start.countDown();
        for (var thread : threads) {
            thread.join();
        }

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(1,
                paths.stream()
                        .distinct()
                        .count());
        assertReadOnlyTree(paths.element());
    }

    @Test
    void expiresOldOutputsOnlyForModulesUsedByThisCompiler(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("sources");
        writeSources(sources, 1);
        var cacheRoot = directory.resolve("cache");
        var compiler = new ModuleCompiler(cacheRoot);
        compiler.compile(sources, configuration(), 25);
        var module = cacheRoot.resolve("modules/example.module");
        final Path output;
        try (var configurations = Files.list(module.resolve("configuration"))) {
            output = configurations.filter(Files::isDirectory)
                                   .findFirst()
                                   .orElseThrow();
        }
        var oldBase = writeOutput(output.resolve("base/old/p/Old.class"));
        var neededBase = writeOutput(output.resolve("base/needed/p/Api.class"));
        var oldPatch = writeOutput(output.resolve("patch/old/needed/p/Api.class"));
        var recentPatch = writeOutput(output.resolve("patch/recent/needed/p/Api.class"));
        var oldConfiguration = writeOutput(module.resolve("configuration/old/base/old/p/Api.class"));
        var otherModule = writeOutput(cacheRoot.resolve("modules/other.module/configuration/old/base/old/p/Api.class"));
        var old = FileTime.from(Instant.now()
                .minus(Duration.ofDays(8)));
        for (var path : List.of(oldBase, neededBase, oldPatch, oldConfiguration, otherModule)) {
            Files.setLastModifiedTime(path, old);
        }
        Files.setLastModifiedTime(module, old);

        compiler.close();

        assertFalse(Files.exists(oldBase));
        assertFalse(Files.exists(oldPatch));
        assertFalse(Files.exists(oldConfiguration));
        assertTrue(Files.isDirectory(neededBase));
        assertTrue(Files.isDirectory(recentPatch));
        assertTrue(Files.isDirectory(otherModule));
    }

    @Test
    void writesAnExplicitOutputDirectory(@TempDir Path directory) throws Exception {
        var sources = directory.resolve("sources");
        writeSources(sources, 1);
        var compiler = new ModuleCompiler(directory.resolve("cache"));
        var compilation = compiler.compile(sources, configuration(), 25);

        var output = compiler.write(compilation, directory.resolve("explicit/example.module"));

        assertEquals(compilation.hash(),
                ModuleHash.moduleSha256(ModuleFinder.of(output)
                        .find("example.module")
                        .orElseThrow()));
    }

    private static void assertReadOnlyTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isDirectory).toList()) {
                var permissions = Files.getPosixFilePermissions(path);
                assertFalse(permissions.contains(PosixFilePermission.OWNER_WRITE), path.toString());
                assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE), path.toString());
                assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE), path.toString());
            }
        }
    }

    private static Configuration configuration() {
        return new Configuration(
                "example.module",
                OptionalInt.of(25),
                false,
                Optional.empty(),
                ModuleRuntimeAccessOptions.EMPTY,
                List.of(),
                entry -> {
                    throw new AssertionError("No dependencies");
                });
    }

    private static Configuration configurationWithoutRelease() {
        return new Configuration(
                "example.module",
                OptionalInt.empty(),
                false,
                Optional.empty(),
                ModuleRuntimeAccessOptions.EMPTY,
                List.of(),
                entry -> {
                    throw new AssertionError("No dependencies");
                });
    }

    private static Path writeOutput(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class");
        return file.getParent().getParent();
    }

    private static void writeSources(Path root, int value) throws Exception {
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("module-info.java"),
                """
                module example.module { exports p; }
                """);
        Files.writeString(root.resolve("p/Api.java"), api(value));
        Files.writeString(root.resolve("p/Use.java"),
                """
                package p;
                public class Use { public int value() { return Api.value(); } }
                """);
    }

    private static void writeIndependentSources(Path root, int a, int b) throws Exception {
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("module-info.java"), "module example.module { exports p; }\n");
        Files.writeString(root.resolve("p/A.java"), independentClass("A", a));
        Files.writeString(root.resolve("p/B.java"), independentClass("B", b));
    }

    private static String independentClass(String name, int value) {
        return """
                package p;
                public class %s { public static int value() { return %d; } }
                """
                .formatted(name, value);
    }

    private static String api(int value) {
        return """
                package p;
                public class Api { public static int value() { return %d; } }
                """
                .formatted(value);
    }
}
