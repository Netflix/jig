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

package com.netflix.module;

import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import javax.lang.model.SourceVersion;

/** Fully qualified runtime access options authorized by a source module. */
public final class ModuleRuntimeAccessOptions {

    /**
     * A qualified package export or open operation.
     *
     * @param sourceModule the module containing the package
     * @param packageName the qualified package name
     * @param targetModule the module receiving access
     */
    public record PackageAccess(String sourceModule, String packageName, String targetModule) {
        /** Validates and creates a package access operation. */
        public PackageAccess {
            requireModuleName(sourceModule);
            Objects.requireNonNull(packageName, "packageName");
            if (!SourceVersion.isName(packageName)) {
                throw new IllegalArgumentException("Invalid package name: " + packageName);
            }
            requireModuleName(targetModule);
        }

        /**
         * Returns the operand accepted by {@code --add-exports} or {@code --add-opens}.
         *
         * @return the launcher option operand
         */
        public String toFlagValue() {
            return sourceModule + "/" + packageName + "=" + targetModule;
        }

        ModuleDesc sourceModuleDesc() {
            return ModuleDesc.of(sourceModule);
        }

        PackageDesc packageDesc() {
            return PackageDesc.of(packageName);
        }

        ModuleDesc targetModuleDesc() {
            return ModuleDesc.of(targetModule);
        }
    }

    /** An option set containing no runtime access. */
    public static final ModuleRuntimeAccessOptions EMPTY = newBuilder().build();

    private final List<String> enableNativeAccess;
    private final List<String> enableFinalFieldMutation;
    private final List<PackageAccess> addExports;
    private final List<PackageAccess> addOpens;

    private ModuleRuntimeAccessOptions(Builder builder) {
        enableNativeAccess = List.copyOf(builder.enableNativeAccess);
        enableFinalFieldMutation = List.copyOf(builder.enableFinalFieldMutation);
        addExports = List.copyOf(builder.addExports);
        addOpens = List.copyOf(builder.addOpens);
    }

    /**
     * Returns modules authorized for native access.
     *
     * @return an immutable list of module names
     */
    public List<String> enableNativeAccess() {
        return enableNativeAccess;
    }

    /**
     * Returns modules authorized to mutate final fields.
     *
     * @return an immutable list of module names
     */
    public List<String> enableFinalFieldMutation() {
        return enableFinalFieldMutation;
    }

    /**
     * Returns qualified package exports.
     *
     * @return an immutable list of exports
     */
    public List<PackageAccess> addExports() {
        return addExports;
    }

    /**
     * Returns qualified package opens.
     *
     * @return an immutable list of opens
     */
    public List<PackageAccess> addOpens() {
        return addOpens;
    }

    /**
     * Tests whether this set authorizes no runtime access.
     *
     * @return {@code true} if every option list is empty
     */
    public boolean isEmpty() {
        return enableNativeAccess.isEmpty()
                && enableFinalFieldMutation.isEmpty()
                && addExports.isEmpty()
                && addOpens.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModuleRuntimeAccessOptions that
                && enableNativeAccess.equals(that.enableNativeAccess)
                && enableFinalFieldMutation.equals(that.enableFinalFieldMutation)
                && addExports.equals(that.addExports)
                && addOpens.equals(that.addOpens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enableNativeAccess, enableFinalFieldMutation, addExports, addOpens);
    }

    @Override
    public String toString() {
        var result = new StringJoiner(", ", "ModuleRuntimeAccessOptions[", "]");
        if (!enableNativeAccess.isEmpty()) {
            result.add("enableNativeAccess=" + enableNativeAccess);
        }
        if (!enableFinalFieldMutation.isEmpty()) {
            result.add("enableFinalFieldMutation=" + enableFinalFieldMutation);
        }
        if (!addExports.isEmpty()) {
            result.add("addExports=" + addExports);
        }
        if (!addOpens.isEmpty()) {
            result.add("addOpens=" + addOpens);
        }
        return result.toString();
    }

    /**
     * Creates an empty option builder.
     *
     * @return a new builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    private static void requireModuleName(String moduleName) {
        Objects.requireNonNull(moduleName, "moduleName");
        if (!SourceVersion.isName(moduleName)) {
            throw new IllegalArgumentException("Invalid module name: " + moduleName);
        }
    }

    /** Builds immutable runtime access option sets. */
    public static final class Builder {
        private final Set<String> enableNativeAccess = new LinkedHashSet<>();
        private final Set<String> enableFinalFieldMutation = new LinkedHashSet<>();
        private final Set<PackageAccess> addExports = new LinkedHashSet<>();
        private final Set<PackageAccess> addOpens = new LinkedHashSet<>();

        private Builder() {}

        /**
         * Authorizes native access for a module.
         *
         * @param moduleName the module name
         * @return this builder
         */
        public Builder enableNativeAccess(String moduleName) {
            requireModuleName(moduleName);
            enableNativeAccess.add(moduleName);
            return this;
        }

        /**
         * Authorizes final-field mutation for a module.
         *
         * @param moduleName the module name
         * @return this builder
         */
        public Builder enableFinalFieldMutation(String moduleName) {
            requireModuleName(moduleName);
            enableFinalFieldMutation.add(moduleName);
            return this;
        }

        /**
         * Adds a qualified package export.
         *
         * @param sourceModule the module containing the package
         * @param packageName the qualified package name
         * @param targetModule the module receiving access
         * @return this builder
         */
        public Builder addExports(String sourceModule, String packageName, String targetModule) {
            addExports.add(new PackageAccess(sourceModule, packageName, targetModule));
            return this;
        }

        /**
         * Adds a qualified package open operation.
         *
         * @param sourceModule the module containing the package
         * @param packageName the qualified package name
         * @param targetModule the module receiving access
         * @return this builder
         */
        public Builder addOpens(String sourceModule, String packageName, String targetModule) {
            addOpens.add(new PackageAccess(sourceModule, packageName, targetModule));
            return this;
        }

        /**
         * Creates an immutable option set.
         *
         * @return the runtime access options
         */
        public ModuleRuntimeAccessOptions build() {
            return new ModuleRuntimeAccessOptions(this);
        }
    }
}
