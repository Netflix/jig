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
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.ClassFileFormatVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleHashTest {

    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void parsesTypedContentHashes() {
        assertEquals(new ModuleHash(Type.MODULE, "sha256", DIGEST), ModuleHash.parse("module:sha256:" + DIGEST));
    }

    @Test
    void rejectsMalformedHash() {
        assertThrows(IllegalArgumentException.class, () -> ModuleHash.parse("sha256:" + DIGEST));
        assertThrows(IllegalArgumentException.class, () -> ModuleHash.parse("module:sha256:not-hex"));
        assertThrows(IllegalArgumentException.class, () -> ModuleHash.parse("source:sha256:" + DIGEST));
    }

    @Test
    void readsAndWritesSingleLineModuleHash(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("module.jar.hash");
        var hash = new ModuleHash(Type.MODULE, "sha256", DIGEST);

        hash.write(path);

        assertEquals("module:sha256:" + DIGEST + "\n", Files.readString(path));
        assertEquals(hash, ModuleHash.read(path));
    }

    @Test
    void computesModernJdkModuleContentHash() throws IOException {
        var reference = ModuleFinder.ofSystem()
                .find("java.logging")
                .orElseThrow();

        ModuleHash hash = ModuleHash.moduleSha256(reference);

        assertEquals(Type.MODULE, hash.type());
        assertEquals("sha256", hash.algorithm());
        assertEquals(64, hash.digest()
                             .length());
        assertEquals(hash, ModuleHash.moduleSha256(reference));
    }

    @Test
    void hashesExplodedModulesWithoutReadingDirectories(@TempDir Path module) throws IOException {
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.module"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(
                module.resolve("module-info.class"),
                ClassFile.of().buildModule(moduleAttribute,
                        builder -> builder.withVersion(ClassFileFormatVersion.latest().major(), 0)));
        Path resources = Files.createDirectories(module.resolve("com/example"));
        Files.writeString(resources.resolve("resource.txt"), "content");

        var reference = ModuleFinder.of(module)
                .find("com.example.module")
                .orElseThrow();

        assertEquals(ModuleHash.moduleSha256(reference), ModuleHash.moduleSha256(reference));
    }

    @Test
    void includesArchiveDirectoryEntriesLikeJdkModuleHashes(@TempDir Path directory) throws IOException {
        Path withDirectories = moduleJar(directory.resolve("with-directories.jar"), true);
        Path withoutDirectories = moduleJar(directory.resolve("without-directories.jar"), false);

        ModuleHash archiveHash = ModuleHash.moduleSha256(ModuleFinder.of(withDirectories)
                .find("com.example.module")
                .orElseThrow());
        ModuleHash compactHash = ModuleHash.moduleSha256(ModuleFinder.of(withoutDirectories)
                .find("com.example.module")
                .orElseThrow());

        assertNotEquals(archiveHash, compactHash);
    }

    @Test
    void hashesModulePatchEntriesByLogicalPathAndContent(@TempDir Path directory) throws IOException {
        var first = directory.resolve("first");
        var second = directory.resolve("second");
        Files.createDirectories(first.resolve("p"));
        Files.createDirectories(second.resolve("p"));
        Files.writeString(first.resolve("p/Api.class"), "api");
        Files.writeString(second.resolve("p/Api.class"), "api");

        var expected = ModuleHash.patchSha256(first);

        assertEquals(Type.PATCH, expected.type());
        assertEquals(expected, ModuleHash.patchSha256(second));
        Files.writeString(second.resolve("p/Api.class"), "changed");
        assertNotEquals(expected, ModuleHash.patchSha256(second));
    }

    private static Path moduleJar(Path jar, boolean includeDirectories) throws IOException {
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.module"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        byte[] moduleInfo = ClassFile.of().buildModule(moduleAttribute,
                builder -> builder.withVersion(ClassFileFormatVersion.latest().major(), 0));
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("module-info.class"));
            output.write(moduleInfo);
            output.closeEntry();
            if (includeDirectories) {
                output.putNextEntry(new ZipEntry("com/"));
                output.closeEntry();
                output.putNextEntry(new ZipEntry("com/example/"));
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry("com/example/resource.txt"));
            output.write("content".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
