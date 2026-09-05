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

package com.netflix.tools.jig.module;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.List;

import com.netflix.module.ModuleRuntimeAccessOptions;

/** Parsed source-module declaration and build settings. */
public record SourceModule(ModuleDescriptor descriptor, Integer release, boolean preview,
        List<String> processors, ModuleRuntimeAccessOptions runtimeAccessOptions, Path sourceDirectory) {

    public SourceModule {
        if (descriptor == null) {
            throw new NullPointerException("descriptor");
        }
        if (release != null && release < 9) {
            throw new IllegalArgumentException("Invalid module release: " + release);
        }
        processors = List.copyOf(processors);
        if (runtimeAccessOptions == null) {
            throw new NullPointerException("runtimeAccessOptions");
        }
        if (sourceDirectory == null) {
            throw new NullPointerException("sourceDirectory");
        }
    }

    public String name() {
        return descriptor.name();
    }
}
