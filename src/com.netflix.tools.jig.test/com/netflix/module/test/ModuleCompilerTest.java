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

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import com.netflix.module.ModuleRuntimeAccessAttribute;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.compile.ModuleCompiler;
import com.netflix.module.compile.ModuleCompiler.Configuration;
import com.netflix.module.compile.ModuleCompiler.Options;
import com.netflix.module.compile.ModulePathEntry;
import com.netflix.module.compile.internal.CompilationDiagnostic;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;
import com.netflix.module.compile.internal.SourceModuleManifest;
import com.netflix.module.compile.internal.StoredDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.netflix.module.test.ModuleCompilerMeasurements.compilations;
import static com.netflix.module.test.ModuleCompilerMeasurements.parsedSources;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleCompilerTest {
    @FunctionalInterface
    private interface ModulePathResolver {
        Path resolve(String moduleName, StandardLocation location) throws IOException;
    }

    private record PatchFixture(Path source, Path dependency, Path patch,
            ModuleHash dependencyHash, ModuleHash patchHash) {
        Configuration configuration(boolean includePatch) {
            return ModuleCompilerTest.configuration(
                    "example.mod",
                    25,
                    false,
                    null,
                    ModuleRuntimeAccessOptions.EMPTY,
                    Map.of("dependency.mod", dependencyHash),
                    Map.of(),
                    includePatch ? Map.of("dependency.mod", patchHash) : Map.of(),
                    (moduleName, location) -> switch (location) {
                        case MODULE_PATH -> dependency;
                        case PATCH_MODULE_PATH -> patch;
                        case UPGRADE_MODULE_PATH -> throw new AssertionError(location);
                        default -> throw new AssertionError(location);
                    });
        }
    }

    @Test
    void modulePathEntriesEnforceLocationHashType() {
        var module = new ModuleHash(Type.MODULE, "sha256", "0".repeat(64));
        var patch = new ModuleHash(Type.PATCH, "sha256", "1".repeat(64));

        assertEquals(module, new ModulePathEntry("example.module", StandardLocation.MODULE_PATH, module)
                .hash());
        assertEquals(patch, new ModulePathEntry("example.module", StandardLocation.PATCH_MODULE_PATH, patch)
                .hash());
        assertThrows(IllegalArgumentException.class, () -> new ModulePathEntry("example.module", StandardLocation.PATCH_MODULE_PATH, module));
    }

    @Test
    void compilationDoesNotCreateAnObjectStore(@TempDir Path directory) throws Exception {
        var sourceRoot = Files.createDirectories(directory.resolve("source"));
        Files.writeString(sourceRoot.resolve("module-info.java"), "module example.mod {}\n");
        Files.createDirectories(sourceRoot.resolve("p"));
        Files.writeString(sourceRoot.resolve("p/Example.java"), "package p; class Example { int answer() { return 42; } }\n");
        var outputsRoot = directory.resolve("outputs");

        new ModuleCompiler(outputsRoot).compile(sourceRoot, configuration("example.mod", 25), 25);

        assertFalse(Files.exists(outputsRoot.resolve("compilation")));
    }

    @Test
    void compilesAnonymousClassThatRefersToItself(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("source/p"));
        Files.writeString(source.getParent()
                                .resolve("module-info.java"),
                "module example.mod {}\n");
        Files.writeString(source.resolve("Example.java"),
                """
                package p;
                public class Example {
                    Object value = new Object() {
                        public String toString() {
                            return this.getClass().getName();
                        }
                    };
                }
                """);
        var compiler = new ModuleCompiler(directory.resolve("outputs"));

        compiler.compile(source.getParent(), configuration("example.mod", 25), 25);

        assertEquals(1, compilations(compiler));
    }

    @Test
    void preservesRuntimeAccessMetadataOutsideCanonicalSignatures(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("source"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod {}\n");
        var runtimeAccess = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("example.mod")
                .build();
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                runtimeAccess,
                Map.of(),
                Map.of(),
                (moduleName, location) -> {
                    throw new AssertionError("Compilation has no dependencies");
                });
        var compiler = new ModuleCompiler(directory.resolve("outputs"));

        var result = compiler.compile(source, configuration, 25);
        var module = compiler.write(result, directory.resolve("module"));
        var moduleInfo = ClassFile.of(ModuleRuntimeAccessAttribute.mapperOption()).parse(Files.readAllBytes(module.resolve("module-info.class")));

        assertEquals(result.hash(),
                ModuleHash.moduleSha256(ModuleFinder.of(module)
                        .find("example.mod")
                        .orElseThrow()));
        assertEquals(runtimeAccess,
                moduleInfo.findAttribute(ModuleRuntimeAccessAttribute.mapper())
                          .orElseThrow()
                          .options());
    }

    @Test
    void recordsSelectedDependencyVersionInStandardModuleAttribute(@TempDir Path directory) throws Exception {
        Path dependencySource = Files.createDirectories(directory.resolve("dependency-source"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod {}\n");
        Path dependency = directory.resolve("dependency");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "--release",
                "25",
                "--module-version",
                "2.0",
                "-d",
                dependency.toString(),
                dependencySource.resolve("module-info.java").toString()));
        ModuleHash dependencyHash = ModuleHash.moduleSha256(ModuleFinder.of(dependency)
                .find("dependency.mod")
                .orElseThrow());

        Path source = Files.createDirectories(directory.resolve("source"));
        Files.writeString(source.resolve("module-info.java"),
                "module example.mod { requires dependency.mod; }\n");
        var runtimeAccess = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("example.mod")
                .build();
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                runtimeAccess,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                (moduleName, location) -> dependency);
        var compiler = new ModuleCompiler(directory.resolve("outputs"));

        var result = compiler.compile(source, configuration, 25);
        Path module = compiler.write(result, directory.resolve("module"));
        var descriptor = ModuleFinder.of(module)
                .find("example.mod")
                .orElseThrow()
                .descriptor();
        var requirement = descriptor.requires().stream()
                .filter(candidate -> candidate.name().equals("dependency.mod"))
                .findFirst()
                .orElseThrow();

        assertEquals(Version.parse("2.0"), requirement.compiledVersion().orElseThrow());
    }

    @Test
    void resolvesModulePathAfterParsingWhenCompilationIsRequired(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api { public static int value() { return 42; } }
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");
        var dependencyReference = ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow();

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example { int value = dependency.Api.value(); }
                """);
        var dependencyHash = ModuleHash.moduleSha256(dependencyReference);
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);

        var compiler = new ModuleCompiler(directory.resolve("outputs"));
        compiler.compile(source, configuration, 25);
        assertEquals(1, compilations(compiler));
        assertEquals(2, parsedSources(compiler));
    }

    @Test
    void compilesWildcardImportFromModulePath(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api {}
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");
        var dependencyHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                import dependency.*;
                public class Example { Api value; }
                """);
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                (moduleName, location) -> dependencyPath);
        var compiler = new ModuleCompiler(directory.resolve("outputs"));

        compiler.compile(source, configuration, 25);

        assertEquals(1, compilations(compiler));
    }

    @Test
    void modulePathAdditionInvalidatesWildcardLookup(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Other.java"),
                """
                package dependency;
                public class Other {}
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("chosen"));
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("chosen/Api.java"),
                """
                package chosen;
                public class Api {}
                """);
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                import chosen.*;
                import dependency.*;
                public class Example { Api value; }
                """);

        var firstHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var firstConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", firstHash),
                Map.of(),
                (moduleName, location) -> dependencyPath);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, firstConfiguration, 25);

        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api {}
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var secondHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var secondConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", secondHash),
                Map.of(),
                (moduleName, location) -> dependencyPath);

        assertThrows(IOException.class, () -> new ModuleCompiler(directory.resolve("clean-outputs")).compile(source, secondConfiguration, 25));
        var incremental = new ModuleCompiler(root);
        assertThrows(IOException.class, () -> incremental.compile(source, secondConfiguration, 25));
    }

    @Test
    void modulePathChangeRecompilesTheCurrentModule(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api { public static int value() { return 42; } }
                """);
        var unused = dependencySource.resolve("dependency/Unused.java");
        Files.writeString(unused,
                """
                package dependency;
                public class Unused { public static int value() { return 1; } }
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public int value() { return dependency.Api.value(); }
                }
                """);

        var firstHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var firstConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", firstHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);
        var root = directory.resolve("outputs");
        var firstCompiler = new ModuleCompiler(root);
        var first = firstCompiler.compile(source, firstConfiguration, 25);
        assertEquals(1, compilations(firstCompiler));
        var firstModule = firstCompiler.write(first, directory.resolve("first"));
        var firstClass = Files.readAllBytes(firstModule.resolve("example/Example.class"));

        Files.writeString(unused,
                """
                package dependency;
                public class Unused { public static int value() { return 2; } }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var nextHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        assertNotEquals(firstHash, nextHash);
        var nextConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", nextHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);

        var nextCompiler = new ModuleCompiler(root);
        var next = nextCompiler.compile(source, nextConfiguration, 25);
        var nextModule = nextCompiler.write(next, directory.resolve("next"));
        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        var cleanResult = clean.compile(source, nextConfiguration, 25);
        var cleanModule = clean.write(cleanResult, directory.resolve("clean"));

        assertEquals(1, compilations(nextCompiler));
        assertEquals(2, parsedSources(nextCompiler));
        assertArrayEquals(firstClass, Files.readAllBytes(nextModule.resolve("example/Example.class")));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("example/Example.class")), Files.readAllBytes(nextModule.resolve("example/Example.class")));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("module-info.class")), Files.readAllBytes(nextModule.resolve("module-info.class")));
    }

    @Test
    void revertingPatchedClassToBaseDropsThePreviousPatch(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod {}\n");
        Files.writeString(source.resolve("p/Unchanged.java"), "package p; public class Unchanged {}\n");
        var example = source.resolve("p/Example.java");
        var originalSource =
                """
                package p;
                public class Example { public int value() { return 1; } }
                """;
        Files.writeString(example, originalSource);
        var configuration = configuration("example.mod", 25);
        var root = directory.resolve("outputs");
        var firstCompiler = new ModuleCompiler(root);
        var first = firstCompiler.compile(source, configuration, 25);
        var firstModule = firstCompiler.write(first, directory.resolve("first"));

        Files.writeString(example,
                """
                package p;
                public class Example { public int value() { return 2; } }
                """);
        var patchedCompiler = new ModuleCompiler(root);
        var patched = patchedCompiler.compile(source, configuration, 25);
        assertTrue(patchedCompiler.modulePath("example.mod", patched)
                .hasPatchModule());
        var patchedModule = patchedCompiler.write(patched, directory.resolve("patched"));
        assertFalse(Arrays.equals(Files.readAllBytes(firstModule.resolve("p/Example.class")), Files.readAllBytes(patchedModule.resolve("p/Example.class"))));

        Files.writeString(example, originalSource + "// Distinct source, original bytecode.\n");
        var revertedCompiler = new ModuleCompiler(root);
        var reverted = revertedCompiler.compile(source, configuration, 25);
        var revertedModule = revertedCompiler.write(reverted, directory.resolve("reverted"));

        assertArrayEquals(Files.readAllBytes(firstModule.resolve("p/Example.class")), Files.readAllBytes(revertedModule.resolve("p/Example.class")));
    }

    @Test
    void modulePathMemberChangeRecompilesItsConsumer(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        var api = dependencySource.resolve("dependency/Api.java");
        Files.writeString(api,
                """
                package dependency;
                public class Api { public static int value() { return 42; } }
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public long value() { return dependency.Api.value(); }
                }
                """);
        var root = directory.resolve("outputs");
        var firstHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var firstConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", firstHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);
        new ModuleCompiler(root).compile(source, firstConfiguration, 25);

        Files.writeString(api,
                """
                package dependency;
                public class Api { public static long value() { return 42L; } }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var nextHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var nextConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", nextHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);

        var incremental = new ModuleCompiler(root);
        var incrementalResult = incremental.compile(source, nextConfiguration, 25);
        assertEquals(1, compilations(incremental));
        assertEquals(2, parsedSources(incremental));

        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        var cleanResult = clean.compile(source, nextConfiguration, 25);
        var incrementalModule = incremental.write(incrementalResult, directory.resolve("incremental"));
        var cleanModule = clean.write(cleanResult, directory.resolve("clean"));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("example/Example.class")), Files.readAllBytes(incrementalModule.resolve("example/Example.class")));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("module-info.class")), Files.readAllBytes(incrementalModule.resolve("module-info.class")));
    }

    @Test
    void patchModuleMemberChangeRecompilesItsConsumer(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api { public static int value() { return 1; } }
                """);
        Files.writeString(dependencySource.resolve("dependency/BaseOnly.java"),
                """
                package dependency;
                public class BaseOnly { public static int value() { return 10; } }
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");
        var dependencyHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());

        var patchSource = directory.resolve("patch-source/dependency/Api.java");
        var unusedPatchSource = directory.resolve("patch-source/dependency/Unused.java");
        var patchPath = directory.resolve("patch-classes");
        Files.createDirectories(patchSource.getParent());
        Files.writeString(patchSource,
                """
                package dependency;
                public class Api { public static int value() { return 2; } }
                """);
        Files.writeString(unusedPatchSource,
                """
                package dependency;
                public class Unused { public static int value() { return 2; } }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "-d",
                        patchPath.toString(),
                        patchSource.toString(),
                        unusedPatchSource.toString()));
        var firstPatchHash = ModuleHash.patchSha256(patchPath);

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public long value() {
                        return dependency.Api.value() + dependency.BaseOnly.value();
                    }
                }
                """);
        var root = directory.resolve("outputs");
        var firstConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                Map.of("dependency.mod", firstPatchHash),
                (moduleName, location) -> switch (location) {
                    case MODULE_PATH -> dependencyPath;
                    case PATCH_MODULE_PATH -> patchPath;
                    case UPGRADE_MODULE_PATH -> throw new AssertionError(location);
                    default -> throw new AssertionError(location);
                });
        var firstCompiler = new ModuleCompiler(root);
        var firstResult = firstCompiler.compile(source, firstConfiguration, 25);
        var firstModule = firstCompiler.write(firstResult, directory.resolve("first"));
        var firstClass = Files.readAllBytes(firstModule.resolve("example/Example.class"));

        var unchangedPatch = new ModuleCompiler(root);
        assertEquals(firstResult, unchangedPatch.compile(source, firstConfiguration, 25));
        assertEquals(0, compilations(unchangedPatch));

        Files.writeString(unusedPatchSource,
                """
                package dependency;
                public class Unused { public static long value() { return 2L; } }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "-d",
                        patchPath.toString(),
                        patchSource.toString(),
                        unusedPatchSource.toString()));
        var unusedPatchHash = ModuleHash.patchSha256(patchPath);
        var unusedConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                Map.of("dependency.mod", unusedPatchHash),
                (moduleName, location) -> switch (location) {
                    case MODULE_PATH -> dependencyPath;
                    case PATCH_MODULE_PATH -> patchPath;
                    case UPGRADE_MODULE_PATH -> throw new AssertionError(location);
                    default -> throw new AssertionError(location);
                });
        var unusedCompiler = new ModuleCompiler(root);
        var unusedResult = unusedCompiler.compile(source, unusedConfiguration, 25);
        var unusedModule = unusedCompiler.write(unusedResult, directory.resolve("unused"));
        var cleanUnused = new ModuleCompiler(directory.resolve("clean-unused-outputs"));
        var cleanUnusedResult = cleanUnused.compile(source, unusedConfiguration, 25);
        var cleanUnusedModule = cleanUnused.write(cleanUnusedResult, directory.resolve("clean-unused"));
        assertEquals(1, compilations(unusedCompiler));
        assertEquals(2, parsedSources(unusedCompiler));
        assertArrayEquals(firstClass, Files.readAllBytes(unusedModule.resolve("example/Example.class")));
        assertArrayEquals(Files.readAllBytes(cleanUnusedModule.resolve("example/Example.class")), Files.readAllBytes(unusedModule.resolve("example/Example.class")));
        assertArrayEquals(Files.readAllBytes(cleanUnusedModule.resolve("module-info.class")), Files.readAllBytes(unusedModule.resolve("module-info.class")));

        Files.writeString(patchSource,
                """
                package dependency;
                public class Api { public static long value() { return 2L; } }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "-d",
                        patchPath.toString(),
                        patchSource.toString(),
                        unusedPatchSource.toString()));
        var nextPatchHash = ModuleHash.patchSha256(patchPath);
        assertNotEquals(firstPatchHash, nextPatchHash);
        var nextConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                Map.of("dependency.mod", nextPatchHash),
                (moduleName, location) -> switch (location) {
                    case MODULE_PATH -> dependencyPath;
                    case PATCH_MODULE_PATH -> patchPath;
                    case UPGRADE_MODULE_PATH -> throw new AssertionError(location);
                    default -> throw new AssertionError(location);
                });

        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        var cleanResult = clean.compile(source, nextConfiguration, 25);
        var cleanModule = clean.write(cleanResult, directory.resolve("clean"));
        assertFalse(Arrays.equals(firstClass, Files.readAllBytes(cleanModule.resolve("example/Example.class"))));

        var incremental = new ModuleCompiler(root);
        var incrementalResult = incremental.compile(source, nextConfiguration, 25);
        var incrementalModule = incremental.write(incrementalResult, directory.resolve("incremental"));
        assertEquals(1, compilations(incremental));
        assertEquals(2, parsedSources(incremental));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("example/Example.class")), Files.readAllBytes(incrementalModule.resolve("example/Example.class")));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("module-info.class")), Files.readAllBytes(incrementalModule.resolve("module-info.class")));
    }

    @Test
    void addingPatchChangesEffectiveModuleView(@TempDir Path directory) throws Exception {
        var fixture = patchFixture(directory);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(fixture.source(), fixture.configuration(false), 25);

        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        var cleanResult = clean.compile(fixture.source(), fixture.configuration(true), 25);
        var cleanModule = clean.write(cleanResult, directory.resolve("clean"));

        var incremental = new ModuleCompiler(root);
        var incrementalResult = incremental.compile(fixture.source(), fixture.configuration(true), 25);
        var incrementalModule = incremental.write(incrementalResult, directory.resolve("incremental"));

        assertEquals(1, compilations(incremental));
        assertEquals(2, parsedSources(incremental));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("example/Example.class")), Files.readAllBytes(incrementalModule.resolve("example/Example.class")));
    }

    @Test
    void removingPatchChangesEffectiveModuleView(@TempDir Path directory) throws Exception {
        var fixture = patchFixture(directory);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(fixture.source(), fixture.configuration(true), 25);

        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        var cleanResult = clean.compile(fixture.source(), fixture.configuration(false), 25);
        var cleanModule = clean.write(cleanResult, directory.resolve("clean"));

        var incremental = new ModuleCompiler(root);
        var incrementalResult = incremental.compile(fixture.source(), fixture.configuration(false), 25);
        var incrementalModule = incremental.write(incrementalResult, directory.resolve("incremental"));

        assertEquals(1, compilations(incremental));
        assertEquals(2, parsedSources(incremental));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("example/Example.class")), Files.readAllBytes(incrementalModule.resolve("example/Example.class")));
    }

    @Test
    void modulePathExportRemovalRecompilesReadingSourceAndMatchesCleanFailure(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        var dependencyInfo = dependencySource.resolve("module-info.java");
        Files.writeString(dependencyInfo, "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api { public static int value() { return 42; } }
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public int value() { return dependency.Api.value(); }
                }
                """);
        var firstHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var firstConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", firstHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, firstConfiguration, 25);

        Files.writeString(dependencyInfo, "module dependency.mod {}\n");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var nextHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var nextConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", nextHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);

        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        assertThrows(IOException.class, () -> clean.compile(source, nextConfiguration, 25));

        var incremental = new ModuleCompiler(root);
        var failure = assertThrows(IOException.class, () -> incremental.compile(source, nextConfiguration, 25));
        assertTrue(failure.getMessage()
                          .startsWith("Compilation failed:"));
    }

    @Test
    void unchangedModuleHashDoesNotQueryProviders(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"), "package dependency; public class Api {}\n");
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");
        var dependencyHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"), "package example; public class Example { dependency.Api value; }\n");
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                (moduleName, location) -> dependencyPath);
        var root = directory.resolve("outputs");
        var first = new ModuleCompiler(root);
        var firstResult = first.compile(source, configuration, 25);

        var unchanged = new ModuleCompiler(root);
        assertEquals(firstResult, unchanged.compile(source, configuration, 25));
        assertEquals(0, compilations(unchanged));
        assertEquals(0, parsedSources(unchanged));
    }

    @Test
    void replaysCachedDiagnosticsAndCanForceCompilation(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("source/p")).getParent();
        Files.createDirectories(source.resolve("api"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { exports p; }\n");
        Files.writeString(source.resolve("api/Api.java"),
                """
                package api;
                public class Api {
                    public static int value() {
                        return System.getSecurityManager() == null ? 1 : 0;
                    }
                }
                """);
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example { public int value() { return api.Api.value() + 1; } }
                """);
        var root = directory.resolve("outputs");
        var initialDiagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var initial = new ModuleCompiler(root, initialDiagnostics::add);
        var configuration = configuration("example.mod", 25);
        var first = initial.compile(source, configuration, 25);
        assertTrue(initialDiagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null).contains("getSecurityManager")));

        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example {
                    public int value() {
                        return api.Api.value()
                                + (System.getSecurityManager() == null ? 2 : 3);
                    }
                }
                """);
        var replayedDiagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var replaying = new ModuleCompiler(root, replayedDiagnostics::add);
        var replayed = replaying.compile(source, configuration, 25, Options.DEFAULT);

        assertNotEquals(first, replayed);
        assertEquals(1, compilations(replaying));
        assertEquals(1, parsedSources(replaying));
        assertTrue(replayedDiagnostics.stream()
                .anyMatch(diagnostic -> (diagnostic.getKind() == Kind.WARNING || diagnostic.getKind() == Kind.MANDATORY_WARNING) && diagnostic.getMessage(null).contains("getSecurityManager")));
        assertEquals(
                List.of(source.resolve("api/Api.java").toUri(),
                        source.resolve("p/Example.java").toUri()),
                replayedDiagnostics.stream()
                        .filter(diagnostic -> diagnostic.getSource() != null)
                        .filter(diagnostic -> diagnostic.getMessage(null).contains("getSecurityManager"))
                        .map(diagnostic -> diagnostic.getSource().toUri())
                        .toList());

        var forcedDiagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var forcing = new ModuleCompiler(root, forcedDiagnostics::add);
        var forced = forcing.compile(source, configuration, 25, new Options(true, true));

        assertEquals(replayed, forced);
        assertEquals(1, compilations(forcing));
        assertEquals(3, parsedSources(forcing));
        assertTrue(forcedDiagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null).contains("getSecurityManager")));
    }

    @Test
    void refreshesDiagnosticsStoredWithoutRenderings(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("source/p")).getParent();
        Files.writeString(source.resolve("module-info.java"), "module example.mod { exports p; }\n");
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example {
                    public int value() {
                        return System.getSecurityManager() == null ? 1 : 0;
                    }
                }
                """);
        var root = directory.resolve("outputs");
        var initial = new ModuleCompiler(root);
        var configuration = configuration("example.mod", 25);
        var first = initial.compile(source, configuration, 25);
        Path statePath = null;
        State retainedState = null;
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                var state = SourceModuleManifest.readCompilation(path);
                if (state.isPresent() && !state.orElseThrow()
                        .diagnostics()
                        .isEmpty()) {
                    statePath = path;
                    retainedState = state.orElseThrow();
                    break;
                }
            }
        }
        if (statePath == null) {
            throw new AssertionError("Compilation state with diagnostics not found");
        }
        var diagnosticsWithoutRenderings = retainedState.diagnostics().stream()
                .map(compilationDiagnostic -> {
                    var diagnostic = compilationDiagnostic.diagnostic();
                    return new CompilationDiagnostic(
                            compilationDiagnostic.compilationSources(),
                            new StoredDiagnostic(
                                    diagnostic.kind(),
                                    diagnostic.code(),
                                    diagnostic.source(),
                                    diagnostic.position(),
                                    diagnostic.startPosition(),
                                    diagnostic.endPosition(),
                                    diagnostic.lineNumber(),
                                    diagnostic.columnNumber(),
                                    diagnostic.message(),
                                    ""));
                })
                .toList();
        var stateWithoutRenderings = new State(retainedState.sources(), retainedState.modulePathEntries(), retainedState.resources(),
                retainedState.moduleInfoOptionsHash(), retainedState.systemImage(), diagnosticsWithoutRenderings);
        SourceModuleManifest.writeCompilation(statePath, stateWithoutRenderings);
        var refreshedDiagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var refreshing = new ModuleCompiler(root, refreshedDiagnostics::add);

        var refreshed = refreshing.compile(source, configuration, 25);

        assertEquals(first, refreshed);
        assertEquals(1, compilations(refreshing));
        assertEquals(2, parsedSources(refreshing));
        var rendering = refreshedDiagnostics.stream()
                .map(Object::toString)
                .reduce((firstRendering, secondRendering) -> firstRendering + System.lineSeparator() + secondRendering)
                .orElseThrow();
        assertTrue(rendering.contains("warning: [removal]"), rendering);
        assertTrue(rendering.contains("return System.getSecurityManager() == null ? 1 : 0;"), rendering);
        assertTrue(rendering.contains("^"), rendering);
        assertFalse(rendering.contains("[diagnostics replayed from previous compilation]"), rendering);
        assertFalse(SourceModuleManifest.requiresDiagnosticRefresh(SourceModuleManifest.readCompilation(statePath)
                .orElseThrow()));
        var replayedDiagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var replaying = new ModuleCompiler(root, replayedDiagnostics::add);

        assertEquals(first, replaying.compile(source, configuration, 25));

        assertEquals(0, compilations(replaying));
        assertTrue(
                replayedDiagnostics.stream()
                        .filter(diagnostic -> diagnostic.getMessage(null).contains("getSecurityManager"))
                        .map(Object::toString)
                        .anyMatch(rendered -> rendered.startsWith("[diagnostics replayed from previous compilation]" + System.lineSeparator())),
                replayedDiagnostics.toString());
    }

    @Test
    void canSuppressDiagnosticsWithoutDiscardingThem(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("source/p")).getParent();
        Files.createDirectories(source.resolve("api"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { exports p; }\n");
        Files.writeString(source.resolve("api/Api.java"),
                """
                package api;
                public class Api {
                    public static int value() {
                        return System.getSecurityManager() == null ? 1 : 0;
                    }
                }
                """);
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example { public int value() { return api.Api.value(); } }
                """);
        var root = directory.resolve("outputs");
        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var compiler = new ModuleCompiler(root, diagnostics::add);
        var configuration = configuration("example.mod", 25);

        compiler.compile(source, configuration, 25, Options.SILENT);

        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
        compiler.compile(source, configuration, 25, Options.DEFAULT);
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.getMessage(null).contains("getSecurityManager")),
                diagnostics.toString());
        diagnostics.clear();
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example { public int value() { return api.Api.value() + 1; } }
                """);
        compiler.compile(source, configuration, 25, Options.DEFAULT);

        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.getMessage(null).contains("getSecurityManager")),
                diagnostics.toString());
    }

    @Test
    void moduleDescriptorChangeRecompilesCurrentModuleAndMatchesCleanOutput(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.createDirectories(dependencySource.resolve("hidden"));
        var dependencyInfo = dependencySource.resolve("module-info.java");
        Files.writeString(dependencyInfo, "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"), "package dependency; public class Api { public static int value() { return 1; } }\n");
        Files.writeString(dependencySource.resolve("hidden/Hidden.java"), "package hidden; public class Hidden {}\n");
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");
        var firstHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"), "package example; public class Example { int value = dependency.Api.value(); }\n");
        var firstConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", firstHash),
                Map.of(),
                (moduleName, location) -> dependencyPath);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, firstConfiguration, 25);

        Files.writeString(dependencyInfo, "module dependency.mod { exports dependency; opens hidden; }\n");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var nextHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var nextConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", nextHash),
                Map.of(),
                (moduleName, location) -> dependencyPath);

        var incremental = new ModuleCompiler(root);
        var incrementalResult = incremental.compile(source, nextConfiguration, 25);
        var incrementalModule = incremental.write(incrementalResult, directory.resolve("incremental"));
        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        var cleanResult = clean.compile(source, nextConfiguration, 25);
        var cleanModule = clean.write(cleanResult, directory.resolve("clean"));
        assertEquals(1, compilations(incremental));
        assertEquals(2, parsedSources(incremental));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("example/Example.class")), Files.readAllBytes(incrementalModule.resolve("example/Example.class")));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("module-info.class")), Files.readAllBytes(incrementalModule.resolve("module-info.class")));
    }

    @Test
    void transitiveRequirementRemovalRecompilesReadingSourceAndMatchesCleanFailure(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source");
        var upstreamSource = dependencySource.resolve("upstream.mod/upstream");
        Files.createDirectories(upstreamSource);
        Files.writeString(dependencySource.resolve("upstream.mod/module-info.java"), "module upstream.mod { exports upstream; }\n");
        Files.writeString(upstreamSource.resolve("Api.java"), "package upstream; public class Api { public static int value() { return 1; } }\n");
        var middleSource = dependencySource.resolve("middle.mod");
        Files.createDirectories(middleSource);
        var middleInfo = middleSource.resolve("module-info.java");
        Files.writeString(middleInfo, "module middle.mod { requires transitive upstream.mod; }\n");
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        dependencySource.toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "upstream.mod,middle.mod"));
        var upstreamPath = dependencyModules.resolve("upstream.mod");
        var middlePath = dependencyModules.resolve("middle.mod");
        var upstreamHash = ModuleHash.moduleSha256(ModuleFinder.of(upstreamPath)
                .find("upstream.mod")
                .orElseThrow());
        var firstMiddleHash = ModuleHash.moduleSha256(ModuleFinder.of(middlePath)
                .find("middle.mod")
                .orElseThrow());

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires middle.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example { public int value() { return upstream.Api.value(); } }
                """);
        var firstConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("upstream.mod", upstreamHash, "middle.mod", firstMiddleHash),
                Map.of(),
                (moduleName, location) -> moduleName.equals("upstream.mod") ? upstreamPath : middlePath);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, firstConfiguration, 25);

        Files.writeString(middleInfo, "module middle.mod { requires upstream.mod; }\n");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        dependencySource.toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "upstream.mod,middle.mod"));
        var nextMiddleHash = ModuleHash.moduleSha256(ModuleFinder.of(middlePath)
                .find("middle.mod")
                .orElseThrow());
        var nextConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("upstream.mod", upstreamHash, "middle.mod", nextMiddleHash),
                Map.of(),
                (moduleName, location) -> moduleName.equals("upstream.mod") ? upstreamPath : middlePath);

        assertThrows(IOException.class, () -> new ModuleCompiler(directory.resolve("clean-outputs")).compile(source, nextConfiguration, 25));
        var incremental = new ModuleCompiler(root);
        var failure = assertThrows(IOException.class, () -> incremental.compile(source, nextConfiguration, 25));
        assertTrue(failure.getMessage()
                          .startsWith("Compilation failed:"));
    }

    @Test
    void modulePathMultiReleaseViewUsesCompilationRelease(@TempDir Path directory) throws Exception {
        var dependencyJar = directory.resolve("dependency.jar");
        createMultiReleaseDependency(directory.resolve("dependency-build"), dependencyJar, "long", "42L");
        var firstHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyJar)
                .find("dependency.mod")
                .orElseThrow());

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public double value() { return dependency.Api.value(); }
                }
                """);
        var firstConfiguration17 = configuration(
                "example.mod",
                17,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", firstHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyJar);
        var firstConfiguration25 = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", firstHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyJar);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, firstConfiguration17, 25);
        new ModuleCompiler(root).compile(source, firstConfiguration25, 25);

        createMultiReleaseDependency(directory.resolve("dependency-build"), dependencyJar, "double", "42.0");
        var nextHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyJar)
                .find("dependency.mod")
                .orElseThrow());
        assertNotEquals(firstHash, nextHash);
        var nextConfiguration17 = configuration(
                "example.mod",
                17,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", nextHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyJar);
        var nextConfiguration25 = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", nextHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyJar);

        var release17 = new ModuleCompiler(root);
        release17.compile(source, nextConfiguration17, 25);
        assertEquals(1, compilations(release17));
        assertEquals(2, parsedSources(release17));

        var release25 = new ModuleCompiler(root);
        var incrementalResult = release25.compile(source, nextConfiguration25, 25);
        assertEquals(1, compilations(release25));
        assertEquals(2, parsedSources(release25));

        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        var cleanResult = clean.compile(source, nextConfiguration25, 25);
        var incrementalModule = release25.write(incrementalResult, directory.resolve("incremental-25"));
        var cleanModule = clean.write(cleanResult, directory.resolve("clean-25"));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("example/Example.class")), Files.readAllBytes(incrementalModule.resolve("example/Example.class")));
    }

    @Test
    void compilesAgainstAutomaticModuleSignatures(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency/Api.java");
        var dependencyClasses = directory.resolve("dependency-classes");
        Files.createDirectories(dependencySource.getParent());
        Files.writeString(dependencySource,
                """
                package dependency;
                public class Api { public static int value() { return 42; } }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "-d",
                        dependencyClasses.toString(), dependencySource.toString()));
        var dependencyJar = directory.resolve("dependency.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "dependency.auto");
        try (var output = new JarOutputStream(Files.newOutputStream(dependencyJar), manifest)) {
            writeJarEntry(output, "dependency/Api.class", Files.readAllBytes(dependencyClasses.resolve("dependency/Api.class")));
        }
        var dependencyHash = ModuleHash.moduleSha256(ModuleFinder.of(dependencyJar)
                .find("dependency.auto")
                .orElseThrow());

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.auto; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public int value() { return dependency.Api.value(); }
                }
                """);
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.auto", dependencyHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyJar);

        var compiler = new ModuleCompiler(directory.resolve("outputs"));
        compiler.compile(source, configuration, 25);

        assertEquals(1, compilations(compiler));
        assertEquals(2, parsedSources(compiler));
    }

    @Test
    void compilationHitsDoNotResolveDependencyPaths(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module example.mod {}\n");
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource);
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod {}\n");
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "-d",
                        dependencyModules.toString(),
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");
        var dependency = ModuleHash.moduleSha256(ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow());
        var resolutions = new AtomicInteger();
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependency),
                Map.of(),
                (moduleName, upgrade) -> {
                    resolutions.incrementAndGet();
                    return dependencyPath;
                });
        var root = directory.resolve("outputs");
        var compiler = new ModuleCompiler(root);
        var first = compiler.compile(source, configuration, 25);
        assertEquals(1, resolutions.get());
        assertEquals(1, compilations(compiler));
        assertEquals(1, parsedSources(compiler));

        var reusedConfiguration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependency),
                Map.of(),
                (moduleName, upgrade) -> {
                    throw new AssertionError("Dependency path must not be resolved");
                });
        var reused = new ModuleCompiler(root);
        assertEquals(first, reused.compile(source, reusedConfiguration, 25));
        assertEquals(0, compilations(reused));
        assertEquals(0, parsedSources(reused));

        Files.writeString(source.resolve("module-info.java"),
                """
                // Different source content with the same parsed structure.
                module example.mod {}
                """);
        var changed = new ModuleCompiler(root);
        assertNotEquals(first, changed.compile(source, configuration, 25));
        assertEquals(2, resolutions.get());
        assertEquals(1, compilations(changed));
        assertEquals(1, parsedSources(changed));
    }

    @Test
    void ordinarySystemTypesUseJavacPlatformSymbols(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod {}\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public java.util.List<String> values() { return java.util.List.of(); }
                }
                """);
        var configuration = new Configuration(
                "example.mod",
                OptionalInt.empty(),
                false,
                Optional.empty(),
                ModuleRuntimeAccessOptions.EMPTY,
                List.of(),
                entry -> {
                    throw new AssertionError("System modules have no module-path entry");
                });

        var compiler = new ModuleCompiler(directory.resolve("outputs"));
        compiler.compile(source, configuration, 25);

        assertEquals(1, compilations(compiler));
    }

    @Test
    void differentRawSourceContentProducesCurrentClassMetadata(@TempDir Path directory) throws Exception {
        var firstSource = directory.resolve("first-source");
        Files.createDirectories(firstSource.resolve("p"));
        Files.writeString(firstSource.resolve("module-info.java"), "module example.mod { exports p; }\n");
        Files.writeString(firstSource.resolve("p/Example.java"),
                """
                package p;
                public class Example { public int answer() { return 42; } }
                """);
        Files.writeString(firstSource.resolve("application.properties"), "value=one\n");

        var root = directory.resolve("outputs");
        var firstCompiler = new ModuleCompiler(root);
        var first = firstCompiler.compile(firstSource, configuration("example.mod", 25), 25);
        assertEquals(1, compilations(firstCompiler));
        assertEquals(2, parsedSources(firstCompiler));

        var secondSource = directory.resolve("second-source");
        Files.createDirectories(secondSource.resolve("p"));
        Files.writeString(secondSource.resolve("module-info.java"), "module example.mod { exports p; }\n");
        Files.writeString(secondSource.resolve("p/Example.java"),
                """
                package p;

                // This checkout has different bytes but the same structure.
                public class Example {
                    public int answer() {
                        return 42;
                    }
                }
                """);
        Files.writeString(secondSource.resolve("application.properties"), "value=one\n");

        var secondCompiler = new ModuleCompiler(root);
        var second = secondCompiler.compile(secondSource, configuration("example.mod", 25), 25);
        assertNotEquals(first, second);
        assertEquals(1, compilations(secondCompiler));
        assertEquals(1, parsedSources(secondCompiler));
    }

    @Test
    void identicalSourceInAnotherCheckoutReusesItsContentAddressedResult(@TempDir Path directory) throws Exception {
        var firstSource = Files.createDirectories(directory.resolve("first-source"));
        Files.writeString(firstSource.resolve("module-info.java"), "module example.mod {}\n");
        Files.createDirectories(firstSource.resolve("p"));
        Files.writeString(firstSource.resolve("p/Example.java"), "package p; class Example {}\n");
        var outputs = directory.resolve("outputs");
        var firstCompiler = new ModuleCompiler(outputs);
        var first = firstCompiler.compile(firstSource, configuration("example.mod", 25), 25);
        assertEquals(1, compilations(firstCompiler));

        var secondSource = Files.createDirectories(directory.resolve("second-source"));
        Files.writeString(secondSource.resolve("module-info.java"), "module example.mod {}\n");
        Files.createDirectories(secondSource.resolve("p"));
        Files.writeString(secondSource.resolve("p/Example.java"), "package p; class Example {}\n");
        var secondCompiler = new ModuleCompiler(outputs);
        var second = secondCompiler.compile(secondSource, configuration("example.mod", 25), 25);

        assertEquals(first, second);
        assertEquals(0, compilations(secondCompiler));
        assertEquals(0, parsedSources(secondCompiler));
    }

    @Test
    void reusesMatchingOutputAfterAnotherSourceVersionWasUsed(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("source"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod {}\n");
        var example = Files.createDirectories(source.resolve("p")).resolve("Example.java");
        var outputs = directory.resolve("outputs");

        Files.writeString(example, "package p; class Example { int value() { return 1; } }\n");
        var firstCompiler = new ModuleCompiler(outputs);
        var first = firstCompiler.compile(source, configuration("example.mod", 25), 25);

        Files.writeString(example, "package p; class Example { int value() { return 2; } }\n");
        var secondCompiler = new ModuleCompiler(outputs);
        var second = secondCompiler.compile(source, configuration("example.mod", 25), 25);
        assertNotEquals(first, second);

        Files.writeString(example, "package p; class Example { int value() { return 1; } }\n");
        var reusedCompiler = new ModuleCompiler(outputs);
        var reused = reusedCompiler.compile(source, configuration("example.mod", 25), 25);

        assertEquals(first, reused);
        assertEquals(0, compilations(reusedCompiler));
        assertEquals(0, parsedSources(reusedCompiler));
    }

    @Test
    void namedModuleSubsetCompilesAgainstCachedOwnModuleClasses(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { exports p; }\n");
        var api = source.resolve("p/Api.java");
        Files.writeString(api,
                """
                package p;
                public class Api { public static Number value() { return 1; } }
                """);
        Files.writeString(source.resolve("p/Use.java"),
                """
                package p;
                public class Use { public Number value() { return Api.value(); } }
                """);
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of(),
                Map.of(),
                (moduleName, upgrade) -> {
                    throw new AssertionError("Compilation has no dependencies");
                });
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, configuration, 25);

        Files.writeString(api,
                """
                package p;
                public class Api { public static Number value() { return 2; } }
                """);
        var next = new ModuleCompiler(root);
        var bodyChange = next.compile(source, configuration, 25);

        assertEquals(1, compilations(next));
        assertEquals(1, parsedSources(next));

        var beforeClassChange = next.write(bodyChange, directory.resolve("before-abi-change"));
        Files.writeString(api,
                """
                package p;
                public class Api {
                    public static Integer value() { return 2; }
                }
                """);
        var classChange = new ModuleCompiler(root);
        var changed = classChange.compile(source, configuration, 25);
        var afterClassChange = classChange.write(changed, directory.resolve("after-abi-change"));

        assertEquals(2, compilations(classChange));
        assertEquals(3, parsedSources(classChange));
        assertFalse(Arrays.equals(Files.readAllBytes(beforeClassChange.resolve("p/Use.class")), Files.readAllBytes(afterClassChange.resolve("p/Use.class"))));
    }

    @Test
    void moduleDescriptorCommentCompilesOnlyModuleInfo(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        var moduleInfo = source.resolve("module-info.java");
        Files.writeString(moduleInfo, "module example.mod { exports p; }\n");
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example { public int answer() { return 42; } }
                """);
        var configuration = configuration("example.mod", 25);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, configuration, 25);

        Files.writeString(moduleInfo,
                """
                // The module declaration is unchanged.
                module example.mod { exports p; }
                """);
        var compiler = new ModuleCompiler(root);
        compiler.compile(source, configuration, 25);

        assertEquals(1, compilations(compiler));
        assertEquals(1, parsedSources(compiler));
    }

    @Test
    void exportChangeRecompilesTheCurrentModule(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        var moduleInfo = source.resolve("module-info.java");
        Files.writeString(moduleInfo, "module example.mod { exports p; }\n");
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example { public int answer() { return 42; } }
                """);
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of(),
                Map.of(),
                (moduleName, upgrade) -> {
                    throw new AssertionError("Compilation has no dependencies");
                });
        var root = directory.resolve("outputs");
        var firstCompiler = new ModuleCompiler(root);
        var first = firstCompiler.compile(source, configuration, 25);
        var firstModule = firstCompiler.write(first, directory.resolve("first"));

        Files.writeString(moduleInfo, "module example.mod {}\n");
        var cleanAssembler = new ModuleCompiler(directory.resolve("clean-outputs"));
        var clean = cleanAssembler.compile(source, configuration, 25);
        var cleanModule = cleanAssembler.write(clean, directory.resolve("clean"));
        assertArrayEquals(Files.readAllBytes(firstModule.resolve("p/Example.class")), Files.readAllBytes(cleanModule.resolve("p/Example.class")));
        assertFalse(Arrays.equals(Files.readAllBytes(firstModule.resolve("module-info.class")), Files.readAllBytes(cleanModule.resolve("module-info.class"))));

        var incrementalAssembler = new ModuleCompiler(root);
        var incremental = incrementalAssembler.compile(source, configuration, 25);
        var incrementalModule = incrementalAssembler.write(incremental, directory.resolve("incremental"));

        assertEquals(2, compilations(incrementalAssembler));
        assertEquals(2, parsedSources(incrementalAssembler));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("module-info.class")), Files.readAllBytes(incrementalModule.resolve("module-info.class")));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("p/Example.class")), Files.readAllBytes(incrementalModule.resolve("p/Example.class")));
    }

    @Test
    void serviceProviderChangeRecompilesTheCurrentModule(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        var moduleInfo = source.resolve("module-info.java");
        Files.writeString(moduleInfo,
                """
                module example.mod { provides p.Service with p.First; }
                """);
        Files.writeString(source.resolve("p/Service.java"),
                """
                package p;
                public interface Service {}
                """);
        Files.writeString(source.resolve("p/First.java"),
                """
                package p;
                public final class First implements Service {
                    public First() {}
                }
                """);
        Files.writeString(source.resolve("p/Second.java"),
                """
                package p;
                public final class Second implements Service {
                    public Second() {}
                }
                """);
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of(),
                Map.of(),
                (moduleName, upgrade) -> {
                    throw new AssertionError("Compilation has no dependencies");
                });
        var root = directory.resolve("outputs");
        var firstCompiler = new ModuleCompiler(root);
        var first = firstCompiler.compile(source, configuration, 25);
        var firstModule = firstCompiler.write(first, directory.resolve("first-provider"));

        Files.writeString(moduleInfo,
                """
                module example.mod { provides p.Service with p.Second; }
                """);
        var cleanAssembler = new ModuleCompiler(directory.resolve("provider-clean-outputs"));
        var clean = cleanAssembler.compile(source, configuration, 25);
        var cleanModule = cleanAssembler.write(clean, directory.resolve("provider-clean"));
        assertFalse(Arrays.equals(Files.readAllBytes(firstModule.resolve("module-info.class")), Files.readAllBytes(cleanModule.resolve("module-info.class"))));
        for (var classFile : List.of("p/Service.class", "p/First.class", "p/Second.class")) {
            assertArrayEquals(Files.readAllBytes(firstModule.resolve(classFile)), Files.readAllBytes(cleanModule.resolve(classFile)));
        }

        var incrementalAssembler = new ModuleCompiler(root);
        var incremental = incrementalAssembler.compile(source, configuration, 25);
        var incrementalModule = incrementalAssembler.write(incremental, directory.resolve("provider-incremental"));

        assertEquals(2, compilations(incrementalAssembler));
        assertEquals(4, parsedSources(incrementalAssembler));
        assertArrayEquals(Files.readAllBytes(cleanModule.resolve("module-info.class")), Files.readAllBytes(incrementalModule.resolve("module-info.class")));
        for (var classFile : List.of("p/Service.class", "p/First.class", "p/Second.class")) {
            assertArrayEquals(Files.readAllBytes(cleanModule.resolve(classFile)), Files.readAllBytes(incrementalModule.resolve(classFile)));
        }
    }

    @Test
    void removingRequiredModuleRecompilesSourceThatReadsIt(@TempDir Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api { public static int value() { return 42; } }
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependencyPath = dependencyModules.resolve("dependency.mod");
        var dependencyReference = ModuleFinder.of(dependencyPath)
                .find("dependency.mod")
                .orElseThrow();

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        var moduleInfo = source.resolve("module-info.java");
        Files.writeString(moduleInfo, "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example {
                    public int answer() { return dependency.Api.value(); }
                }
                """);
        var dependencyHash = ModuleHash.moduleSha256(dependencyReference);
        var configuration = configuration(
                "example.mod",
                25,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of("dependency.mod", dependencyHash),
                Map.of(),
                (moduleName, upgrade) -> dependencyPath);
        var root = directory.resolve("outputs");
        new ModuleCompiler(root).compile(source, configuration, 25);

        Files.writeString(moduleInfo, "module example.mod {}\n");
        var clean = new ModuleCompiler(directory.resolve("clean-outputs"));
        assertThrows(IOException.class, () -> clean.compile(source, configuration, 25));

        var incremental = new ModuleCompiler(root);
        var failure = assertThrows(IOException.class, () -> incremental.compile(source, configuration, 25));

        assertTrue(failure.getMessage()
                          .startsWith("Compilation failed:"));
    }

    @Test
    void deletingSecondaryTopLevelTypeRemovesStaleOwnedOutput(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod {}\n");
        var owner = source.resolve("p/Owner.java");
        Files.writeString(owner,
                """
                package p;
                public class Owner {}
                class Secondary {}
                """);
        var root = directory.resolve("outputs");
        var firstCompiler = new ModuleCompiler(root);
        var first = firstCompiler.compile(source, configuration("example.mod", 25), 25);
        var firstModule = firstCompiler.write(first, directory.resolve("first"));
        assertTrue(Files.exists(firstModule.resolve("p/Owner.class")));
        assertTrue(Files.exists(firstModule.resolve("p/Secondary.class")));

        Files.writeString(owner,
                """
                package p;
                public class Owner {}
                """);
        var nextCompiler = new ModuleCompiler(root);
        var next = nextCompiler.compile(source, configuration("example.mod", 25), 25);
        var nextModule = nextCompiler.write(next, directory.resolve("next"));

        assertEquals(2, compilations(nextCompiler));
        assertEquals(2, parsedSources(nextCompiler));
        assertTrue(Files.exists(nextModule.resolve("p/Owner.class")));
        assertFalse(Files.exists(nextModule.resolve("p/Secondary.class")));
    }

    @Test
    void resourceChangesReassembleWithoutCompilation(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod {}\n");
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example {}
                """);
        var resource = source.resolve("application.properties");
        Files.writeString(resource, "value=one\n");

        var root = directory.resolve("outputs");
        var compiler = new ModuleCompiler(root);
        var first = compiler.compile(source, configuration("example.mod", 25), 25);
        assertEquals(1, compilations(compiler));
        var firstModule = compiler.write(first, directory.resolve("first"));
        var firstClass = Files.readAllBytes(firstModule.resolve("p/Example.class"));
        assertEquals("value=one\n", Files.readString(firstModule.resolve("application.properties")));

        Files.writeString(resource, "value=two\n");
        var next = new ModuleCompiler(root);
        var second = next.compile(source, configuration("example.mod", 25), 25);
        assertEquals(0, compilations(next));
        var secondModule = next.write(second, directory.resolve("second"));
        assertNotEquals(first, second);
        assertArrayEquals(firstClass, Files.readAllBytes(secondModule.resolve("p/Example.class")));
        assertEquals("value=two\n", Files.readString(secondModule.resolve("application.properties")));
    }

    private static PatchFixture patchFixture(Path directory) throws Exception {
        var dependencySource = directory.resolve("dependency-source/dependency.mod");
        Files.createDirectories(dependencySource.resolve("dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(dependencySource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api { public static int value() { return 1; } }
                """);
        var dependencyModules = directory.resolve("dependency-modules");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "25",
                        "--module-source-path",
                        directory.resolve("dependency-source").toString(),
                        "-d",
                        dependencyModules.toString(),
                        "--module",
                        "dependency.mod"));
        var dependency = dependencyModules.resolve("dependency.mod");
        var dependencyHash = ModuleHash.moduleSha256(ModuleFinder.of(dependency)
                .find("dependency.mod")
                .orElseThrow());

        var patchSource = directory.resolve("patch-source/dependency/Api.java");
        Files.createDirectories(patchSource.getParent());
        Files.writeString(patchSource,
                """
                package dependency;
                public class Api { public static long value() { return 2L; } }
                """);
        var patch = directory.resolve("patch-classes");
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "-d",
                        patch.toString(), patchSource.toString()));
        var patchHash = ModuleHash.patchSha256(patch);

        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { requires dependency.mod; }\n");
        Files.writeString(source.resolve("example/Example.java"),
                """
                package example;
                public class Example {
                    public long value() { return dependency.Api.value(); }
                }
                """);
        return new PatchFixture(source, dependency, patch, dependencyHash, patchHash);
    }

    private static void createMultiReleaseDependency(Path build, Path jar, String versionType,
            String versionValue)
            throws Exception {
        if (Files.exists(build)) {
            try (var paths = Files.walk(build)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        var baseSource = build.resolve("base-source");
        var baseClasses = build.resolve("base-classes");
        Files.createDirectories(baseSource.resolve("dependency"));
        Files.writeString(baseSource.resolve("module-info.java"), "module dependency.mod { exports dependency; }\n");
        Files.writeString(baseSource.resolve("dependency/Api.java"),
                """
                package dependency;
                public class Api { public static int value() { return 42; } }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(
                        null,
                        null,
                        null,
                        "--release",
                        "17",
                        "-d",
                        baseClasses.toString(),
                        baseSource.resolve("module-info.java").toString(),
                        baseSource.resolve("dependency/Api.java").toString()));

        var versionSource = build.resolve("version-source/dependency/Api.java");
        var versionClasses = build.resolve("version-classes");
        Files.createDirectories(versionSource.getParent());
        Files.writeString(versionSource,
                """
                package dependency;
                public class Api {
                    public static %s value() { return %s; }
                }
                """
                        .formatted(versionType, versionValue));
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "-d",
                        versionClasses.toString(), versionSource.toString()));

        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            try (var paths = Files.walk(baseClasses)) {
                for (var path : paths.filter(Files::isRegularFile)
                                     .sorted()
                                     .toList()) {
                    writeJarEntry(
                            output,
                            baseClasses.relativize(path)
                                       .toString()
                                       .replace('\\', '/'),
                            Files.readAllBytes(path));
                }
            }
            writeJarEntry(output, "META-INF/versions/25/dependency/Api.class", Files.readAllBytes(versionClasses.resolve("dependency/Api.class")));
        }
    }

    private static void writeJarEntry(JarOutputStream output, String name, byte[] bytes) throws Exception {
        var entry = new JarEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private static Configuration configuration(String moduleName, int release) {
        return configuration(
                moduleName,
                release,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                Map.of(),
                Map.of(),
                (name, location) -> {
                    throw new AssertionError("Compilation has no dependencies");
                });
    }

    private static Configuration configuration(
            String moduleName,
            Integer release,
            boolean preview,
            String moduleVersion,
            ModuleRuntimeAccessOptions runtimeAccess,
            Map<String, ModuleHash> modulePath,
            Map<String, ModuleHash> upgradeModulePath,
            ModulePathResolver paths) {
        return configuration(moduleName, release, preview, moduleVersion, runtimeAccess, modulePath,
                upgradeModulePath, Map.of(), paths);
    }

    private static Configuration configuration(
            String moduleName,
            Integer release,
            boolean preview,
            String moduleVersion,
            ModuleRuntimeAccessOptions runtimeAccess,
            Map<String, ModuleHash> modulePath,
            Map<String, ModuleHash> upgradeModulePath,
            Map<String, ModuleHash> patchModulePath,
            ModulePathResolver paths) {
        var entries = new ArrayList<ModulePathEntry>();
        modulePath.forEach((name, hash) -> entries.add(new ModulePathEntry(name, StandardLocation.MODULE_PATH, hash)));
        upgradeModulePath.forEach((name, hash) -> entries.add(new ModulePathEntry(name, StandardLocation.UPGRADE_MODULE_PATH, hash)));
        patchModulePath.forEach((name, hash) -> entries.add(new ModulePathEntry(name, StandardLocation.PATCH_MODULE_PATH, hash)));
        return new Configuration(
                moduleName,
                release == null ? OptionalInt.empty() : OptionalInt.of(release),
                preview,
                Optional.ofNullable(moduleVersion).map(Version::parse),
                runtimeAccess,
                entries,
                entry -> paths.resolve(entry.moduleName(), entry.location()));
    }

    @Test
    void materializesIndependentMultiReleaseCompilationContent(@TempDir Path directory) throws Exception {
        var source = directory.resolve("source");
        Files.createDirectories(source.resolve("p"));
        Files.writeString(source.resolve("module-info.java"), "module example.mod { exports p; }\n");
        Files.writeString(source.resolve("p/Example.java"),
                """
                package p;
                public class Example { public int version() { return 11; } }
                """);
        Files.createDirectories(source.resolve("META-INF/versions/17/p"));
        Files.writeString(source.resolve("META-INF/versions/17/p/Example.java"),
                """
                package p;
                public class Example { public int version() { return 17; } }
                """);
        Files.writeString(source.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n");
        Files.writeString(source.resolve("version.txt"), "base\n");
        Files.writeString(source.resolve("META-INF/versions/17/version.txt"), "17\n");

        var root = directory.resolve("outputs");
        var compiler = new ModuleCompiler(root);
        var release16 = compiler.compile(source, configuration("example.mod", 11), 16);
        assertEquals(1, compilations(compiler));
        var module16 = compiler.write(release16, directory.resolve("release-16"));
        var baseClass = Files.readAllBytes(module16.resolve("p/Example.class"));
        assertEquals("base\n", Files.readString(module16.resolve("version.txt")));

        var next = new ModuleCompiler(root);
        var release17 = next.compile(source, configuration("example.mod", 11), 17);
        assertEquals(2, compilations(next));
        var module17 = next.write(release17, directory.resolve("release-17"));
        assertNotEquals(release16, release17);
        assertNotEquals(HexFormat.of().formatHex(baseClass),
                HexFormat.of().formatHex(Files.readAllBytes(module17.resolve("p/Example.class"))));
        assertEquals("17\n", Files.readString(module17.resolve("version.txt")));
    }
}
