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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Facts recorded while compiling one Java source file. */
public record SourceCompilation(SourcePath source, ContentHash sourceHash, ContentHash declarationHash,
        Set<String> generatedClasses, Map<String, ContentHash> systemClasses) {

    public SourceCompilation(SourcePath source, ContentHash sourceHash, ContentHash declarationHash,
            Set<String> generatedClasses) {
        this(source, sourceHash, declarationHash, generatedClasses, Map.of());
    }

    public SourceCompilation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceHash, "sourceHash");
        Objects.requireNonNull(declarationHash, "declarationHash");
        var classes = new TreeSet<String>();
        Objects.requireNonNull(generatedClasses, "generated classes").forEach(name -> classes.add(requireClassName(name)));
        generatedClasses = Collections.unmodifiableNavigableSet(classes);
        systemClasses = Collections.unmodifiableNavigableMap(new TreeMap<>(Objects.requireNonNull(systemClasses, "system classes")));
    }

    /** An inexpensive observation used only to select incremental work. */
    public record SourceContent(ContentHash hash) {
        public SourceContent {
            Objects.requireNonNull(hash, "hash");
        }
    }

    private static String requireClassName(String name) {
        Objects.requireNonNull(name, "class name");
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Invalid binary class name: " + name);
        }
        return name;
    }
}
