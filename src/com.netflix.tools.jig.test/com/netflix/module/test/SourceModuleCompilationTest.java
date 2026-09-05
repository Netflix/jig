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

import java.lang.classfile.ClassFile;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleRuntimeAccessAttribute;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.compile.ModulePathEntry;
import com.netflix.module.compile.internal.CompilationDiagnostic;
import com.netflix.module.compile.internal.ContentHash;
import com.netflix.module.compile.internal.SourceModuleCompilation;
import com.netflix.module.compile.internal.SourceModuleCompilation.Configuration;
import com.netflix.module.compile.internal.SourceModuleCompilation.Paths;
import com.netflix.module.compile.internal.SourceModuleCompilation.Result;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;
import com.netflix.module.compile.internal.SourceModuleCompilation.SystemImage;
import com.netflix.module.compile.internal.SourceModuleOutput;
import com.netflix.module.compile.internal.SourcePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceModuleCompilationTest {
    private static final Map<Path, State> STATES = new ConcurrentHashMap<>();

    @Test
    void compilationRecordsSourceBytesReadByJavac(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var observed = SourceModuleOutput.readSources(sources);
        var changed =
                """
                package p;
                public class Api {
                    public static int value() { return 2; }
                }
                """;
        Files.writeString(sources.resolve("p/Api.java"), changed);

        var result = SourceModuleCompilation.compile(
                sources,
                null,
                null,
                null,
                configuration(List.of(), List.of()),
                observed,
                State.empty());

        assertEquals(ContentHash.sha256(changed.getBytes(StandardCharsets.UTF_8)),
                result.state()
                      .sources()
                      .get(new SourcePath("p/Api.java"))
                      .sourceHash());
    }

    @Test
    void resultClassBytesAreDefensive(@TempDir Path directory) throws Exception {
        var result = compile(sources(directory), null);
        var first = result.classes().get("p/Api.class");
        var expected = first.clone();

        first[0] = (byte) ~first[0];

        assertArrayEquals(expected, result.classes()
                .get("p/Api.class"));
    }

    @Test
    void coldCompilationRecordsSourceDeclarationsAndGeneratedClasses(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var result = compile(sources, null);
        var api = result.state()
                        .sources()
                        .get(new SourcePath("p/Api.java"));

        assertEquals(Set.of(new SourcePath("module-info.java"), new SourcePath("p/Api.java"), new SourcePath("p/Use.java")),
                result.compiledSources());
        assertEquals(Set.of("p.Api"), api.generatedClasses());
        assertNotEquals(api.sourceHash(), api.declarationHash());
    }

    @Test
    void systemImageCompilationRecordsOnlyReachedClasses(@TempDir Path directory) throws Exception {
        var result = compile(sources(directory), null);

        assertTrue(result.state().systemImage() != null);
        var systemClasses = result.state().sources().values().stream()
                .flatMap(compilation -> compilation.systemClasses().keySet().stream())
                .collect(Collectors.toSet());
        assertTrue(systemClasses.stream()
                .anyMatch(uri -> uri.equals("jrt:/java.base/java/lang/Object.class")));
        assertFalse(systemClasses.stream()
                .anyMatch(uri -> uri.equals("jrt:/java.base/java/util/Random.class")));
    }

    @Test
    void changedSystemImageRechecksOnlyPreviouslyReachedClasses(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var first = compile(sources, null);
        var output = write(directory.resolve("system-image-output"), first);
        var previous = first.state();
        var image = previous.systemImage();
        var staleImage = new SystemImage(image.path(), image.size() + 1, image.modifiedSeconds(),
                image.modifiedNanos(), image.fileKey());
        var staleState = new State(previous.sources(), previous.modulePathEntries(), previous.resources(),
                previous.moduleInfoOptionsHash(), staleImage);

        var unchanged = SourceModuleCompilation.compile(
                sources,
                output,
                null,
                output,
                configuration(List.of(), List.of()),
                SourceModuleOutput.readSources(sources),
                staleState);

        assertEquals(Set.of(), unchanged.compiledSources());
        assertEquals(image, unchanged.state()
                .systemImage());
    }

    @Test
    void releaseCompilationUsesCtSymWithoutSystemImageObservations(@TempDir Path directory) throws Exception {
        var configuration = new Configuration(
                "example.module",
                Runtime.version().feature(),
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                List.of(),
                List.of(),
                Map.of(),
                List.of());

        var result = SourceModuleCompilation.compile(sources(directory), null, configuration);

        assertTrue(result.state().sources().values().stream()
                .allMatch(compilation -> compilation.systemClasses().isEmpty()));
        assertNull(result.state()
                         .systemImage());
    }

    @Test
    void compilationUsesReleaseModuleVersionAndRuntimeAccess(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var runtimeAccess = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("example.module")
                .addOpens("java.base", "java.lang", "example.module")
                .build();
        var configuration = new Configuration(
                "example.module",
                17,
                false,
                "1.2",
                runtimeAccess,
                List.of(),
                List.of(),
                Map.of(),
                List.of());

        var result = SourceModuleCompilation.compile(sources, null, configuration);
        var api = ClassFile.of().parse(result.classes()
                .get("p/Api.class"));
        var moduleInfo = ClassFile.of(ModuleRuntimeAccessAttribute.mapperOption()).parse(result.classes()
                .get("module-info.class"));
        var output = write(directory.resolve("configured-output"), result);
        var descriptor = ModuleFinder.of(output)
                .find("example.module")
                .orElseThrow()
                .descriptor();

        assertEquals(61, api.majorVersion());
        assertEquals("1.2", descriptor.rawVersion()
                .orElseThrow());
        assertEquals(runtimeAccess,
                moduleInfo.findAttribute(ModuleRuntimeAccessAttribute.mapper())
                          .orElseThrow()
                          .options());
    }

    @Test
    void outputOnlyModuleOptionsCompileOnlyTheDescriptor(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var paths = new Paths(List.of(), List.of(), Map.of());
        var initial = new Configuration("example.module", 25, false, "1.0", null, ModuleRuntimeAccessOptions.EMPTY,
                paths, List.of(), null);
        var output = write(directory.resolve("initial-output"), SourceModuleCompilation.compile(sources, null, initial));
        var runtimeAccess = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("example.module")
                .build();
        var changed = new Configuration("example.module", 25, false, "2.0", "p.Api", runtimeAccess,
                paths, List.of(), null);

        var result = compile(sources, output, changed);

        assertEquals(Set.of(new SourcePath("module-info.java")), result.compiledSources());
        assertEquals(Set.of("module-info.class"),
                result.classes().keySet());
        var updated = apply(output, directory.resolve("updated-output"), result);
        var descriptor = ModuleFinder.of(updated)
                .find("example.module")
                .orElseThrow()
                .descriptor();
        assertEquals("2.0", descriptor.rawVersion()
                .orElseThrow());
        assertEquals("p.Api", descriptor.mainClass()
                .orElseThrow());
        var moduleInfo = ClassFile.of(ModuleRuntimeAccessAttribute.mapperOption()).parse(Files.readAllBytes(updated.resolve("module-info.class")));
        assertEquals(runtimeAccess,
                moduleInfo.findAttribute(ModuleRuntimeAccessAttribute.mapper())
                          .orElseThrow()
                          .options());
    }

    @Test
    void timestampChangesDoNotCompileSources(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var output = write(directory.resolve("output"), compile(sources, null));
        var api = sources.resolve("p/Api.java");
        Files.setLastModifiedTime(api, FileTime.fromMillis(Files.getLastModifiedTime(api).toMillis() + 10_000));

        var result = compile(sources, output);

        assertEquals(Set.of(), result.compiledSources());
        assertEquals(0, result.rounds());
        assertEquals(0, result.classes()
                              .size());
    }

    @Test
    void writesANewImmutableOutputByCopyingUnchangedClasses(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var first = SourceModuleCompilation.write(null, compile(sources, null), directory.resolve("first-output"));
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    public static int value() { return 2; }
                }
                """);
        var update = compile(sources, first);

        var second = SourceModuleCompilation.write(first, update, directory.resolve("second-output"));

        assertFalse(Files.isSameFile(first.resolve("module-info.class"), second.resolve("module-info.class")));
        assertArrayEquals(Files.readAllBytes(first.resolve("module-info.class")), Files.readAllBytes(second.resolve("module-info.class")));
        assertFalse(Files.isSameFile(first.resolve("p/Api.class"), second.resolve("p/Api.class")));
        assertFalse(Files.isWritable(second.resolve("p/Api.class")));
    }

    @Test
    void enablesAllLintWarningsByDefault(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    @Deprecated
                    public static int value() { return 1; }
                }
                """);
        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();

        SourceModuleCompilation.compile(
                sources,
                null,
                null,
                null,
                configuration(List.of(), List.of()),
                SourceModuleOutput.readSources(sources),
                State.empty(),
                diagnostics::add);

        assertTrue(
                diagnostics.stream().anyMatch(diagnostic ->
                        (diagnostic.getKind() == Kind.WARNING || diagnostic.getKind() == Kind.MANDATORY_WARNING)
                                && diagnostic.getCode().contains("deprecated")
                                && diagnostic.getSource() != null
                                && diagnostic.getSource()
                                             .toUri()
                                             .equals(sources.resolve("p/Use.java").toUri())),
                diagnostics.toString());
    }

    @Test
    void replayIncludesClassDiagnosticsBeforeLaterSourceCompilation(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    @Deprecated
                    public static int value() { return 1; }
                }
                """);
        var initial = compile(sources, null);
        assertTrue(initial.state().diagnostics().stream()
                .map(CompilationDiagnostic::diagnostic)
                .anyMatch(diagnostic -> diagnostic.message().contains("deprecated")));
        var output = write(directory.resolve("replay-output"), initial);
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    public static int value() { return 1; }
                }
                """);
        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();

        var result = SourceModuleCompilation.compile(
                sources,
                output,
                null,
                output,
                configuration(List.of(), List.of()),
                SourceModuleOutput.readSources(sources),
                STATES.get(output.toAbsolutePath().normalize()),
                diagnostics::add);

        assertEquals(2, result.rounds());
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.getMessage(null).contains("deprecated")),
                diagnostics.toString());
    }

    @Test
    void nullSourceDiagnosticsAreRetainedInEncounterOrder(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var runtimeAccess = ModuleRuntimeAccessOptions.newBuilder()
                .addExports("missing.module", "p", "example.module")
                .build();
        var configuration = new Configuration(
                "example.module",
                null,
                false,
                null,
                runtimeAccess,
                List.of(),
                List.of(),
                Map.of(),
                List.of());
        var initial = compile(sources, null, configuration);
        assertTrue(
                initial.state().diagnostics().stream()
                        .map(CompilationDiagnostic::diagnostic)
                        .anyMatch(diagnostic -> diagnostic.source() == null));
        var firstOutput = write(directory.resolve("null-diagnostic-output"), initial);
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    public static int value() { return 2; }
                }
                """);
        var firstDiagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var first = SourceModuleCompilation.compile(
                sources,
                firstOutput,
                null,
                firstOutput,
                configuration,
                SourceModuleOutput.readSources(sources),
                STATES.get(firstOutput.toAbsolutePath().normalize()),
                firstDiagnostics::add);
        var expectedCodes = firstDiagnostics.stream()
                .filter(diagnostic -> diagnostic.getSource() == null)
                .map(Diagnostic::getCode)
                .toList();
        assertFalse(expectedCodes.isEmpty());
        var secondOutput = apply(firstOutput, directory.resolve("second-null-output"), first);
        Files.writeString(sources.resolve("p/Use.java"),
                """
                package p;
                public class Use {
                    public int value() { return Api.value() + 1; }
                }
                """);
        var secondDiagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();

        SourceModuleCompilation.compile(
                sources,
                secondOutput,
                null,
                secondOutput,
                configuration,
                SourceModuleOutput.readSources(sources),
                STATES.get(secondOutput.toAbsolutePath().normalize()),
                secondDiagnostics::add);

        assertEquals(expectedCodes,
                secondDiagnostics.stream()
                        .filter(diagnostic -> diagnostic.getSource() == null)
                        .map(Diagnostic::getCode)
                        .toList());
    }

    @Test
    void incrementalDiagnosticsUseOriginalSourcePaths(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var output = write(directory.resolve("diagnostic-output"), compile(sources, null));
        Files.writeString(sources.resolve("module-info.java"),
                """
                module example.module {
                    exports p to missing.module;
                }
                """);
        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();

        SourceModuleCompilation.compile(
                sources,
                output,
                null,
                output,
                configuration(List.of(), List.of()),
                SourceModuleOutput.readSources(sources),
                STATES.get(output.toAbsolutePath().normalize()),
                diagnostics::add);

        var source = diagnostics.stream()
                .filter(diagnostic -> diagnostic.getCode().equals("compiler.warn.module.not.found"))
                .map(Diagnostic::getSource)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
        assertEquals(sources.resolve("module-info.java").toUri(),
                URI.create(source.toUri().toString()));
    }

    @Test
    void qualifiedExportTargetIsVisibleFromTheModuleSourcePath(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        Files.writeString(sources.resolve("module-info.java"),
                """
                module example.module {
                    exports p to sibling.module;
                }
                """);
        var sibling = Files.createDirectories(directory.resolve("sibling-sources"));
        Files.writeString(sibling.resolve("module-info.java"), "module sibling.module {}\n");
        var paths = new Paths(List.of(), List.of(), Map.of(),
                Map.of("sibling.module", List.of(sibling)));
        var configuration = new Configuration(
                "example.module",
                Runtime.version().feature(),
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                paths,
                List.of(),
                null);
        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();

        var result = SourceModuleCompilation.compile(sources, null, null, null, configuration,
                SourceModuleOutput.readSources(sources), State.empty(), diagnostics::add);

        assertTrue(diagnostics.stream().noneMatch(diagnostic -> diagnostic.getCode().equals("compiler.warn.module.not.found")),
                diagnostics.toString());
        assertTrue(
                result.classes().containsKey("module-info.class"),
                result.classes()
                      .keySet()
                      .toString());
    }

    @Test
    void incrementalClassesReplaceUnselectedSources(@TempDir Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("selected-sources/p")).getParent();
        Files.writeString(sources.resolve("module-info.java"), "module example.module { exports p; }\n");
        Files.writeString(sources.resolve("p/Helper.java"),
                """
                package p;
                public class Helper { public static int value() { return 1; } }
                """);
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api { public static int value() { return Helper.value(); } }
                """);
        var output = write(directory.resolve("selected-output"), compile(sources, null));
        var changed =
                """
                package p;
                public class Api { public static int value() { return Helper.value() + 1; } }
                """;
        Files.writeString(sources.resolve("p/Api.java"), changed);
        var contents = new TreeMap<>(SourceModuleOutput.readSources(sources));

        // Reconciliation has already selected only Api.java. Even though javac
        // can discover the source root, the retained Helper.class must replace
        // the unselected Helper.java.
        Files.writeString(sources.resolve("p/Helper.java"), "package p; this is not Java\n");

        var result = SourceModuleCompilation.compile(
                sources,
                output,
                null,
                output,
                configuration(List.of(), List.of()),
                contents,
                STATES.get(output.toAbsolutePath().normalize()));

        assertEquals(Set.of(new SourcePath("p/Api.java")), result.compiledSources());
        assertEquals(1, result.rounds());
    }

    @Test
    void implementationChangeCompilesOnlyItsSource(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var output = write(directory.resolve("output"), compile(sources, null));
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    public static int value() { return 2; }
                }
                """);

        var result = compile(sources, output);

        assertEquals(Set.of(new SourcePath("p/Api.java")), result.compiledSources());
        assertEquals(1, result.rounds());
    }

    @Test
    void implementationChangeDoesNotCompileUnchangedSources(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        Files.writeString(sources.resolve("p/Consumer.java"),
                """
                package p;
                public class Consumer {
                    public int value() { return new Use().value(); }
                }
                """);
        var output = write(directory.resolve("output"), compile(sources, null));
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    public static int value() { return 2; }
                }
                """);

        var result = compile(sources, output);

        assertEquals(Set.of(new SourcePath("p/Api.java")), result.compiledSources());
        assertEquals(1, result.rounds());
        assertFalse(result.classes()
                          .containsKey("p/Consumer.class"));
    }

    @Test
    void privateDeclarationChangeCompilesOnlyItsSource(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    private static int implementation() { return 1; }
                    public static int value() { return implementation(); }
                }
                """);
        var output = write(directory.resolve("output"), compile(sources, null));
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    private static long implementation() { return 2; }
                    public static int value() { return (int) implementation(); }
                }
                """);

        var result = compile(sources, output);

        assertEquals(Set.of(new SourcePath("p/Api.java")), result.compiledSources());
        assertEquals(1, result.rounds());
    }

    @Test
    void moduleVisibleDeclarationChangeRecompilesTheCurrentModule(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    static int implementation() { return 1; }
                    public static int value() { return implementation(); }
                }
                """);
        var output = write(directory.resolve("output"), compile(sources, null));
        Files.writeString(sources.resolve("p/Api.java"),
                """
                package p;
                public class Api {
                    static long implementation() { return 2; }
                    public static int value() { return (int) implementation(); }
                }
                """);

        var result = compile(sources, output);

        assertEquals(Set.of(new SourcePath("module-info.java"), new SourcePath("p/Api.java"), new SourcePath("p/Use.java")),
                result.compiledSources());
        assertEquals(2, result.rounds());
    }

    @Test
    void changedModuleHashRecompilesTheCurrentModule(@TempDir Path directory) throws Exception {
        var firstDependency = dependency(directory.resolve("dependency-1"), 1, 1);
        var sources = dependentSources(directory);
        var firstConfiguration = configuration(firstDependency);
        var output = write(directory.resolve("output-1"), SourceModuleCompilation.compile(sources, null, firstConfiguration));

        var unchanged = compile(sources, output, firstConfiguration);

        assertEquals(Set.of(), unchanged.compiledSources());
        assertEquals(Map.of(), unchanged.classes());
        var moduleInfo = ClassFile.of().parse(Files.readAllBytes(output.resolve("module-info.class")));
        assertTrue(moduleInfo.findAttribute(java.lang.classfile.Attributes.moduleHashes())
                             .isEmpty());

        var unrelatedChange = dependency(directory.resolve("dependency-2"), 1, 2);
        var metadataOnly = compile(sources, output, configuration(unrelatedChange));

        assertEquals(Set.of(new SourcePath("module-info.java"), new SourcePath("p/Use.java")),
                metadataOnly.compiledSources());
        var secondOutput = apply(output, directory.resolve("output-2"), metadataOnly);

        var observedChange = dependency(directory.resolve("dependency-3"), 2, 2);
        var recompiled = compile(sources, secondOutput, configuration(observedChange));

        assertEquals(Set.of(new SourcePath("module-info.java"), new SourcePath("p/Use.java")),
                recompiled.compiledSources());
        assertEquals(1, recompiled.rounds());
        assertEquals(Set.of("module-info.class", "p/Use.class"),
                recompiled.classes().keySet());
    }

    @Test
    void compiledModulesDoNotPinAutomaticDependencies(@TempDir Path directory) throws Exception {
        var firstDependency = automaticDependency(directory.resolve("automatic-1"), 1);
        var sources = dependentSources(directory);
        var output = write(directory.resolve("automatic-output"), SourceModuleCompilation.compile(sources, null, configuration(firstDependency)));
        var changedDependency = automaticDependency(directory.resolve("automatic-2"), 2);
        var resolved = ModuleLayer.boot()
                .configuration()
                .resolve(ModuleFinder.of(output, changedDependency.path()),
                        ModuleFinder.of(), Set.of("example.module"));

        assertTrue(resolved.findModule("example.module")
                           .isPresent());
        assertTrue(resolved.findModule("dependency.module")
                           .isPresent());

        var result = compile(sources, output, configuration(changedDependency));

        assertEquals(Set.of(new SourcePath("module-info.java"), new SourcePath("p/Use.java")),
                result.compiledSources());
    }

    @Test
    void removedSourceProducesACompleteModulePatch(@TempDir Path directory) throws Exception {
        var sources = sources(directory);
        var unused = sources.resolve("p/Unused.java");
        Files.writeString(unused,
                """
                package p;
                public class Unused { static class Nested {} }
                """);
        var output = write(directory.resolve("output"), compile(sources, null));
        Files.delete(unused);

        var result = compile(sources, output);

        assertEquals(Set.of(new SourcePath("module-info.java"), new SourcePath("p/Api.java"), new SourcePath("p/Use.java")),
                result.compiledSources());
        assertEquals(Set.of("p/Unused.class", "p/Unused$Nested.class"), result.removedClasses());
        assertTrue(Files.exists(output.resolve("p/Unused.class")));
        assertTrue(Files.exists(output.resolve("p/Unused$Nested.class")));
    }

    private record BinaryModule(Path path, ModulePathEntry entry) {}

    private static BinaryModule dependency(Path directory, int apiValue, int unusedValue) throws Exception {
        var sources = Files.createDirectories(directory.resolve("sources/d"));
        Files.writeString(directory.resolve("sources/module-info.java"),
                """
                module dependency.module {
                    exports d;
                }
                """);
        Files.writeString(sources.resolve("Api.java"),
                """
                package d;
                public class Api {
                    public static int value() { return %d; }
                }
                """
                        .formatted(apiValue));
        Files.writeString(sources.resolve("Unused.java"),
                """
                package d;
                public class Unused {
                    public static int value() { return %d; }
                }
                """
                        .formatted(unusedValue));
        var output = Files.createDirectories(directory.resolve("output"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var status = compiler.run(
                null,
                null,
                null,
                "-d",
                output.toString(),
                directory.resolve("sources/module-info.java").toString(),
                sources.resolve("Api.java").toString(),
                sources.resolve("Unused.java").toString());
        assertEquals(0, status);
        var reference = ModuleFinder.of(output)
                .find("dependency.module")
                .orElseThrow();
        return new BinaryModule(output, new ModulePathEntry("dependency.module", StandardLocation.MODULE_PATH, ModuleHash.moduleSha256(reference)));
    }

    private static BinaryModule automaticDependency(Path directory, int apiValue) throws Exception {
        var sources = Files.createDirectories(directory.resolve("sources/d"));
        Files.writeString(sources.resolve("Api.java"),
                """
                package d;
                public class Api {
                    public static int value() { return %d; }
                }
                """
                        .formatted(apiValue));
        var classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var status = compiler.run(null, null, null, "-d", classes.toString(),
                sources.resolve("Api.java").toString());
        assertEquals(0, status);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "dependency.module");
        var jar = directory.resolve("dependency.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var files = Files.walk(classes)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var name = classes.relativize(file)
                                  .toString()
                                  .replace('\\', '/');
                output.putNextEntry(new JarEntry(name));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        var reference = ModuleFinder.of(jar)
                .find("dependency.module")
                .orElseThrow();
        return new BinaryModule(jar, new ModulePathEntry("dependency.module", StandardLocation.MODULE_PATH, ModuleHash.moduleSha256(reference)));
    }

    private static Result compile(Path sources, Path previousOutput) throws Exception {
        return compile(sources, previousOutput,
                configuration(List.of(), List.of()));
    }

    private static Result compile(Path sources, Path previousOutput, Configuration configuration) throws Exception {
        var state = previousOutput == null ? State.empty() : STATES.getOrDefault(previousOutput.toAbsolutePath().normalize(),
                State.empty());
        return SourceModuleCompilation.compile(sources, previousOutput, null, previousOutput, configuration,
                SourceModuleOutput.readSources(sources), state);
    }

    private static Configuration configuration(BinaryModule module) {
        return configuration(List.of(module.path()), List.of(module.entry()));
    }

    private static Configuration configuration(List<Path> modulePath, List<ModulePathEntry> entries) {
        return new Configuration("example.module", null, false, null, ModuleRuntimeAccessOptions.EMPTY, modulePath,
                List.of(), Map.of(), entries);
    }

    private static Path dependentSources(Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("dependent-sources"));
        var packageDirectory = Files.createDirectories(sources.resolve("p"));
        Files.writeString(sources.resolve("module-info.java"),
                """
                module example.module {
                    requires dependency.module;
                    exports p;
                }
                """);
        Files.writeString(packageDirectory.resolve("Use.java"),
                """
                package p;
                public class Use {
                    public int value() { return d.Api.value(); }
                }
                """);
        return sources;
    }

    private static Path sources(Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("sources"));
        var packageDirectory = Files.createDirectories(sources.resolve("p"));
        Files.writeString(sources.resolve("module-info.java"),
                """
                module example.module {
                    exports p;
                }
                """);
        Files.writeString(packageDirectory.resolve("Api.java"),
                """
                package p;
                public class Api {
                    public static int value() { return 1; }
                }
                """);
        Files.writeString(packageDirectory.resolve("Use.java"),
                """
                package p;
                public class Use {
                    public int value() { return Api.value(); }
                }
                """);
        return sources;
    }

    private static Path apply(Path base, Path output, Result result) throws Exception {
        try (var files = Files.walk(base)) {
            for (var source : files.filter(Files::isRegularFile).toList()) {
                var target = output.resolve(base.relativize(source)
                        .toString());
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
        }
        write(output, result);
        for (var removed : result.removedClasses()) {
            Files.deleteIfExists(output.resolve(removed));
        }
        return output;
    }

    private static Path write(Path output, Result result) throws Exception {
        for (var entry : result.classes().entrySet()) {
            var target = output.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        STATES.put(output.toAbsolutePath().normalize(),
                result.state());
        return output;
    }
}
