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

import java.util.List;

import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.ModuleRuntimeAccessOptions.PackageAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRuntimeAccessOptionsTest {

    @Test
    void emptyHasNoRuntimeAccessOptions() {
        var options = ModuleRuntimeAccessOptions.EMPTY;

        assertTrue(options.enableNativeAccess()
                          .isEmpty());
        assertTrue(options.enableFinalFieldMutation()
                          .isEmpty());
        assertTrue(options.addExports()
                          .isEmpty());
        assertTrue(options.addOpens()
                          .isEmpty());
    }

    @Test
    void optionsAreFullyQualified() {
        var options = ModuleRuntimeAccessOptions.newBuilder()
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
    void duplicateOptionsAreDeduplicated() {
        var options = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("com.example.app")
                .enableNativeAccess("com.example.app")
                .addOpens("java.base", "java.lang", "com.example.app")
                .addOpens("java.base", "java.lang", "com.example.app")
                .build();

        assertEquals(1, options.enableNativeAccess()
                               .size());
        assertEquals(1, options.addOpens()
                               .size());
    }

    @Test
    void packageAccessValidatesNames() {
        assertThrows(NullPointerException.class, () -> new PackageAccess(null, "java.lang", "com.example.app"));
        assertThrows(NullPointerException.class, () -> new PackageAccess("java.base", null, "com.example.app"));
        assertThrows(NullPointerException.class, () -> new PackageAccess("java.base", "java.lang", null));
        assertThrows(IllegalArgumentException.class, () -> new PackageAccess("not/a/module", "java.lang", "com.example.app"));
        assertThrows(IllegalArgumentException.class, () -> new PackageAccess("java.base", "not/a/package", "com.example.app"));
        assertThrows(IllegalArgumentException.class, () -> new PackageAccess("java.base", "java.lang", "ALL-UNNAMED"));
    }

    @Test
    void listsAreImmutable() {
        var options = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("com.example.app")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> options.enableNativeAccess().add("com.example.other"));
    }
}
