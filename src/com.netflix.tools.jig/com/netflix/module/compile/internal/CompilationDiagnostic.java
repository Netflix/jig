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

package com.netflix.module.compile.internal;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.netflix.module.compile.ModulePathEntry;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;
import com.netflix.module.compile.internal.SourceModuleCompilation.SystemImage;

/** A diagnostic and the source compilations that establish its validity. */
public record CompilationDiagnostic(Set<SourcePath> compilationSources, StoredDiagnostic diagnostic) {
    public CompilationDiagnostic {
        var sources = new TreeSet<>(Objects.requireNonNull(compilationSources, "compilation sources"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Compilation sources are empty");
        }
        compilationSources = Collections.unmodifiableNavigableSet(sources);
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /* Bootstrap against the preceding compilation state shape. */
    @SuppressWarnings("unchecked")
    static List<CompilationDiagnostic> diagnostics(State state) {
        Objects.requireNonNull(state, "state");
        try {
            return (List<CompilationDiagnostic>) State.class.getMethod("diagnostics").invoke(state);
        } catch (NoSuchMethodException bootstrap) {
            return List.of();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read compilation diagnostics", failure);
        }
    }

    static State state(Map<SourcePath, SourceCompilation> sources, List<ModulePathEntry> modulePathEntries, Map<String, ContentHash> resources,
                       ContentHash moduleInfoOptionsHash, SystemImage systemImage, List<CompilationDiagnostic> diagnostics) {
        try {
            var constructor = State.class.getConstructor(Map.class, List.class, Map.class, ContentHash.class, SystemImage.class, List.class);
            return constructor.newInstance(sources, modulePathEntries, resources, moduleInfoOptionsHash, systemImage, diagnostics);
        } catch (NoSuchMethodException bootstrap) {
            return new State(sources, modulePathEntries, resources, moduleInfoOptionsHash, systemImage);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct compilation state", failure);
        }
    }
}
