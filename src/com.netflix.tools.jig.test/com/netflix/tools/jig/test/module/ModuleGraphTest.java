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

package com.netflix.tools.jig.test.module;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.netflix.tools.jig.module.ModuleGraph;
import com.netflix.tools.jig.module.ModuleGraph.Module;
import com.netflix.tools.jig.module.ModuleGraph.Requirement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleGraphTest {

    @Test
    void selectsTheNearestVersionBeforeAnEarlierFartherRequirement() throws Exception {
        var modules = modules(module("first", "1", requirement("middle", "1")), module("second", "1", requirement("shared", "2")), module("middle", "1", requirement("shared", "1")),
                module("shared", "1"), module("shared", "2"));

        var result = ModuleGraph.resolve(
                List.of(requirement("first", "1"), requirement("second", "1")),
                false,
                (name, version) -> modules.get(name + "@" + version));

        assertEquals(List.of("first", "second", "middle", "shared"),
                result.stream()
                        .map(Module::name)
                        .toList());
        assertEquals("2", version(result, "shared"));
    }

    @Test
    void selectsTheFirstVersionAtTheSameDepth() throws Exception {
        var modules = modules(module("first", "1", requirement("shared", "1")), module("second", "1", requirement("shared", "2")),
                module("shared", "1"), module("shared", "2"));

        var result = ModuleGraph.resolve(
                List.of(requirement("first", "1"), requirement("second", "1")),
                false,
                (name, version) -> modules.get(name + "@" + version));

        assertEquals("1", version(result, "shared"));
    }

    @Test
    void resolvesDependenciesRecordedForAutomaticModules() throws Exception {
        var automatic = module(true, "automatic", "1", requirement("explicit", "2"));
        var modules = modules(automatic, module("explicit", "2"));

        var result = ModuleGraph.resolve(List.of(requirement("automatic", "1")), false,
                (name, version) -> modules.get(name + "@" + version));

        assertTrue(automatic.reference().descriptor().requires().stream()
                .noneMatch(requirement -> requirement.name().equals("explicit")));
        assertEquals(List.of("automatic", "explicit"),
                result.stream()
                        .map(Module::name)
                        .toList());
    }

    @Test
    void doesNotExpandAFlattenedRequirement() throws Exception {
        var root = module("root", "1", new Requirement("included", "1", false, false));
        var modules = modules(root, module("included", "1", requirement("excluded", "1")), module("excluded", "1"));

        var result = ModuleGraph.resolve(List.of(requirement("root", "1")), false,
                (name, version) -> modules.get(name + "@" + version));

        assertEquals(List.of("root", "included"),
                result.stream()
                        .map(Module::name)
                        .toList());
    }

    @Test
    void includesStaticDependenciesOnlyWhenRequested() throws Exception {
        var root = module("root", "1", new Requirement("optional", "1", true));
        var modules = modules(root, module("optional", "1"));

        var runtime = ModuleGraph.resolve(List.of(requirement("root", "1")), false,
                (name, version) -> modules.get(name + "@" + version));
        var compile = ModuleGraph.resolve(List.of(requirement("root", "1")), true,
                (name, version) -> modules.get(name + "@" + version));

        assertEquals(List.of("root"),
                runtime.stream()
                        .map(Module::name)
                        .toList());
        assertEquals(List.of("root", "optional"),
                compile.stream()
                        .map(Module::name)
                        .toList());
    }

    private static String version(List<Module> modules, String name) {
        return modules.stream()
                .filter(module -> module.name().equals(name))
                .findFirst()
                .orElseThrow()
                .version();
    }

    private static Map<String, Module> modules(Module... modules) {
        var result = new LinkedHashMap<String, Module>();
        for (var module : modules) {
            result.put(module.name() + "@" + module.version(), module);
        }
        return result;
    }

    private static Requirement requirement(String name, String version) {
        return new Requirement(name, version, false);
    }

    private static Module module(String name, String version, Requirement... requirements) {
        return module(false, name, version, requirements);
    }

    private static Module module(boolean automatic, String name, String version,
            Requirement... requirements) {
        var builder = automatic ? ModuleDescriptor.newAutomaticModule(name) : ModuleDescriptor.newModule(name);
        var descriptor = builder.version(version).build();
        var reference = new ModuleReference(descriptor, null) {
            @Override
            public ModuleReader open() throws IOException {
                throw new IOException("No content");
            }
        };
        return new Module(name, version, reference, List.of(requirements));
    }
}
