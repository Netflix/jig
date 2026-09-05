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

import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;

/** Maps Java module requirements to their closest Maven dependency semantics. */
public final class MavenDependency {
    private MavenDependency() {}

    /**
     * Returns whether a requirement should be optional in a consumer POM.
     *
     * <p>Maven cannot express a dependency that is transitive at compile time
     * but optional at run time. A plain {@code requires static} is therefore
     * optional, while {@code requires static transitive} remains non-optional
     * so downstream compilation continues to work.
     */
    public static boolean isOptional(Requires requirement) {
        var modifiers = requirement.modifiers();
        return modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.TRANSITIVE);
    }
}
