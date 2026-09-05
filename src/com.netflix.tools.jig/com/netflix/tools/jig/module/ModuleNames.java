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

import java.util.regex.Pattern;

/**
 * Utilities for module name normalization and derivation from Maven coordinates.
 */
final class ModuleNames {

    private ModuleNames() {}

    /**
     * Normalizes a candidate module name by replacing non-alphanumerics with dots,
     * collapsing repeated dots, and trimming leading/trailing dots.
     */
    static String normalize(String name) {
        name = NON_ALPHANUM.matcher(name).replaceAll(".");
        name = REPEATING_DOTS.matcher(name).replaceAll(".");
        name = LEADING_DOTS.matcher(name).replaceAll("");
        return TRAILING_DOTS.matcher(name).replaceAll("");
    }

    /**
     * Collapses {@code <alpha>-<digits>} sequences into {@code <alpha><digits>},
     * preserving numeric suffixes that are part of an artifact's identity rather
     * than a version. For example, {@code jsr-275} becomes {@code jsr275}.
     */
    static String collapseNumericSuffixes(String name) {
        return DASH_NUMERIC.matcher(name).replaceAll("$1$2");
    }

    private static final Pattern DASH_NUMERIC = Pattern.compile("([A-Za-z])-(\\d)");
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern REPEATING_DOTS = Pattern.compile("(\\.)(\\1)+");
    private static final Pattern LEADING_DOTS = Pattern.compile("^\\.");
    private static final Pattern TRAILING_DOTS = Pattern.compile("\\.$");
}
