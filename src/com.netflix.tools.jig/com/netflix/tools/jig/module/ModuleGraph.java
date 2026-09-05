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

import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleReference;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Selects an ordered graph of exact modules, with the nearest version winning.
 */
public final class ModuleGraph {

    public record Requirement(String name, String version, boolean staticPhase,
            boolean transitive) {
        public Requirement(String name, String version, boolean staticPhase) {
            this(name, version, staticPhase, true);
        }

        public Requirement {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Empty module name");
            }
            if (version.isBlank()) {
                throw new IllegalArgumentException("Empty module version");
            }
        }
    }

    public record Module(String name, String version, ModuleReference reference,
                         List<Requirement> requirements) {
        public Module {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(reference, "reference");
            requirements = List.copyOf(requirements);
            if (!reference.descriptor()
                          .name()
                          .equals(name)) {
                throw new IllegalArgumentException("Module reference provides " + reference.descriptor().name() + ", not " + name);
            }
        }
    }

    @FunctionalInterface
    public interface Loader {
        Module load(String name, String version) throws IOException;
    }

    private ModuleGraph() {}

    public static List<Module> resolve(Collection<Requirement> roots, boolean includeStatics, Loader loader) throws IOException {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(loader, "loader");
        var queue = new ArrayDeque<>(roots);
        var selected = new LinkedHashMap<String, Module>();
        var expanded = new HashSet<String>();
        while (!queue.isEmpty()) {
            var requirement = queue.removeFirst();
            if (requirement.staticPhase() && !includeStatics) {
                continue;
            }
            var previous = selected.get(requirement.name());
            if (previous != null) {
                if (!previous.version().equals(requirement.version())) {
                    Trace.trace("mediate %s@%s -> %s@%s", requirement.name(), requirement.version(),
                            previous.name(), previous.version());
                } else if (requirement.transitive() && expanded.add(requirement.name())) {
                    queue.addAll(previous.requirements());
                }
                continue;
            }
            var module = loader.load(requirement.name(), requirement.version());
            if (module == null) {
                throw new FindException("Module not found: " + requirement.name() + "@" + requirement.version());
            }
            if (!module.name().equals(requirement.name()) || !module.version().equals(requirement.version())) {
                throw new FindException("Requested "
                        + requirement.name()
                        + "@"
                        + requirement.version()
                        + " but loader returned "
                        + module.name()
                        + "@"
                        + module.version());
            }
            selected.put(module.name(), module);
            if (requirement.transitive()) {
                expanded.add(module.name());
                queue.addAll(module.requirements());
            }
        }

        return List.copyOf(selected.values());
    }
}
