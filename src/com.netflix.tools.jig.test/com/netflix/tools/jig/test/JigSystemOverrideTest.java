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

package com.netflix.tools.jig.test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.tools.jig.Jig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JigSystemOverrideTest {
    private static final String COMPILE_OPTIONS = "module-path,processor-module-path,upgrade-module-path,module-source-path," + "module=list,module-version,patch-module,release,enable-preview," + "add-exports";
    private static final String LAUNCH_OPTIONS = "module-path,upgrade-module-path,patch-module,add-modules,module=main," + "enable-preview,enable-native-access,enable-final-field-mutation," + "add-opens,add-exports";
    private static final String RUNTIME_OPTIONS = "module-path,upgrade-module-path,patch-module,add-modules," + "enable-preview,enable-native-access,enable-final-field-mutation," + "add-opens,add-exports";

    private final ToolProvider jig = new Jig();

    @Test
    void describeUsesTheUpgradeModulePath(@TempDir Path directory) throws Exception {
        Path source = sourceModule(directory, "java.logging", "module java.logging {}");

        String arguments = arguments("--module-source-path", directory.resolve("src").toString(),
                "-m", "java.logging", "--resolve-options", "module-path,upgrade-module-path,describe-module");

        Path compiled = optionPath(arguments, "--upgrade-module-path");
        assertTrue(Files.isRegularFile(compiled.resolve("module-info.class")));
        assertTrue(arguments.endsWith("--describe-module\njava.logging\n"), arguments);
        assertTrue(Files.isDirectory(source));
    }

    @Test
    void launchUsesTheUpgradeModulePath(@TempDir Path directory) throws Exception {
        sourceModule(directory, "java.logging",
                """
                /** @mainClass replacement.Main */
                module java.logging {}
                """);

        String arguments = arguments("--module-source-path", directory.resolve("src").toString(),
                "-m", "java.logging", "--resolve-options", LAUNCH_OPTIONS, "--validate-runtime-access");

        Path compiled = optionPath(arguments, "--upgrade-module-path");
        assertTrue(Files.isRegularFile(compiled.resolve("module-info.class")));
        assertTrue(
                arguments
                        .endsWith("""
                --add-modules
                java.logging
                --module
                java.logging/replacement.Main
                """),
                arguments);
    }

    @Test
    void runtimeUsesTheUpgradeModulePathWithoutRequiringAMainClass(@TempDir Path directory) throws Exception {
        sourceModule(directory, "java.logging", "module java.logging {}");

        String arguments = arguments("--module-source-path", directory.resolve("src").toString(),
                "-m", "java.logging", "--resolve-options", "module-path,upgrade-module-path,patch-module,add-modules");

        Path compiled = optionPath(arguments, "--upgrade-module-path");
        assertTrue(Files.isRegularFile(compiled.resolve("module-info.class")));
        assertTrue(arguments.endsWith("--add-modules\njava.logging\n"), arguments);
    }

    @Test
    void runtimeUsesTheContractOfAnUpgradedSystemModule(@TempDir Path directory) throws Exception {
        Path application = sourceModule(directory, "com.example.application",
                """
                module com.example.application {
                    requires java.logging;
                }
                """);
        Path replacement = systemModuleWithNativeAccess(directory.resolve("java.logging.jar"), "java.logging");

        String arguments = arguments(
                "--module-source-path",
                application.getParent().toString(),
                "-m",
                "com.example.application",
                "--module-path",
                replacement.toString(),
                "--resolve-options",
                RUNTIME_OPTIONS,
                "--validate-runtime-access");

        assertTrue(arguments.contains("--enable-native-access\njava.logging\n"), arguments);
        assertEquals(replacement, optionPath(arguments, "--upgrade-module-path"));
        var lines = arguments.lines().toList();
        String modulePath = lines.get(lines.indexOf("--module-path") + 1);
        assertTrue(!modulePath.contains(replacement.toString()), arguments);
    }

    @Test
    void fixedSystemOverrideRequiresUpgradeModulePathOutput(@TempDir Path directory) throws Exception {
        Path replacement = systemModuleWithNativeAccess(directory.resolve("java.logging.jar"), "java.logging");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-path",
                replacement.toString(),
                "-m",
                "java.logging",
                "--resolve-options",
                "module-path,add-modules");

        assertEquals(1, result);
        assertTrue(err.toString().contains("Module path module java.logging shadows a system module"), err.toString());
    }

    @Test
    void suppliedModulePathIsRetainedAlongsideCompiledSystemOverride(@TempDir Path directory) throws Exception {
        sourceModule(directory, "java.logging", "module java.logging {}");
        Path output = directory.resolve("modules");

        String arguments = arguments(
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "java.logging",
                "--module-path",
                output.toString(),
                "--resolve-options",
                "module-path,upgrade-module-path,add-modules");

        assertTrue(arguments.startsWith("--module-path\n" + output + "\n"), arguments);
        Path compiled = optionPath(arguments, "--upgrade-module-path");
        assertTrue(Files.isRegularFile(compiled.resolve("module-info.class")));
        assertTrue(arguments.endsWith("--add-modules\njava.logging\n"), arguments);
    }

    @Test
    void compilationConsumesTheSelectedSource(@TempDir Path directory) throws Exception {
        Path source = sourceModule(directory, "java.logging", "module java.logging {}");

        String arguments = arguments("--module-source-path", directory.resolve("src").toString(),
                "-m", "java.logging", "--resolve-options", COMPILE_OPTIONS);

        assertTrue(arguments.contains("--module-source-path"), arguments);
        assertTrue(arguments.contains(source.toString()), arguments);
        assertTrue(!arguments.contains("--upgrade-module-path"), arguments);
    }

    @Test
    void incompatibleOutputOptionsAreRejected(@TempDir Path directory) throws Exception {
        sourceModule(directory, "java.logging", "module java.logging {}");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "java.logging",
                "--resolve-options",
                "module-path,add-modules");

        assertEquals(1, result);
        assertTrue(err.toString().contains("Source module java.logging shadows a system module, but the requested " + "options do not support --upgrade-module-path"),
                err.toString());
    }

    private static Path optionPath(String arguments, String option) {
        var lines = arguments.lines().toList();
        return Path.of(lines.get(lines.indexOf(option) + 1));
    }

    private String arguments(String... arguments) {
        var out = new StringWriter();
        var err = new StringWriter();
        int result = jig.run(new PrintWriter(out), new PrintWriter(err), arguments);
        assertEquals(0, result, err.toString());
        return out.toString();
    }

    private static Path systemModuleWithNativeAccess(Path archive, String moduleName) throws Exception {
        var reference = ModuleFinder.ofSystem()
                .find(moduleName)
                .orElseThrow();
        byte[] moduleInfo;
        try (var reader = reference.open();
             var input = reader.open("module-info.class").orElseThrow()) {
            var access = ModuleRuntimeAccessOptions.newBuilder()
                    .enableNativeAccess(moduleName)
                    .build();
            moduleInfo = ModuleRuntimeAccess.write(input.readAllBytes(), access);
        }
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("module-info.class"));
            output.write(moduleInfo);
            output.closeEntry();
        }
        return archive;
    }

    private static Path sourceModule(Path directory, String moduleName, String declaration) throws Exception {
        Path source = directory.resolve("src").resolve(moduleName);
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), declaration);
        return source;
    }
}
