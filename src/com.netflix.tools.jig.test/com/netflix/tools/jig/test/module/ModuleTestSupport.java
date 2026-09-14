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

import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.module.ModuleReference;
import java.util.List;
import java.util.Set;

import com.netflix.tools.jig.module.ModuleRepositorySession;

final class ModuleTestSupport {

    private ModuleTestSupport() {}

    static ModuleReference resolve(ModuleRepositorySession session, String moduleName, String version) {
        var root = ModuleDescriptor.newModule("_test_root")
                .requires(Set.of(), moduleName, Version.parse(version))
                .build();
        return session.resolveModules(List.of(root), false)
                      .observableModules()
                      .find(moduleName)
                      .orElseThrow(() -> new FindException("Module not found: " + moduleName + "@" + version));
    }
}
