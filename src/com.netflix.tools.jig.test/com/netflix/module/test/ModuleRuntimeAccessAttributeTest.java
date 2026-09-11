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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessAttribute;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.ModuleRuntimeAccessOptions.PackageAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRuntimeAccessAttributeTest {
    @Test
    void optionsAreFullyQualifiedAndDeduplicated() {
        var options = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("com.example.nativebinding")
                .enableNativeAccess("com.example.nativebinding")
                .enableFinalFieldMutation("com.example.model")
                .addExports("jdk.compiler", "com.sun.tools.javac.tree", "com.example.processor")
                .addOpens("java.base", "java.lang", "com.example.framework")
                .build();

        assertEquals(List.of("com.example.nativebinding"), options.enableNativeAccess());
        assertEquals(List.of("com.example.model"), options.enableFinalFieldMutation());
        assertEquals("jdk.compiler/com.sun.tools.javac.tree=com.example.processor",
                options.addExports()
                       .getFirst()
                       .toFlagValue());
        assertEquals("java.base/java.lang=com.example.framework",
                options.addOpens()
                       .getFirst()
                       .toFlagValue());
    }

    @Test
    void rejectsPseudoModulesAndInvalidPackages() {
        assertThrows(IllegalArgumentException.class,
                () -> ModuleRuntimeAccessOptions.newBuilder().enableNativeAccess("ALL-UNNAMED"));
        assertThrows(IllegalArgumentException.class, () -> new PackageAccess("java.base", "not/a/package", "com.example.app"));
        assertThrows(IllegalArgumentException.class, () -> new PackageAccess("java.base", "java.lang", "ALL-UNNAMED"));
    }

    @Test
    void classFileAttributeRoundTripsTypedEntriesAndGroupedTargets() {
        var options = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("com.example.nativebinding")
                .enableFinalFieldMutation("com.example.model")
                .addExports("jdk.compiler", "com.sun.tools.javac.tree", "com.example.one")
                .addExports("jdk.compiler", "com.sun.tools.javac.tree", "com.example.two")
                .addOpens("java.base", "java.lang", "com.example.framework")
                .build();
        var declaration = ModuleAttribute.of(ModuleDesc.of("com.example.application"), builder -> {});

        var bytes = ClassFile.of().buildModule(declaration, builder -> builder.with(ModuleRuntimeAccessAttribute.of(options)));
        var model = ClassFile.of(ModuleRuntimeAccessAttribute.mapperOption()).parse(bytes);
        var attribute = model.findAttribute(ModuleRuntimeAccessAttribute.mapper()).orElseThrow();

        assertEquals("com.netflix.module.ModuleRuntimeAccess", attribute.attributeName()
                .stringValue());
        assertEquals(options, attribute.options());
        assertTrue(model.isModuleInfo());
    }

    @Test
    void readsLegacyUnnamespacedAttributes(@TempDir Path modules) throws Exception {
        var options = ModuleRuntimeAccessOptions.newBuilder()
                .addExports("jdk.compiler", "com.sun.tools.javac.tree", "com.example.application")
                .build();
        var declaration = ModuleAttribute.of(ModuleDesc.of("com.example.application"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        var namespaced = ClassFile.of().buildModule(declaration,
                builder -> builder.with(ModuleRuntimeAccessAttribute.of(options)));
        var legacy = renameUtf8(namespaced, "com.netflix.module.ModuleRuntimeAccess", "ModuleRuntimeAccess");
        var module = Files.createDirectories(modules.resolve("com.example.application"));
        Files.write(module.resolve("module-info.class"), legacy);
        var reference = ModuleFinder.of(modules).find("com.example.application").orElseThrow();

        assertEquals(options, ModuleRuntimeAccess.read(reference).orElseThrow());
    }

    @Test
    void launchRequiresIndependentAuthorizationForReachableAttributes(@TempDir Path modules) throws Exception {
        var library = Files.createDirectories(modules.resolve("com.example.library"));
        var libraryDeclaration = ModuleAttribute.of(ModuleDesc.of("com.example.library"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        var requirements = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("com.example.library")
                .addOpens("java.base", "java.lang", "com.example.library")
                .build();
        Files.write(library.resolve("module-info.class"),
                ClassFile.of().buildModule(libraryDeclaration, builder -> builder.with(ModuleRuntimeAccessAttribute.of(requirements))));

        var application = Files.createDirectories(modules.resolve("com.example.application"));
        var applicationDeclaration = ModuleAttribute.of(ModuleDesc.of("com.example.application"),
                builder -> {
                    builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
                    builder.requires(ModuleDesc.of("com.example.library"), Set.of(), null);
                });
        Files.write(application.resolve("module-info.class"),
                ClassFile.of().buildModule(applicationDeclaration));

        var missing = assertThrows(IllegalArgumentException.class, () -> ModuleRuntimeAccess.checkLaunch(withSystemRuntimeAccess("--module-path", modules.toString(), "--module", "com.example.application/example.Main")));
        assertTrue(missing.getMessage()
                          .contains("--enable-native-access=com.example.library"));
        assertTrue(missing.getMessage()
                          .contains("--add-opens=java.base/java.lang=com.example.library"));

        assertDoesNotThrow(() -> ModuleRuntimeAccess.checkLaunch(withSystemRuntimeAccess("--module-path", modules.toString(), "--enable-native-access", "com.example.library", "--add-opens",
                "java.base/java.lang=com.example.library", "--module", "com.example.application/example.Main")));
    }

    @Test
    void launchResolutionIncludesObservableSystemModulesOutsideTheBootLayer(@TempDir Path modules) throws Exception {
        var systemModule = ModuleFinder.ofSystem().findAll().stream()
                .map(reference -> reference.descriptor().name())
                .filter(name -> ModuleLayer.boot()
                        .findModule(name)
                        .isEmpty())
                .findFirst()
                .orElseThrow();
        var application = Files.createDirectories(modules.resolve("com.example.application"));
        var declaration = ModuleAttribute.of(ModuleDesc.of("com.example.application"),
                builder -> {
                    builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
                    builder.requires(ModuleDesc.of(systemModule), Set.of(), null);
                });
        Files.write(application.resolve("module-info.class"),
                ClassFile.of().buildModule(declaration));

        assertDoesNotThrow(() -> ModuleRuntimeAccess.checkLaunch(withSystemRuntimeAccess("--module-path", modules.toString(), "--module", "com.example.application/example.Main")));
    }

    private static byte[] renameUtf8(byte[] classFile, String current, String replacement) {
        byte[] currentBytes = current.getBytes(StandardCharsets.UTF_8);
        byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
        for (int index = 2; index <= classFile.length - currentBytes.length; index++) {
            if ((classFile[index - 2] & 0xff) != currentBytes.length >>> 8
                    || (classFile[index - 1] & 0xff) != (currentBytes.length & 0xff)) {
                continue;
            }
            boolean matches = true;
            for (int offset = 0; offset < currentBytes.length; offset++) {
                if (classFile[index + offset] != currentBytes[offset]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            var renamed = new ByteArrayOutputStream(classFile.length - currentBytes.length + replacementBytes.length);
            renamed.writeBytes(java.util.Arrays.copyOfRange(classFile, 0, index - 2));
            renamed.write(replacementBytes.length >>> 8);
            renamed.write(replacementBytes.length & 0xff);
            renamed.writeBytes(replacementBytes);
            renamed.writeBytes(java.util.Arrays.copyOfRange(classFile, index + currentBytes.length, classFile.length));
            return renamed.toByteArray();
        }
        throw new IllegalArgumentException("Class file does not contain " + current);
    }

    private static List<String> withSystemRuntimeAccess(String... arguments) throws IOException {
        var required = ModuleRuntimeAccessOptions.newBuilder();
        for (var reference : ModuleFinder.ofSystem().findAll()) {
            ModuleRuntimeAccess.read(reference).ifPresent(options -> ModuleRuntimeAccess.add(required, options));
        }

        var result = new ArrayList<String>();
        var options = required.build();
        options.enableNativeAccess().forEach(module -> result.add("--enable-native-access=" + module));
        options.enableFinalFieldMutation().forEach(module -> result.add("--enable-final-field-mutation=" + module));
        options.addExports().forEach(access -> result.add("--add-exports=" + access.toFlagValue()));
        options.addOpens().forEach(access -> result.add("--add-opens=" + access.toFlagValue()));
        result.addAll(List.of(arguments));
        return List.copyOf(result);
    }
}
