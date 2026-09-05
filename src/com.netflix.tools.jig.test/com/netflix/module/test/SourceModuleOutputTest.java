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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.compile.internal.SourceModuleCompilation.Configuration;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;
import com.netflix.module.compile.internal.SourceModuleOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals(61,
                ClassFile.of()
                        .parse(Files.readAllBytes(release17.output().resolve("p/Example.class")))
                        .majorVersion());
        assertEquals("base\n", Files.readString(release16.output()
                .resolve("version.txt")));
        assertEquals("17\n", Files.readString(release17.output()
                .resolve("version.txt")));
        assertFalse(Files.exists(release17.output()
                .resolve("META-INF/versions")));
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

        var observedClass = Files.readAllBytes(observed.output()
                .resolve("p/Example.class"));
        assertFalse(Arrays.equals(Files.readAllBytes(expected.output()
                .resolve("p/Example.class")),
                observedClass));
        assertArrayEquals(Files.readAllBytes(current.output()
                .resolve("p/Example.class")),
                observedClass);
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
