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

import java.util.Objects;

/** A normalized source path relative to a compilation source root. */
public record SourcePath(String value) implements Comparable<SourcePath> {
    public SourcePath {
        validate(value);
        if (!value.endsWith(".java")) {
            throw new IllegalArgumentException("Source path is not a Java source: " + value);
        }
    }

    @Override
    public int compareTo(SourcePath other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    static void validate(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.startsWith("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Invalid relative path: " + value);
        }
        for (var component : value.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("Invalid relative path: " + value);
            }
        }
    }
}
