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

import java.lang.classfile.AttributeMapper;
import java.lang.classfile.AttributeMapper.AttributeStability;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.BufWriter;
import java.lang.classfile.ClassFile.AttributeMapperOption;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassReader;
import java.lang.classfile.CustomAttribute;
import java.lang.classfile.constantpool.ModuleEntry;
import java.lang.classfile.constantpool.PackageEntry;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import com.netflix.module.ModuleRuntimeAccessOptions.PackageAccess;

/**
 * A module's fully qualified runtime access requirements.
 *
 * <pre>{@code
 * ModuleRuntimeAccess_attribute {
 *     u2 attribute_name_index;
 *     u4 attribute_length;
 *     u2 native_access_count;
 *     u2 native_access_index[native_access_count];
 *     u2 final_field_mutation_count;
 *     u2 final_field_mutation_index[final_field_mutation_count];
 *     u2 add_exports_count;
 *     {
 *         u2 source_module_index;
 *         u2 package_index;
 *         u2 target_count;
 *         u2 target_index[target_count];
 *     } add_exports[add_exports_count];
 *     u2 add_opens_count;
 *     {
 *         u2 source_module_index;
 *         u2 package_index;
 *         u2 target_count;
 *         u2 target_index[target_count];
 *     } add_opens[add_opens_count];
 * }
 * }</pre>
 *
 * <p>Module indexes refer to {@code CONSTANT_Module_info}; package indexes
 * refer to {@code CONSTANT_Package_info}. The attribute is valid only on
 * {@code module-info.class}.
 */
public final class ModuleRuntimeAccessAttribute extends CustomAttribute<ModuleRuntimeAccessAttribute> {

    static final String ATTRIBUTE_NAME = "com.netflix.module.ModuleRuntimeAccess";

    private final ModuleRuntimeAccessOptions options;

    private ModuleRuntimeAccessAttribute(ModuleRuntimeAccessOptions options) {
        super(MAPPER);
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Creates an attribute containing runtime access requirements.
     *
     * @param options the requirements to encode
     * @return the attribute
     */
    public static ModuleRuntimeAccessAttribute of(ModuleRuntimeAccessOptions options) {
        return new ModuleRuntimeAccessAttribute(options);
    }

    /**
     * Returns the requirements stored in this attribute.
     *
     * @return the runtime access options
     */
    public ModuleRuntimeAccessOptions options() {
        return options;
    }

    private record PackageKey(String sourceModule, String packageName) {}

    private static final AttributeMapper<ModuleRuntimeAccessAttribute> MAPPER = new AttributeMapper<>() {
        @Override
        public String name() {
            return ATTRIBUTE_NAME;
        }

        @Override
        public ModuleRuntimeAccessAttribute readAttribute(AttributedElement enclosing, ClassReader reader, int position) {
            if (!(enclosing instanceof ClassModel model) || !model.flags().has(AccessFlag.MODULE)) {
                throw new IllegalArgumentException(ATTRIBUTE_NAME + " is only valid on module-info.class");
            }
            int length = reader.readInt(position - 4);
            if (length < 8 || length > Integer.MAX_VALUE - position) {
                throw invalidLength(length);
            }
            int end = position + length;
            var builder = ModuleRuntimeAccessOptions.newBuilder();
            int offset = position;

            int nativeCount = readCount(reader, offset, end);
            offset += 2;
            for (int i = 0; i < nativeCount; i++) {
                requireRemaining(offset, 2, end);
                builder.enableNativeAccess(moduleName(reader, offset));
                offset += 2;
            }

            int finalFieldCount = readCount(reader, offset, end);
            offset += 2;
            for (int i = 0; i < finalFieldCount; i++) {
                requireRemaining(offset, 2, end);
                builder.enableFinalFieldMutation(moduleName(reader, offset));
                offset += 2;
            }

            int exportsCount = readCount(reader, offset, end);
            offset += 2;
            for (int i = 0; i < exportsCount; i++) {
                requireRemaining(offset, 6, end);
                String sourceModule = moduleName(reader, offset);
                String packageName = packageName(reader, offset + 2);
                int targetCount = reader.readU2(offset + 4);
                offset += 6;
                if (targetCount == 0) {
                    throw invalidLength(length);
                }
                for (int target = 0; target < targetCount; target++) {
                    requireRemaining(offset, 2, end);
                    builder.addExports(sourceModule, packageName, moduleName(reader, offset));
                    offset += 2;
                }
            }

            int opensCount = readCount(reader, offset, end);
            offset += 2;
            for (int i = 0; i < opensCount; i++) {
                requireRemaining(offset, 6, end);
                String sourceModule = moduleName(reader, offset);
                String packageName = packageName(reader, offset + 2);
                int targetCount = reader.readU2(offset + 4);
                offset += 6;
                if (targetCount == 0) {
                    throw invalidLength(length);
                }
                for (int target = 0; target < targetCount; target++) {
                    requireRemaining(offset, 2, end);
                    builder.addOpens(sourceModule, packageName, moduleName(reader, offset));
                    offset += 2;
                }
            }

            if (offset != end) {
                throw invalidLength(length);
            }
            return of(builder.build());
        }

        @Override
        public void writeAttribute(BufWriter writer, ModuleRuntimeAccessAttribute attribute) {
            var options = attribute.options();
            var exports = group(options.addExports());
            var opens = group(options.addOpens());
            writer.writeIndex(attribute.attributeName());
            writer.writeInt(attributeLength(options, exports, opens));
            writeModules(writer, options.enableNativeAccess());
            writeModules(writer, options.enableFinalFieldMutation());
            writePackageAccess(writer, exports);
            writePackageAccess(writer, opens);
        }

        @Override
        public AttributeStability stability() {
            return AttributeStability.CP_REFS;
        }
    };

    /**
     * Returns the Class-File API mapper for this attribute.
     *
     * @return the attribute mapper
     */
    public static AttributeMapper<ModuleRuntimeAccessAttribute> mapper() {
        return MAPPER;
    }

    /**
     * Returns a Class-File API option which recognizes this attribute.
     *
     * @return the attribute mapper option
     */
    public static AttributeMapperOption mapperOption() {
        return AttributeMapperOption.of(name -> name.equalsString(ATTRIBUTE_NAME) ? MAPPER : null);
    }

    private static int readCount(ClassReader reader, int offset, int end) {
        requireRemaining(offset, 2, end);
        return reader.readU2(offset);
    }

    private static void requireRemaining(int offset, int size, int end) {
        if (offset < 0 || size > end - offset) {
            throw new IllegalArgumentException("Truncated " + ATTRIBUTE_NAME + " attribute");
        }
    }

    private static IllegalArgumentException invalidLength(int length) {
        return new IllegalArgumentException("Invalid " + ATTRIBUTE_NAME + " attribute length: " + length);
    }

    private static String moduleName(ClassReader reader, int offset) {
        return reader.readEntry(offset, ModuleEntry.class)
                     .asSymbol()
                     .name();
    }

    private static String packageName(ClassReader reader, int offset) {
        return reader.readEntry(offset, PackageEntry.class)
                     .asSymbol()
                     .name();
    }

    private static LinkedHashMap<PackageKey, List<String>> group(List<PackageAccess> accesses) {
        var grouped = new LinkedHashMap<PackageKey, List<String>>();
        for (var access : accesses) {
            var key = new PackageKey(access.sourceModule(), access.packageName());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(access.targetModule());
        }
        return grouped;
    }

    private static int attributeLength(ModuleRuntimeAccessOptions options, LinkedHashMap<PackageKey, List<String>> exports,
            LinkedHashMap<PackageKey, List<String>> opens) {
        return 8
                + 2 * options.enableNativeAccess().size()
                + 2 * options.enableFinalFieldMutation().size()
                + packageAccessLength(exports)
                + packageAccessLength(opens);
    }

    private static int packageAccessLength(LinkedHashMap<PackageKey, List<String>> accesses) {
        return accesses.values().stream()
                .mapToInt(targets -> 6 + 2 * targets.size())
                .sum();
    }

    private static void writeModules(BufWriter writer, List<String> modules) {
        writer.writeU2(modules.size());
        for (String module : modules) {
            writer.writeIndex(writer.constantPool()
                                    .moduleEntry(ModuleDesc.of(module)));
        }
    }

    private static void writePackageAccess(BufWriter writer, LinkedHashMap<PackageKey, List<String>> accesses) {
        writer.writeU2(accesses.size());
        for (var entry : accesses.entrySet()) {
            writer.writeIndex(writer.constantPool()
                                    .moduleEntry(ModuleDesc.of(entry.getKey()
                                            .sourceModule())));
            writer.writeIndex(writer.constantPool()
                                    .packageEntry(PackageDesc.of(entry.getKey()
                                            .packageName())));
            writeModules(writer, entry.getValue());
        }
    }
}
