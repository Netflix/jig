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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.netflix.module.ModuleRuntimeMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRuntimeMetadataTest {

    @Test
    void infersClassFileState() {
        var declaration = ModuleAttribute.of(ModuleDesc.of("test.module"), builder -> {});
        byte[] bytes = ClassFile.of().buildModule(declaration, builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_25.major(), 0xFFFF));

        var metadata = ModuleRuntimeMetadata.fromClassBytes(bytes);
        assertEquals(25, metadata.release());
        assertTrue(metadata.preview());
    }

    @Test
    void readsMetadataFromResolvedModuleReference(@TempDir Path moduleDirectory) throws IOException {
        var declaration = ModuleAttribute.of(ModuleDesc.of("test.module"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        byte[] bytes = ClassFile.of().buildModule(declaration, builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_25.major(), 0));
        Files.write(moduleDirectory.resolve("module-info.class"), bytes);
        var reference = ModuleFinder.of(moduleDirectory)
                .find("test.module")
                .orElseThrow();

        var metadata = ModuleRuntimeMetadata.read(reference).orElseThrow();

        assertEquals(25, metadata.release());
    }
}
