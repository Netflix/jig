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

import java.util.List;
import javax.tools.StandardLocation;

import com.netflix.module.compile.ModulePathEntry;

/** Normalizes physical module paths to their effective compilation identities. */
public final class ModulePathCompilation {
    private ModulePathCompilation() {}

    public static List<ModulePathEntry> entries(List<ModulePathEntry> entries) {
        return entries.stream()
                .filter(entry -> !redundantSourcePatch(entry, entries))
                .toList();
    }

    private static boolean redundantSourcePatch(ModulePathEntry candidate, List<ModulePathEntry> entries) {
        if (candidate.location() != StandardLocation.PATCH_MODULE_PATH || !candidate.hash()
                .algorithm()
                .equals(CompiledModuleHash.COMPILATION_ALGORITHM)) {
            return false;
        }
        return entries.stream().anyMatch(entry ->
                entry.location() != StandardLocation.PATCH_MODULE_PATH
                        && entry.moduleName().equals(candidate.moduleName())
                        && entry.hash()
                                .algorithm()
                                .equals(candidate.hash()
                                                 .algorithm())
                        && entry.hash()
                                .digest()
                                .equals(candidate.hash()
                                                 .digest()));
    }
}
