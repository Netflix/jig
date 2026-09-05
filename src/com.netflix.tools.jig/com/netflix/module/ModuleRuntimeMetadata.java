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

package com.netflix.module;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.module.ModuleReference;
import java.lang.reflect.ClassFileFormatVersion;
import java.util.Optional;

/**
 * Runtime metadata not exposed by {@link java.lang.module.ModuleDescriptor}.
 *
 * @param release the Java release of the class-file format
 * @param preview whether the module descriptor uses preview features
 */
public record ModuleRuntimeMetadata(int release, boolean preview) {

    /** Validates and creates module runtime metadata. */
    public ModuleRuntimeMetadata {
        if (release < 9) {
            throw new IllegalArgumentException("Invalid module release: " + release);
        }
    }

    /**
     * Reads metadata from a module descriptor.
     *
     * @param reference the module to inspect
     * @return the metadata, or an empty value for an automatic module
     * @throws IOException if an explicit module descriptor cannot be read
     */
    public static Optional<ModuleRuntimeMetadata> read(ModuleReference reference) throws IOException {
        if (reference.descriptor().isAutomatic()) {
            return Optional.empty();
        }
        try (var reader = reference.open();
             var input = reader.open("module-info.class").orElseThrow(() -> new IOException("Module " + reference.descriptor().name() + " has no module-info.class"))) {
            return Optional.of(fromClassBytes(input.readAllBytes()));
        }
    }

    /**
     * Reads metadata from {@code module-info.class} bytes.
     *
     * @param bytes the class-file bytes
     * @return the module runtime metadata
     * @throws IllegalArgumentException if the bytes are not a valid class file
     */
    public static ModuleRuntimeMetadata fromClassBytes(byte[] bytes) {
        var model = ClassFile.of().parse(bytes);
        int release = ClassFileFormatVersion.fromMajor(model.majorVersion())
                .runtimeVersion()
                .feature();
        return new ModuleRuntimeMetadata(release, model.minorVersion() == 0xFFFF);
    }
}
