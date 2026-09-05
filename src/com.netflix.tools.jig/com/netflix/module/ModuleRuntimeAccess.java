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

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.netflix.module.ModuleRuntimeAccessOptions.Builder;

/** Reads, writes, combines, and validates module runtime access requirements. */
public final class ModuleRuntimeAccess {
    private enum RuntimeOption {
        ENABLE_NATIVE_ACCESS("--enable-native-access"),
        ENABLE_FINAL_FIELD_MUTATION("--enable-final-field-mutation"),
        ADD_EXPORTS("--add-exports"),
        ADD_OPENS("--add-opens");

        private final String argument;

        RuntimeOption(String argument) {
            this.argument = argument;
        }

        static RuntimeOption find(String argument) {
            for (var option : values()) {
                if (option.argument.equals(argument)) {
                    return option;
                }
            }
            return null;
        }
    }

    private enum PackageAccessKind {
        EXPORTS,
        OPENS
    }

    private ModuleRuntimeAccess() {}

    /**
     * Selects options whose access is granted to one target module.
     *
     * @param options the options to filter
     * @param moduleName the target module
     * @return the options applying to the target module
     */
    public static ModuleRuntimeAccessOptions forModule(ModuleRuntimeAccessOptions options, String moduleName) {
        var builder = ModuleRuntimeAccessOptions.newBuilder();
        if (options.enableNativeAccess().contains(moduleName)) {
            builder.enableNativeAccess(moduleName);
        }
        if (options.enableFinalFieldMutation().contains(moduleName)) {
            builder.enableFinalFieldMutation(moduleName);
        }
        options.addExports().stream()
                .filter(access -> access.targetModule().equals(moduleName))
                .forEach(access -> builder.addExports(access.sourceModule(), access.packageName(), access.targetModule()));
        options.addOpens().stream()
                .filter(access -> access.targetModule().equals(moduleName))
                .forEach(access -> builder.addOpens(access.sourceModule(), access.packageName(), access.targetModule()));
        return builder.build();
    }

    /**
     * Replaces the runtime access attribute in a module descriptor.
     *
     * @param moduleInfo the {@code module-info.class} bytes
     * @param options the requirements to record; an empty set removes the attribute
     * @return the transformed class-file bytes
     * @throws IllegalArgumentException if the bytes do not represent a module descriptor
     */
    public static byte[] write(byte[] moduleInfo, ModuleRuntimeAccessOptions options) {
        var classFile = ClassFile.of(ModuleRuntimeAccessAttribute.mapperOption());
        var model = classFile.parse(moduleInfo);
        if (!model.isModuleInfo()) {
            throw new IllegalArgumentException("Not a module-info.class");
        }
        ClassTransform removePrevious = ClassTransform.dropping(element -> element instanceof ModuleRuntimeAccessAttribute);
        var transform = options.isEmpty() ? removePrevious : removePrevious.andThen(ClassTransform.endHandler(builder -> builder.with(ModuleRuntimeAccessAttribute.of(options))));
        return classFile.transformClass(model, transform);
    }

    /**
     * Parses recognized runtime access options from launcher arguments.
     * Unrecognized arguments are ignored.
     *
     * @param arguments launcher arguments
     * @return the parsed runtime access options
     * @throws IllegalArgumentException if a recognized option has an invalid operand
     */
    public static ModuleRuntimeAccessOptions parseArguments(List<String> arguments) {
        var builder = ModuleRuntimeAccessOptions.newBuilder();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            var option = argument;
            String value = null;
            var equals = argument.indexOf('=');
            if (equals > 0) {
                option = argument.substring(0, equals);
                value = argument.substring(equals + 1);
            }
            var runtimeOption = RuntimeOption.find(option);
            if (runtimeOption == null) {
                continue;
            }
            if (value == null) {
                value = operand(arguments, ++i, option);
            }
            switch (runtimeOption) {
                case ENABLE_NATIVE_ACCESS -> splitModules(value).forEach(builder::enableNativeAccess);
                case ENABLE_FINAL_FIELD_MUTATION -> splitModules(value).forEach(builder::enableFinalFieldMutation);
                case ADD_EXPORTS -> addPackageAccess(builder, value, PackageAccessKind.EXPORTS);
                case ADD_OPENS -> addPackageAccess(builder, value, PackageAccessKind.OPENS);
            }
        }
        return builder.build();
    }

    /**
     * Resolves a modular launch and verifies recorded requirements against its arguments.
     *
     * @param arguments launcher arguments
     * @throws IllegalArgumentException if a runtime access requirement is unsatisfied
     * @throws IOException if a module descriptor cannot be read
     */
    public static void checkLaunch(List<String> arguments) throws IOException {
        var supplied = parseArguments(arguments);
        var modulePath = optionPaths(arguments, "--module-path");
        var upgradeModulePath = optionPaths(arguments, "--upgrade-module-path");
        if (modulePath.isEmpty() && upgradeModulePath.isEmpty()) {
            return;
        }

        var modules = ModuleFinder.of(modulePath.toArray(Path[]::new));
        var upgrades = ModuleFinder.of(upgradeModulePath.toArray(Path[]::new));
        var roots = launchRoots(arguments, modules, upgrades);
        if (roots.isEmpty()) {
            return;
        }
        var systemAndUpgrades = ModuleFinder.compose(upgrades, ModuleFinder.ofSystem());
        var configuration = Configuration.resolveAndBind(systemAndUpgrades, List.of(ModuleLayer.boot().configuration()),
                modules, roots);

        var requirements = ModuleRuntimeAccessOptions.newBuilder();
        for (var resolved : configuration.modules().stream()
                .sorted(Comparator.comparing(ResolvedModule::name))
                .toList()) {
            read(resolved.reference()).ifPresent(options -> add(requirements, options));
        }
        checkRequirements(requirements.build(), supplied);
    }

    /**
     * Verifies that supplied options include every required authorization.
     *
     * @param required the required runtime access
     * @param supplied the authorized runtime access
     * @throws IllegalArgumentException if an authorization is missing
     */
    public static void checkRequirements(ModuleRuntimeAccessOptions required, ModuleRuntimeAccessOptions supplied) {
        var missing = new LinkedHashSet<String>();
        collectMissing(required, supplied, missing);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Unsatisfied module runtime access requirements: " + String.join(", ", missing));
        }
    }

    /**
     * Adds all options to a builder.
     *
     * @param builder the destination builder
     * @param options the options to add
     */
    public static void add(Builder builder, ModuleRuntimeAccessOptions options) {
        options.enableNativeAccess().forEach(builder::enableNativeAccess);
        options.enableFinalFieldMutation().forEach(builder::enableFinalFieldMutation);
        options.addExports().forEach(access -> builder.addExports(access.sourceModule(), access.packageName(), access.targetModule()));
        options.addOpens().forEach(access -> builder.addOpens(access.sourceModule(), access.packageName(), access.targetModule()));
    }

    /**
     * Reads the runtime access attribute from a module descriptor.
     *
     * @param reference the module to inspect
     * @return the recorded options, or an empty value when no attribute is present
     * @throws IOException if an explicit module descriptor cannot be read
     */
    public static Optional<ModuleRuntimeAccessOptions> read(ModuleReference reference) throws IOException {
        if (reference.descriptor().isAutomatic()) {
            return Optional.empty();
        }
        try (var reader = reference.open();
             var input = reader.open("module-info.class").orElseThrow(() -> new IOException("Module " + reference.descriptor().name() + " has no module-info.class"))) {
            var model = ClassFile.of(ModuleRuntimeAccessAttribute.mapperOption()).parse(input.readAllBytes());
            return model.findAttribute(ModuleRuntimeAccessAttribute.mapper()).map(ModuleRuntimeAccessAttribute::options);
        }
    }

    private static void collectMissing(ModuleRuntimeAccessOptions required, ModuleRuntimeAccessOptions supplied, Set<String> missing) {
        required.enableNativeAccess().stream()
                .filter(module -> !supplied.enableNativeAccess().contains(module))
                .map(module -> "--enable-native-access=" + module)
                .forEach(missing::add);
        required.enableFinalFieldMutation().stream()
                .filter(module -> !supplied.enableFinalFieldMutation().contains(module))
                .map(module -> "--enable-final-field-mutation=" + module)
                .forEach(missing::add);
        required.addExports().stream()
                .filter(access -> !supplied.addExports().contains(access))
                .map(access -> "--add-exports=" + access.toFlagValue())
                .forEach(missing::add);
        required.addOpens().stream()
                .filter(access -> !supplied.addOpens().contains(access))
                .map(access -> "--add-opens=" + access.toFlagValue())
                .forEach(missing::add);
    }

    private static Set<String> launchRoots(List<String> arguments, ModuleFinder modules, ModuleFinder upgrades) {
        var roots = new LinkedHashSet<String>();
        for (int i = 0; i < arguments.size(); i++) {
            var option = arguments.get(i);
            String value = null;
            if ((option.equals("--module") || option.equals("--add-modules")) && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (option.startsWith("--module=")) {
                value = option.substring("--module=".length());
            } else if (option.startsWith("--add-modules=")) {
                value = option.substring("--add-modules=".length());
            }
            if (value == null) {
                continue;
            }
            var slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            for (var module : value.split(",")) {
                if (module.equals("ALL-MODULE-PATH")) {
                    modules.findAll().stream()
                            .map(reference -> reference.descriptor().name())
                            .forEach(roots::add);
                    upgrades.findAll().stream()
                            .map(reference -> reference.descriptor().name())
                            .forEach(roots::add);
                } else if (!module.startsWith("ALL-")) {
                    roots.add(module);
                }
            }
        }
        return Set.copyOf(roots);
    }

    private static List<Path> optionPaths(List<String> arguments, String selectedOption) {
        var paths = new ArrayList<Path>();
        var separator = Pattern.quote(System.getProperty("path.separator"));
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            String value = null;
            if (argument.equals(selectedOption) && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (argument.startsWith(selectedOption + "=")) {
                value = argument.substring(selectedOption.length() + 1);
            }
            if (value == null) {
                continue;
            }
            for (var path : value.split(separator)) {
                if (!path.isEmpty()) {
                    paths.add(Path.of(path));
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void addPackageAccess(Builder builder, String value, PackageAccessKind kind) {
        var slash = value.indexOf('/');
        var equals = value.indexOf('=', slash + 1);
        if (slash <= 0 || equals <= slash + 1 || equals == value.length() - 1) {
            throw new IllegalArgumentException("Invalid module package access: " + value);
        }
        var source = value.substring(0, slash);
        var packageName = value.substring(slash + 1, equals);
        for (var target : splitModules(value.substring(equals + 1))) {
            switch (kind) {
                case EXPORTS -> builder.addExports(source, packageName, target);
                case OPENS -> builder.addOpens(source, packageName, target);
            }
        }
    }

    private static List<String> splitModules(String value) {
        return Pattern.compile(",")
                .splitAsStream(value)
                .map(String::strip)
                .filter(module -> !module.isEmpty())
                .toList();
    }

    private static String operand(List<String> arguments, int index, String option) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(option + " requires an argument");
        }
        return arguments.get(index);
    }
}
