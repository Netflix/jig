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
import java.lang.module.ModuleDescriptor.Version;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import com.netflix.module.ModuleInfoHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModuleInfoHashTest {

    @Test
    @DisplayName("compute hash is deterministic")
    void computeHashDeterministic() throws IOException {
        ModuleReference ref = ModuleFinder.ofSystem()
                .find("java.logging")
                .orElseThrow();
        ModuleHash hash1 = ModuleHash.moduleSha256(ref);
        ModuleHash hash2 = ModuleHash.moduleSha256(ref);
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("compute hash returns valid SHA-256 hex string")
    void computeHashFormat() throws IOException {
        ModuleReference ref = ModuleFinder.ofSystem()
                .find("java.logging")
                .orElseThrow();
        ModuleHash hash = ModuleHash.moduleSha256(ref);
        assertNotNull(hash);
        assertEquals(64, hash.digest().length(),
                "SHA-256 hex should be 64 chars");
        assertTrue(hash.digest()
                       .matches("[0-9a-f]+"),
                "should be lowercase hex");
    }

    @Test
    @DisplayName("write and read round-trip")
    void roundTrip(@TempDir Path dir) throws IOException {
        Path hashFile = dir.resolve("module-info.hash");

        ModuleInfoHash original = ModuleInfoHash.newBuilder()
                .put("com.example.lib", moduleHash("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"))
                .put("com.example.api", "2.0.0", moduleHash("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"))
                .build();

        original.write(hashFile);

        ModuleInfoHash loaded = ModuleInfoHash.read(hashFile);
        assertEquals(original.get("com.example.lib"), loaded.get("com.example.lib"));
        assertEquals(original.get("com.example.api", "2.0.0"), loaded.get("com.example.api", "2.0.0"));
    }

    @Test
    @DisplayName("read ignores blank lines and comments")
    void readIgnoresCommentsAndBlanks(@TempDir Path dir) throws IOException {
        Path hashFile = dir.resolve("module-info.hash");
        Files.writeString(hashFile,
                """
                # this is a comment

                com.example.lib=module:sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789

                # another comment
                com.example.api@2.0.0=module:sha256:1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef
                """);

        ModuleInfoHash hashes = ModuleInfoHash.read(hashFile);
        assertTrue(hashes.get("com.example.lib")
                         .isPresent());
        assertTrue(hashes.get("com.example.api", "2.0.0")
                         .isPresent());
    }

    @Test
    @DisplayName("get returns empty for unknown module")
    void getUnknown() {
        ModuleInfoHash hashes = ModuleInfoHash.newBuilder().build();
        assertTrue(hashes.get("nonexistent")
                         .isEmpty());
    }

    @Test
    @DisplayName("compute and put accepts a module without a JPMS version")
    void computeAndPutUnversionedModule(@TempDir Path dir) throws Exception {
        ModuleReference ref = ModuleFinder.of(explicitJar(dir, "com.example.unversioned"))
                .find("com.example.unversioned")
                .orElseThrow();

        ModuleInfoHash hashes = ModuleInfoHash.newBuilder()
                .computeAndPut(ref)
                .build();

        assertEquals(ModuleHash.moduleSha256(ref), hashes.get("com.example.unversioned").orElseThrow());
    }

    @Test
    @DisplayName("verify returns true for matching hash")
    void verifyMatch() throws IOException {
        ModuleReference ref = ModuleFinder.ofSystem()
                .find("java.logging")
                .orElseThrow();
        String version = ref.descriptor()
                            .version()
                            .map(Version::toString)
                            .orElse("0");
        ModuleHash hash = ModuleHash.moduleSha256(ref);

        ModuleInfoHash hashes = ModuleInfoHash.newBuilder()
                .put("java.logging", version, hash)
                .build();

        assertTrue(hashes.verify("java.logging", version, ref));
    }

    @Test
    @DisplayName("verify returns false for mismatched hash")
    void verifyMismatch() throws IOException {
        ModuleReference ref = ModuleFinder.ofSystem()
                .find("java.logging")
                .orElseThrow();
        String version = ref.descriptor()
                            .version()
                            .map(Version::toString)
                            .orElse("0");

        ModuleInfoHash hashes = ModuleInfoHash.newBuilder()
                .put("java.logging", version, moduleHash("0000000000000000000000000000000000000000000000000000000000000000"))
                .build();

        assertFalse(hashes.verify("java.logging", version, ref));
    }

    @Test
    @DisplayName("verify returns false for missing entry")
    void verifyMissing() throws IOException {
        ModuleReference ref = ModuleFinder.ofSystem()
                .find("java.logging")
                .orElseThrow();
        ModuleInfoHash hashes = ModuleInfoHash.newBuilder().build();

        assertFalse(hashes.verify("java.logging", "1.0.0", ref));
    }

    @Test
    @DisplayName("written file is sorted by module key")
    void writtenFileIsSorted(@TempDir Path dir) throws IOException {
        Path hashFile = dir.resolve("module-info.hash");
        ModuleHash hashA = moduleHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        ModuleHash hashZ = moduleHash("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        ModuleInfoHash hashes = ModuleInfoHash.newBuilder()
                .put("z.module", "1.0.0", hashA)
                .put("a.module", "1.0.0", hashZ)
                .build();

        hashes.write(hashFile);

        String content = Files.readString(hashFile);
        int posA = content.indexOf("a.module@");
        int posZ = content.indexOf("z.module@");
        assertTrue(posA < posZ, "entries should be sorted: " + content);
    }

    @Test
    void describesEveryDifference() {
        ModuleHash first = moduleHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        ModuleHash second = moduleHash("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        ModuleInfoHash expected = ModuleInfoHash.newBuilder()
                .put("com.example.changed", "1.0", first)
                .put("com.example.removed", "1.0", first)
                .build();
        ModuleInfoHash observed = ModuleInfoHash.newBuilder()
                .put("com.example.changed", "1.0", second)
                .put("com.example.added", "1.0", second)
                .build();

        assertEquals(
                List.of("com.example.added@1.0 is not recorded; resolved " + second, "com.example.changed@1.0 changed; expected " + first + ", resolved " + second, "com.example.removed@1.0 is no longer resolved; expected " + first),
                expected.differences(observed));
    }

    private static ModuleHash moduleHash(String digest) {
        return new ModuleHash(Type.MODULE, "sha256", digest);
    }

    private static Path explicitJar(Path directory, String moduleName) throws Exception {
        Path source = directory.resolve("src/module-info.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "module " + moduleName + " {}\n");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, result);
        Path jar = directory.resolve(moduleName + ".jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            var entry = new java.util.jar.JarEntry("module-info.class");
            output.putNextEntry(entry);
            Files.copy(classes.resolve("module-info.class"), output);
            output.closeEntry();
        }
        return jar;
    }
}
