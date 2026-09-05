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

package com.netflix.module.compile;

import java.util.Comparator;
import java.util.Objects;
import javax.lang.model.SourceVersion;
import javax.tools.StandardLocation;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;

/**
 * Compilation identity of a module supplied to a javac module-path location.
 * Binary modules normally use their content identity; source modules can use a
 * coarser compile-time API identity.
 *
 * @param moduleName the Java module name
 * @param location the javac module-path location
 * @param hash the module or patch compilation identity
 */
public record ModulePathEntry(String moduleName, StandardLocation location, ModuleHash hash) implements Comparable<ModulePathEntry> {

    private static final Comparator<ModulePathEntry> ORDER = Comparator.comparing(ModulePathEntry::location)
            .thenComparing(ModulePathEntry::moduleName)
            .thenComparing(entry -> entry.hash().toString());

    /** Validates and creates a module-path entry. */
    public ModulePathEntry {
        Objects.requireNonNull(moduleName, "moduleName");
        if (!SourceVersion.isName(moduleName)) {
            throw new IllegalArgumentException("Invalid module name: " + moduleName);
        }
        Objects.requireNonNull(location, "location");
        if (location != StandardLocation.MODULE_PATH && location != StandardLocation.UPGRADE_MODULE_PATH && location != StandardLocation.PATCH_MODULE_PATH) {
            throw new IllegalArgumentException("Not a module-path location: " + location);
        }
        Objects.requireNonNull(hash, "hash");
        var requiredType = location == StandardLocation.PATCH_MODULE_PATH ? Type.PATCH : Type.MODULE;
        if (hash.type() != requiredType) {
            throw new IllegalArgumentException(location + " requires a " + requiredType + " hash: " + moduleName);
        }
    }

    @Override
    public int compareTo(ModulePathEntry other) {
        return ORDER.compare(this, other);
    }
}
