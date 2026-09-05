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

import java.lang.reflect.Field;

import com.netflix.module.compile.ModuleCompiler;

public final class ModuleCompilerMeasurements {
    private static final Field COMPILATIONS = field("compilations");
    private static final Field PARSED_SOURCES = field("parsedSources");

    private ModuleCompilerMeasurements() {}

    public static long compilations(ModuleCompiler compiler) {
        return value(COMPILATIONS, compiler);
    }

    public static long parsedSources(ModuleCompiler compiler) {
        return value(PARSED_SOURCES, compiler);
    }

    private static Field field(String name) {
        try {
            var field = ModuleCompiler.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static long value(Field field, ModuleCompiler compiler) {
        try {
            return field.getLong(compiler);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }
}
