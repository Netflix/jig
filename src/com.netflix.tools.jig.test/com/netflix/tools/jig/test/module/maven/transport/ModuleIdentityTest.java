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

package com.netflix.tools.jig.test.module.maven.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.module.FindException;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.ClassFileFormatVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.netflix.tools.jig.module.maven.transport.AbstractModuleTransporter.ModuleIdentity;
import com.netflix.tools.jig.module.maven.transport.ZipCentralDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleIdentityTest {
    @Test
    void readsOsgiBundleSymbolicNameAsFallback() throws Exception {
        var identity = parseManifest(Map.of("Bundle-SymbolicName", "com.example.bundle;singleton:=true"));

        assertNull(identity.moduleName());
        assertEquals("com.example.bundle", identity.osgiModuleName());
    }

    @Test
    void ignoresOsgiSymbolicNameThatIsNotAJavaModuleName() throws Exception {
        var identity = parseManifest(Map.of("Bundle-SymbolicName", "com.example-bundle"));

        assertNull(identity.osgiModuleName());
    }

    @Test
    void keepsOsgiNameSeparateFromAutomaticModuleName() throws Exception {
        var identity = parseManifest(Map.of("Automatic-Module-Name", "com.example.java", "Bundle-SymbolicName", "com.example.osgi"));

        assertEquals("com.example.java", identity.moduleName());
        assertEquals("com.example.osgi", identity.osgiModuleName());
    }

    @Test
    void selectsTheRuntimeModuleDescriptorFromMultiReleaseJars(@TempDir Path directory) throws Exception {
        var runtime = JarFile.runtimeVersion().feature();
        var jar = directory.resolve("example.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Name.MULTI_RELEASE, "true");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            write(output, "module-info.class", moduleInfo("com.example.base"));
            write(output, "META-INF/versions/9/module-info.class", moduleInfo("com.example.runtime"));
            write(output, "META-INF/versions/" + (runtime + 1) + "/module-info.class", moduleInfo("com.example.future"));
        }

        var identity = ModuleIdentity.parseJar(jar);
        var probedIdentity = probeJar(jar);

        assertEquals("com.example.runtime", identity.moduleName());
        assertEquals("com.example.runtime", probedIdentity.moduleName());
    }

    @Test
    void ignoresVersionedDescriptorsBelowTheBaseVersion(@TempDir Path directory) throws Exception {
        var jar = directory.resolve("example.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Name.MULTI_RELEASE, "true");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            write(output, "module-info.class", moduleInfo("com.example.base"));
            var version = JarFile.baseVersion().feature() - 1;
            write(output, "META-INF/versions/" + version + "/module-info.class", moduleInfo("com.example.invalid"));
        }

        var identity = ModuleIdentity.parseJar(jar);
        var probedIdentity = probeJar(jar);

        assertEquals("com.example.base", identity.moduleName());
        assertEquals("com.example.base", probedIdentity.moduleName());
    }

    @Test
    void ignoresVersionedDescriptorsWithoutTheMultiReleaseManifestHeader(@TempDir Path directory) throws Exception {
        var jar = directory.resolve("example.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            write(output, "module-info.class", moduleInfo("com.example.base"));
            write(output, "META-INF/versions/9/module-info.class", moduleInfo("com.example.versioned"));
        }

        var identity = ModuleIdentity.parseJar(jar);
        var probedIdentity = probeJar(jar);

        assertEquals("com.example.base", identity.moduleName());
        assertEquals("com.example.base", probedIdentity.moduleName());
    }

    @Test
    void preservesModuleFinderValidationForAutomaticModules(@TempDir Path directory) throws Exception {
        var jar = directory.resolve("example.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "com.example.application");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            write(output, "META-INF/services/com.example.Service", "com.example.MissingProvider\n".getBytes(StandardCharsets.UTF_8));
        }

        assertThrows(FindException.class, () -> ModuleIdentity.parseJar(jar));
    }

    @Test
    void readsIdentityFromJmodClassesSection(@TempDir Path directory) throws Exception {
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.tool"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(
                classes.resolve("module-info.class"),
                ClassFile.of().buildModule(moduleAttribute,
                        builder -> builder.withVersion(ClassFileFormatVersion.latest().major(), 0)));
        Path jmod = directory.resolve("com.example.tool.jmod");
        var errors = new StringWriter();
        int result = ToolProvider.findFirst("jmod")
                .orElseThrow()
                .run(new PrintWriter(Writer.nullWriter()), new PrintWriter(errors), "create", "--class-path",
                        classes.toString(), jmod.toString());
        assertEquals(0, result, errors.toString());

        var identity = ModuleIdentity.parseJmod(jmod);

        assertEquals("com.example.tool", identity.moduleName());
        assertNotNull(identity.descriptor());

        byte[] bytes = Files.readAllBytes(jmod);
        var directoryEntries = ZipCentralDirectory.parse(bytes, 0, 4).orElseThrow();
        var probedIdentity = ModuleIdentity.parseJmodEntries(directoryEntries.entryNames(),
                name -> {
                    var entry = directoryEntries.find(name).orElseThrow();
                    byte[] localData = Arrays.copyOfRange(bytes, Math.toIntExact(entry.localHeaderOffset()), bytes.length);
                    return ZipCentralDirectory.extractEntry(entry, localData);
                });
        assertEquals("com.example.tool", probedIdentity.moduleName());
    }

    private static ModuleIdentity probeJar(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var names = zip.stream()
                    .map(ZipEntry::getName)
                    .collect(Collectors.toSet());
            return ModuleIdentity.parseJarEntries(names, name -> {
                try (var input = zip.getInputStream(zip.getEntry(name))) {
                    return input.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static byte[] moduleInfo(String moduleName) {
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of(moduleName),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        return ClassFile.of().buildModule(moduleAttribute,
                builder -> builder.withVersion(ClassFileFormatVersion.latest().major(), 0));
    }

    private static void write(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static ModuleIdentity parseManifest(Map<String, String> headers) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        headers.forEach((name, value) -> manifest.getMainAttributes().putValue(name, value));
        var bytes = new ByteArrayOutputStream();
        manifest.write(bytes);
        return ModuleIdentity.parseJmodEntries(Set.of("classes/META-INF/MANIFEST.MF"), ignored -> bytes.toByteArray());
    }
}
