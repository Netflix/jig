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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.compile.internal.SourceModuleCompilation.Configuration;
import com.netflix.module.compile.internal.SourceModuleCompilation.Paths;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;
import com.netflix.module.compile.internal.SourceModuleOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceModuleOutputTest {
    @Test
    void writesResourcesAndExcludesNestedModules(@TempDir Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("sources"));
        var rootPackage = Files.createDirectories(sources.resolve("p"));
        Files.writeString(sources.resolve("module-info.java"), "module example.module { exports p; }\n");
        Files.writeString(rootPackage.resolve("Example.java"), "package p; public class Example {}\n");
        Files.writeString(sources.resolve("resource.txt"), "first\n");
        var nested = Files.createDirectories(sources.resolve("nested/p"));
        Files.writeString(sources.resolve("nested/module-info.java"), "module nested.mod { exports p; }\n");
        Files.writeString(nested.resolve("Nested.java"), "package p; public class Nested {}\n");

        var first = SourceModuleOutput.compile(sources, null, null, directory.resolve("first"), 25,
                configuration(25));

        assertTrue(Files.isRegularFile(first.output()
                .resolve("p/Example.class")));
        assertEquals("first\n", Files.readString(first.output()
                .resolve("resource.txt")));
        assertFalse(Files.exists(first.output()
                .resolve("p/Nested.class")));

        Files.delete(sources.resolve("resource.txt"));
        var secondInputs = SourceModuleOutput.readInputs(sources, 25);
        var second = SourceModuleOutput.compile(
                sources,
                first.output(),
                null,
                directory.resolve("second"),
                25,
                configuration(25),
                secondInputs,
                first.state());

        assertEquals(0, second.compilations());
        assertFalse(Files.exists(second.output()
                .resolve("resource.txt")));
        assertFalse(
                Files.isSameFile(first.output().resolve("p/Example.class"),
                        second.output().resolve("p/Example.class")));
        assertArrayEquals(Files.readAllBytes(first.output().resolve("p/Example.class")),
                Files.readAllBytes(second.output().resolve("p/Example.class")));
    }

    @Test
    void selectsAndCompilesMultiReleaseSources(@TempDir Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("sources/p"));
        Files.writeString(directory.resolve("sources/module-info.java"), "module example.module { exports p; }\n");
        Files.writeString(sources.resolve("Example.java"),
                """
                package p;
                public class Example { public int version() { return 11; } }
                """);
        var version = Files.createDirectories(directory.resolve("sources/META-INF/versions/17/p"));
        Files.writeString(version.resolve("Example.java"),
                """
                package p;
                public class Example { public int version() { return 17; } }
                """);
        Files.writeString(directory.resolve("sources/META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n");
        Files.writeString(directory.resolve("sources/version.txt"), "base\n");
        Files.writeString(directory.resolve("sources/META-INF/versions/17/version.txt"), "17\n");

        var release16Inputs = SourceModuleOutput.readInputs(directory.resolve("sources"), 16);
        assertTrue(release16Inputs.observations()
                .files()
                .containsKey("META-INF/versions/17/p/Example.java"));
        assertTrue(release16Inputs.observations()
                .files()
                .containsKey("META-INF/versions/17/version.txt"));

        var release16 = SourceModuleOutput.compile(directory.resolve("sources"), null, null, directory.resolve("release-16"),
                16, configuration(11));
        var release17 = SourceModuleOutput.compile(directory.resolve("sources"), null, null, directory.resolve("release-17"),
                17, configuration(11));

        assertEquals(55,
                ClassFile.of()
                        .parse(Files.readAllBytes(release16.output().resolve("p/Example.class")))
                        .majorVersion());
        assertEquals(55, ClassFile.of().parse(content(release17, "p/Example.class")).majorVersion());
        assertEquals(61, ClassFile.of().parse(content(release17, "META-INF/versions/17/p/Example.class")).majorVersion());
        assertEquals("base\n", Files.readString(release16.output()
                .resolve("version.txt")));
        assertEquals("base\n", new String(content(release17, "version.txt"), StandardCharsets.UTF_8));
        assertEquals("17\n", new String(content(release17, "META-INF/versions/17/version.txt"), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 11})
    void preservesMultiReleaseManifestAttributes(int release, @TempDir Path directory) throws Exception {
        var root = Files.createDirectories(directory.resolve("sources"));
        var base = Files.createDirectories(root.resolve("p"));
        String source = """
                package p;
                public class Example {
                    public static void premain(String arguments) {}
                    public static void main(String[] arguments) {}
                }
                """;
        Files.writeString(base.resolve("Example.java"), source);
        Path descriptor;
        if (release == 8) {
            descriptor = Files.createDirectories(root.resolve("META-INF/versions/9")).resolve("module-info.java");
        } else {
            descriptor = root.resolve("module-info.java");
            var version = Files.createDirectories(root.resolve("META-INF/versions/17/p"));
            Files.writeString(version.resolve("Example.java"), source);
        }
        Files.writeString(descriptor, "module example.module { exports p; }\n");

        var expected = new Manifest();
        var attributes = expected.getMainAttributes();
        attributes.put(Name.MANIFEST_VERSION, "1.0");
        attributes.put(Name.MULTI_RELEASE, "true");
        attributes.put(Name.MAIN_CLASS, "p.Example");
        attributes.putValue("Premain-Class", "p.Example");
        attributes.putValue("Agent-Class", "p.Example");
        attributes.putValue("Can-Redefine-Classes", "true");
        attributes.put(Name.IMPLEMENTATION_TITLE, "Example module");
        attributes.putValue("X-Folded-Value", "manifest-value-".repeat(12));
        var section = new Attributes();
        section.put(Name.SEALED, "true");
        section.put(Name.IMPLEMENTATION_VERSION, "1.2.3");
        expected.getEntries().put("p/", section);
        try (var output = Files.newOutputStream(root.resolve("META-INF/MANIFEST.MF"))) {
            expected.write(output);
        }

        var output = SourceModuleOutput.compile(root, null, null, directory.resolve("output"),
                25, configuration(release));

        try (var archive = new JarFile(SourceModuleOutput.archive(output.output()).toFile())) {
            var actual = archive.getManifest();
            for (var attribute : expected.getMainAttributes().entrySet()) {
                assertEquals(attribute.getValue(), actual.getMainAttributes().get(attribute.getKey()),
                        attribute.getKey().toString());
            }
            assertEquals(expected.getEntries(), actual.getEntries());
        }
    }

    @Test
    void compilesMultiReleaseSourceBytesReadByJavac(@TempDir Path directory) throws Exception {
        var root = Files.createDirectories(directory.resolve("sources"));
        var base = Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("module-info.java"), "module example.module { exports p to missing.module; }\n");
        Files.writeString(base.resolve("Example.java"),
                """
                package p;
                public class Example { public int version() { return 11; } }
                """);
        var version = Files.createDirectories(root.resolve("META-INF/versions/17/p"));
        var versionSource = version.resolve("Example.java");
        Files.writeString(versionSource,
                """
                package p;
                public class Example { public int version() { return 17; } }
                """);
        Files.writeString(root.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n");

        var expected = SourceModuleOutput.compile(root, null, null, directory.resolve("expected"), 17,
                configuration(11));
        var inputs = SourceModuleOutput.readInputs(root, 17);
        Files.writeString(versionSource,
                """
                package p;
                public class Example { public int version() { return 18; } }
                """);

        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        var observed = SourceModuleOutput.compile(
                root,
                null,
                null,
                directory.resolve("observed"),
                17,
                configuration(11),
                inputs,
                State.empty(),
                diagnostics::add);
        var current = SourceModuleOutput.compile(root, null, null, directory.resolve("current"), 17,
                configuration(11));

        var observedClass = content(observed, "META-INF/versions/17/p/Example.class");
        assertFalse(Arrays.equals(content(expected, "META-INF/versions/17/p/Example.class"), observedClass));
        assertArrayEquals(content(current, "META-INF/versions/17/p/Example.class"), observedClass);
        assertNotEquals(inputs.hash(), observed.inputHash());
        assertEquals(current.inputHash(), observed.inputHash());
        var diagnosticSource = diagnostics.stream()
                .filter(diagnostic -> diagnostic.getSource() != null)
                .findFirst()
                .orElseThrow()
                .getSource();
        assertEquals(root.resolve("module-info.java").toUri(),
                URI.create(diagnosticSource.toUri().toString()));
    }

    @Test
    void rejectsMultiReleasePublicApiChanges(@TempDir Path directory) throws Exception {
        var root = Files.createDirectories(directory.resolve("sources"));
        var base = Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("module-info.java"), "module example.module { exports p; }\n");
        Files.writeString(base.resolve("Example.java"),
                """
                package p;
                public class Example { public int version() { return 11; } }
                """);
        var version = Files.createDirectories(root.resolve("META-INF/versions/17/p"));
        Files.writeString(version.resolve("Example.java"),
                """
                package p;
                public class Example {
                    public int version() { return 17; }
                    public int added() { return 17; }
                }
                """);
        Files.writeString(root.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n");

        var failure = assertThrows(
                IOException.class,
                () -> SourceModuleOutput.compile(root, null, null, directory.resolve("output"), 17,
                        configuration(11)));

        assertTrue(failure.getMessage().contains("contains a class with different api from earlier version"),
                failure.getMessage());
        assertFalse(Files.exists(directory.resolve("output")));
    }

    @Test
    void compilesNonModularBaseAndVersionNineDescriptor(@TempDir Path directory) throws Exception {
        var root = Files.createDirectories(directory.resolve("sources"));
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/Example.java"), "package p; public class Example {}\n");
        var version = Files.createDirectories(root.resolve("META-INF/versions/9"));
        Files.writeString(version.resolve("module-info.java"),
                "/** @release 8 */ module example.module { exports p; }\n");
        Files.writeString(root.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nMulti-Release: true\n\n");
        var configuration = new Configuration("example.module", 8, false, "1.2.3", "p.Example",
                ModuleRuntimeAccessOptions.EMPTY, new Paths(
                        List.of(), List.of(), Map.of()), List.of(), null);
        var output = SourceModuleOutput.compile(root, null, null, directory.resolve("output"), 25, configuration);
        assertEquals(52, ClassFile.of().parse(content(output, "p/Example.class")).majorVersion());
        assertEquals(53, ClassFile.of().parse(content(output, "META-INF/versions/9/module-info.class")).majorVersion());
        var descriptor = java.lang.module.ModuleFinder.of(output.output()).find("example.module").orElseThrow().descriptor();
        assertEquals("1.2.3", descriptor.rawVersion().orElseThrow());
        assertEquals("p.Example", descriptor.mainClass().orElseThrow());
        try (var archive = new ZipFile(SourceModuleOutput.archive(output.output()).toFile())) {
            assertNull(archive.getEntry("module-info.class"));
        }
        Files.writeString(root.resolve("p/Example.java"),
                "package p; public class Example { public String value() { return \"x\".strip(); } }\n");
        var failure = assertThrows(IOException.class, () -> SourceModuleOutput.compile(root, null, null,
                directory.resolve("invalid"), 25, configuration));
        assertTrue(failure.getMessage().contains("strip"), failure.getMessage());
    }

    @Test
    void rejectsReleaseEightWithoutVersionNineAnchor(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.module {}\n");
        assertThrows(IllegalArgumentException.class, () -> SourceModuleOutput.compile(
                directory, null, null, directory.resolve("output"), 25, configuration(8)));
    }

    private static byte[] content(SourceModuleOutput.Result output, String name) throws IOException {
        Path archive = SourceModuleOutput.archive(output.output());
        if (archive == null) {
            return Files.readAllBytes(output.output().resolve(name));
        }
        try (var zip = new ZipFile(archive.toFile());
             var input = zip.getInputStream(zip.getEntry(name))) {
            return input.readAllBytes();
        }
    }

    private static Configuration configuration(int release) {
        return new Configuration(
                "example.module",
                release,
                false,
                null,
                ModuleRuntimeAccessOptions.EMPTY,
                List.of(),
                List.of(),
                Map.of(),
                List.of());
    }
}
