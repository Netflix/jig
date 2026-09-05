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
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleMainClassAttribute;
import java.lang.classfile.attribute.ModulePackagesAttribute;
import java.lang.classfile.attribute.ModuleProvideInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.ClassFileFormatVersion;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessAttribute;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.tools.jig.Jig;
import com.netflix.tools.jig.Jig.Options;
import com.netflix.tools.jig.Jig.Options.ModuleForm;
import com.netflix.tools.jig.Jig.RepositoryPaths;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.module.MavenArtifactOrigin;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import com.netflix.tools.jig.module.ModuleResolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for CLI argument parsing.
 */
public class JigTest {
    private static final String COMPILE_OPTIONS = "module-path,processor-module-path,upgrade-module-path,module-source-path," + "module=list,module-version,patch-module,release,enable-preview," + "add-exports";
    private static final String LAUNCH_OPTIONS = "module-path,upgrade-module-path,patch-module,add-modules,module=main," + "enable-preview,enable-native-access,enable-final-field-mutation," + "add-opens,add-exports";
    private static final String ACCESS_OPTIONS = "enable-native-access,enable-final-field-mutation,add-opens,add-exports";

    @Test
    void serveListenAddress() {
        var random = Jig.parseListenAddress(null);
        assertEquals("127.0.0.1", random.getHostString());
        assertEquals(0, random.getPort());

        var specified = Jig.parseListenAddress("0.0.0.0:8080");
        assertEquals("0.0.0.0", specified.getHostString());
        assertEquals(8080, specified.getPort());
    }

    @Test
    void moduleProxyBindsToRandomPort(@TempDir Path localRepository) throws Exception {
        var trace = new StringWriter();
        try (var session = ModuleRepositorySession.create(localRepository, List.of());
             var server = Jig.startModuleProxy(session, new InetSocketAddress("127.0.0.1", 0), new PrintWriter(trace, true))) {
            assertTrue(server.uri().getPort() > 0);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.uri().resolve("missing"))
                    .GET()
                    .build(),
                    BodyHandlers.discarding());
            assertEquals(404, response.statusCode());
            assertEquals("jig",
                    response.headers()
                            .firstValue("Server")
                            .orElseThrow());
        }
        assertTrue(trace.toString().contains("[proxy 1 request GET /missing]"),
                trace.toString());
        assertTrue(trace.toString().contains("[proxy 1 resource not found missing]"),
                trace.toString());
        assertTrue(trace.toString().contains("[proxy 1 response 404]"),
                trace.toString());
        assertTrue(trace.toString()
                        .lines()
                        .allMatch(line -> line.startsWith("[") && line.endsWith("]")),
                trace.toString());
    }

    @Test
    @DisplayName("--module / -m: root module")
    void moduleFlag() {
        Options opts = Options.parse(new String[] {"-m", "com.example.app"});
        assertEquals(1, opts.rootNames()
                            .size());
        assertTrue(opts.rootNames()
                       .contains("com.example.app"));
    }

    @Test
    @DisplayName("--module=value")
    void moduleFlagEquals() {
        Options opts = Options.parse(new String[] {"--module=com.example.app"});
        assertEquals(1, opts.rootNames()
                            .size());
        assertTrue(opts.rootNames()
                       .contains("com.example.app"));
    }

    @Test
    @DisplayName("--add-modules: comma-separated")
    void addModules() {
        Options opts = Options.parse(new String[] {"--add-modules", "mod.a,mod.b,mod.c"});
        assertEquals(3, opts.rootNames()
                            .size());
        assertTrue(opts.rootNames()
                       .contains("mod.a"));
        assertTrue(opts.rootNames()
                       .contains("mod.b"));
        assertTrue(opts.rootNames()
                       .contains("mod.c"));
    }

    @Test
    @DisplayName("--add-modules=value")
    void addModulesEquals() {
        Options opts = Options.parse(new String[] {"--add-modules=mod.a,mod.b"});
        assertEquals(2, opts.rootNames()
                            .size());
    }

    @Test
    void addRequiresAddsVersionedExternalRoots() {
        Options opts = Options.parse(new String[] {"--add-requires", "org.junit.platform.console@6.1.2", "--add-requires=org.junit.jupiter.engine@6.1.2"});

        assertEquals(Map.of("org.junit.platform.console", "6.1.2", "org.junit.jupiter.engine", "6.1.2"), opts.addedRequires);
        assertEquals(Set.of("org.junit.platform.console", "org.junit.jupiter.engine"), Set.copyOf(opts.rootNames()));
    }

    @Test
    void lookupModuleAcceptsASupportedUrl() {
        Options opts = Options.parse(new String[] {"--lookup-module", "pkg:maven/org.apache.commons/commons-configuration2@2.15.1"});

        assertEquals(new MavenArtifactOrigin("org.apache.commons", "commons-configuration2", "2.15.1"), opts.moduleLookup);
    }

    @Test
    @DisplayName("rootNames deduplicates")
    void rootNamesDeduplicates() {
        Options opts = Options.parse(new String[] {"-m", "app", "--add-modules", "app"});
        assertEquals(1, opts.rootNames()
                            .size());
        assertTrue(opts.rootNames()
                       .contains("app"));
    }

    @Test
    @DisplayName("--module-path / -p")
    void modulePath() {
        Options opts = Options.parse(new String[] {"-p", "/path/to/mods", "-m", "app"});
        assertEquals(1, opts.modulePath.length);
        assertEquals("/path/to/mods", opts.modulePath[0].toString());
    }

    @Test
    @DisplayName("--module-path=value")
    void modulePathEquals() {
        Options opts = Options.parse(new String[] {"--module-path=/path/to/mods", "-m", "app"});
        assertEquals(1, opts.modulePath.length);
    }

    @Test
    void outputDirectoryIsNotAResolutionOption() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"-d", "/path/to/output", "-m", "app"}));
        assertTrue(exception.getMessage()
                            .contains("unknown option: -d"));
    }

    // -- module-source-path forms --

    @Test
    @DisplayName("--module-source-path: plain directory path")
    void sourcePathDirectory() {
        Options opts = Options.parse(new String[] {"--module-source-path", "/src", "-m", "app"});
        assertEquals(List.of("/src"), opts.moduleSourcePaths);
    }

    @Test
    @DisplayName("--module-source-path: module-specific form (name=path)")
    void sourcePathModuleSpecific() {
        Options opts = Options.parse(new String[] {"--module-source-path", "com.example.app=src/main/java", "-m", "app"});
        assertEquals(List.of("com.example.app=src/main/java"), opts.moduleSourcePaths);
    }

    @Test
    @DisplayName("--module-source-path: module-pattern form (with *)")
    void sourcePathModulePattern() {
        Options opts = Options.parse(new String[] {"--module-source-path", "src/*/main/java", "-m", "app"});
        assertEquals(List.of("src/*/main/java"), opts.moduleSourcePaths);
    }

    @Test
    void moduleSourcePathArgumentIsAbsolute() {
        assertEquals(
                "com.example=" + Path.of(".")
                        .toAbsolutePath()
                        .normalize(),
                Jig.moduleSourcePathArgument("com.example", Path.of(".")));
    }

    @Test
    @DisplayName("--module-source-path: multiple instances")
    void sourcePathMultiple() {
        Options opts = Options.parse(new String[] {"--module-source-path", "com.example.core=core/src", "--module-source-path",
                "com.example.api=api/src", "-m", "app"});
        assertEquals(List.of("com.example.core=core/src", "com.example.api=api/src"), opts.moduleSourcePaths);
    }

    @Test
    @DisplayName("--module-source-path=value")
    void sourcePathEquals() {
        Options opts = Options.parse(new String[] {"--module-source-path=src/*/java", "-m", "app"});
        assertEquals(List.of("src/*/java"), opts.moduleSourcePaths);
    }

    // -- output modes --

    @Test
    void releaseOptionUsesTheCurrentReleaseWithoutAModuleGraph() {
        String arguments = runJig("--resolve-options", "release");

        assertEquals(
                """
                --release
                %s
                """
                        .formatted(Runtime.version().feature()),
                arguments);
    }

    @Test
    void moduleVersionOptionDoesNotRequireAModuleGraph() {
        String arguments = runJig("--module-version", "1.2.3", "--resolve-options", "module-version");

        assertEquals("""
                --module-version
                1.2.3
                """,
                arguments);
    }

    @Test
    void releaseOptionOmitsAnUnspecifiedRelease(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}\n");

        String arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", "release");

        assertEquals("", arguments);
    }

    @Test
    void previewUsesTheCurrentReleaseWhenNoneIsSpecified(@TempDir Path directory) throws Exception {
        var source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @enablePreview */
                module com.example.application {}
                """);

        var arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", "release,enable-preview");

        assertEquals(
                """
                --release
                %s
                --enable-preview
                """
                        .formatted(Runtime.version().feature()),
                arguments);
    }

    @Test
    void optionProjectionsAreWrittenToCallerOwnedFiles(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @mainClass com.example.Main */
                module com.example.application {}
                """);
        Path compiled = directory.resolve("modules/com.example.application");
        Files.createDirectories(compiled);
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.application"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(compiled.resolve("module-info.class"),
                ClassFile.of().buildModule(moduleAttribute, builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_9.major(), 0)));
        Path compileArguments = directory.resolve("compile.args");
        Path packageArguments = directory.resolve("package.args");
        Path launchArguments = directory.resolve("launch.args");
        var out = new StringWriter();
        var err = new StringWriter();

        int compileResult = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                compiled.toString(),
                "--module",
                "com.example.application",
                "--compile-time",
                "--resolve-options",
                "module-path,module-source-path,module=list",
                "--write-argfile",
                compileArguments.toString());
        int packageResult = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                compiled.toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "main-class",
                "--write-argfile",
                packageArguments.toString());
        int launchResult = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                compiled.toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,add-modules,module=main",
                "--write-argfile",
                launchArguments.toString());

        assertEquals(0, compileResult, err.toString());
        assertEquals(0, packageResult, err.toString());
        assertEquals(0, launchResult, err.toString());
        assertEquals("", out.toString());
        assertEquals(
                """
                --module-path
                %s
                --module-source-path
                com.example.application=%s
                --module
                com.example.application
                """
                        .formatted(compiled, source),
                Files.readString(compileArguments));
        assertEquals(
                """
                --main-class
                com.example.Main
                """,
                Files.readString(packageArguments));
        assertEquals(
                """
                --module-path
                %s
                --add-modules
                com.example.application
                --module
                com.example.application/com.example.Main
                """
                        .formatted(compiled),
                Files.readString(launchArguments));
    }

    @Test
    void mainClassSelectsTheOnlyRootThatDeclaresOne(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src");
        Path app = Files.createDirectories(source.resolve("com.example.app"));
        Path library = Files.createDirectories(source.resolve("com.example.library"));
        Files.writeString(app.resolve("module-info.java"),
                """
                /** @mainClass com.example.app.Main */
                module com.example.app {}
                """);
        Files.writeString(library.resolve("module-info.java"),
                """
                module com.example.library {}
                """);
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                source.toString(),
                "-m",
                "com.example.app",
                "-m",
                "com.example.library",
                "--resolve-options",
                "main-class");

        assertEquals(0, result, err.toString());
        assertEquals(
                """
                --main-class
                com.example.app.Main
                """,
                out.toString());
    }

    @Test
    void rootlessLaunchSelectsTheUniqueSourceMainModule(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src");
        Path app = Files.createDirectories(source.resolve("com.example.app"));
        Path tests = Files.createDirectories(source.resolve("com.example.app.test"));
        Files.writeString(app.resolve("module-info.java"),
                """
                /** @mainClass com.example.app.Main */
                module com.example.app {}
                """);
        Files.writeString(tests.resolve("module-info.java"),
                """
                module com.example.app.test {
                    requires com.example.missing;
                }
                """);
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(new PrintWriter(out), new PrintWriter(err), "--module-source-path",
                source.toString(), "--resolve-options", LAUNCH_OPTIONS, "--validate-runtime-access");

        assertEquals(0, result, err.toString());
        Path compiled = optionPath(out.toString(), "--module-path");
        assertTrue(Files.isRegularFile(compiled.resolve("module-info.class")));
        assertTrue(out.toString()
                      .endsWith("""
                --add-modules
                com.example.app
                --module
                com.example.app/com.example.app.Main
                """),
                        out.toString());
    }

    @Test
    void rootlessLaunchListsAmbiguousSourceMainModules(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src");
        Path app = Files.createDirectories(source.resolve("com.example.app"));
        Path other = Files.createDirectories(source.resolve("com.example.other"));
        Files.writeString(app.resolve("module-info.java"),
                """
                /** @mainClass com.example.app.Main */
                module com.example.app {}
                """);
        Files.writeString(other.resolve("module-info.java"),
                """
                /** @mainClass com.example.other.Main */
                module com.example.other {}
                """);
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(new PrintWriter(out), new PrintWriter(err), "--module-source-path",
                source.toString(), "--resolve-options", LAUNCH_OPTIONS, "--validate-runtime-access");

        assertEquals(1, result);
        assertEquals("", out.toString());
        assertTrue(err.toString().contains("select one with -m:"),
                err.toString());
        assertTrue(err.toString().contains("-m com.example.app"),
                err.toString());
        assertTrue(err.toString().contains("-m com.example.other"),
                err.toString());
    }

    @Test
    void rootlessLaunchFallsBackToTheModulePath(@TempDir Path directory) throws Exception {
        Path modules = Files.createDirectories(directory.resolve("modules"));
        Path app = Files.createDirectories(modules.resolve("com.example.app"));
        Path mainClass = Files.createDirectories(app.resolve("com/example/app")).resolve("Main.class");
        Files.write(mainClass, new byte[0]);
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.app"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(
                app.resolve("module-info.class"),
                ClassFile.of().buildModule(moduleAttribute,
                        builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_9.major(), 0).with(ModuleMainClassAttribute.of(ClassDesc.of("com.example.app.Main")))));
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(new PrintWriter(out), new PrintWriter(err), "--module-path",
                modules.toString(), "--resolve-options", LAUNCH_OPTIONS, "--validate-runtime-access");

        assertEquals(0, result, err.toString());
        assertEquals(
                """
                --module-path
                %s
                --add-modules
                com.example.app
                --module
                com.example.app/com.example.app.Main
                """
                        .formatted(modules),
                out.toString());
    }

    @Test
    void mainClassListsRootsWhenOneMustBeSelected(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src");
        Path app = Files.createDirectories(source.resolve("com.example.app"));
        Path other = Files.createDirectories(source.resolve("com.example.other"));
        Files.writeString(app.resolve("module-info.java"),
                """
                /** @mainClass com.example.app.Main */
                module com.example.app {}
                """);
        Files.writeString(other.resolve("module-info.java"),
                """
                /** @mainClass com.example.other.Main */
                module com.example.other {}
                """);
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                source.toString(),
                "-m",
                "com.example.app",
                "-m",
                "com.example.other",
                "--resolve-options",
                "main-class");

        assertEquals(1, result);
        assertEquals("", out.toString());
        assertTrue(err.toString().contains("select one with -m:"),
                err.toString());
        assertTrue(err.toString().contains("-m com.example.app"),
                err.toString());
        assertTrue(err.toString().contains("-m com.example.other"),
                err.toString());
    }

    @Test
    void oneResolutionProducesCompileAndLaunchArguments(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @mainClass com.example.Main */
                module com.example.application {}
                """);
        Path mainSource = source.resolve("com/example/Main.java");
        Files.createDirectories(mainSource.getParent());
        Files.writeString(mainSource,
                """
                package com.example;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("started");
                    }
                }
                """);
        Path output = directory.resolve("modules");
        Path compileArguments = directory.resolve("compile.args");
        Path launchArguments = directory.resolve("launch.args");
        var out = new StringWriter();
        var err = new StringWriter();

        int compileProjection = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--compile-time",
                "--resolve-options",
                "module-source-path,module=list",
                "--write-argfile",
                compileArguments.toString());
        assertEquals(0, compileProjection, err.toString());
        assertEquals("", out.toString());
        assertEquals(
                """
                --module-source-path
                com.example.application=%s
                --module
                com.example.application
                """
                        .formatted(source),
                Files.readString(compileArguments));

        var compileOut = new StringWriter();
        var compileErr = new StringWriter();
        int compileResult = ToolProvider.findFirst("javac")
                .orElseThrow()
                .run(new PrintWriter(compileOut), new PrintWriter(compileErr), "-d",
                        output.toString(), "@" + compileArguments);
        assertEquals(0, compileResult, compileErr.toString());

        int launchProjection = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-path",
                output.toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,add-modules",
                "--write-argfile",
                launchArguments.toString());
        assertEquals(0, launchProjection, err.toString());
        assertEquals(
                """
                --module-path
                %s
                --add-modules
                com.example.application
                """
                        .formatted(output),
                Files.readString(launchArguments));

        String javaCommand = ProcessHandle.current()
                .info()
                .command()
                .orElseThrow();
        Process process = new ProcessBuilder(javaCommand, "@" + launchArguments, "--module", "com.example.application/com.example.Main")
                .redirectErrorStream(true)
                .start();
        String processOutput = new String(process.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), processOutput);
        assertEquals("started" + System.lineSeparator(), processOutput);
    }

    @Test
    void describeProjectsTheCompiledRootModule(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        Path compiled = directory.resolve("modules/com.example.application");
        Files.createDirectories(compiled);
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.application"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(compiled.resolve("module-info.class"),
                ClassFile.of().buildModule(moduleAttribute, builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_9.major(), 0)));

        String arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                compiled.toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,describe-module");

        assertEquals(
                """
                --module-path
                %s
                --describe-module
                com.example.application
                """
                        .formatted(compiled),
                arguments);
    }

    @Test
    void describeMaterializesSourceModule(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        var arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", "module-path,describe-module");

        var lines = arguments.lines().toList();
        var module = Path.of(lines.get(lines.indexOf("--module-path") + 1));
        assertTrue(Files.isRegularFile(module.resolve("module-info.class")));
        assertTrue(arguments.contains("--describe-module\ncom.example.application\n"));
    }

    @Test
    void describeRejectsASystemShadowWithoutUpgradeModulePath(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/java.logging");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module java.logging {}");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "java.logging",
                "--resolve-options",
                "module-path,describe-module");

        assertEquals(1, result);
        assertTrue(err.toString().contains("Source module java.logging shadows a system module, but the requested " + "options do not support --upgrade-module-path"),
                err.toString());
    }

    @Test
    void describeUsesTheOrdinaryModulePathForANonShadowingSourceModule(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        String arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", "module-path,upgrade-module-path,describe-module");

        Path compiled = optionPath(arguments, "--module-path");
        assertTrue(Files.isRegularFile(compiled.resolve("module-info.class")));
        assertTrue(arguments.endsWith("--describe-module\ncom.example.application\n"), arguments);
    }

    @Test
    void launchMaterializesSourceModule(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @mainClass com.example.Main */
                module com.example.application {}
                """);
        var arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", LAUNCH_OPTIONS, "--validate-runtime-access");

        var lines = arguments.lines().toList();
        var module = Path.of(lines.get(lines.indexOf("--module-path") + 1));
        assertTrue(Files.isRegularFile(module.resolve("module-info.class")));
        assertTrue(arguments.contains("--module\ncom.example.application/com.example.Main\n"));
    }

    @Test
    void changedSourceUsesANewImmutableModuleOutput(@TempDir Path directory) throws Exception {
        var previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.resolve("home")
                .toString());
        try {
            Path source = Files.createDirectories(directory.resolve("src/com.example.application/com/example"));
            Files.writeString(source.getParent()
                                    .getParent()
                                    .resolve("module-info.java"),
                    """
                    /** @mainClass com.example.Main */
                    module com.example.application {}
                    """);
            var main = source.resolve("Main.java");
            Files.writeString(main,
                    """
                    package com.example;
                    public class Main {
                        public static void main(String[] arguments) {
                            System.out.print("before");
                        }
                    }
                    """);
            var arguments = new String[] {"--module-source-path", directory.resolve("src").toString(), "--module", "com.example.application", "--resolve-options",
                    LAUNCH_OPTIONS, "--validate-runtime-access"};

            var first = runJig(arguments);
            Files.writeString(main,
                    """
                    package com.example;
                    public class Main {
                        public static void main(String[] arguments) {
                            System.out.print("after");
                        }
                    }
                    """);
            var second = runJig(arguments);

            var firstLines = first.lines().toList();
            var secondLines = second.lines().toList();
            assertNotEquals(firstLines.get(firstLines.indexOf("--module-path") + 1), secondLines.get(secondLines.indexOf("--module-path") + 1));
            assertFalse(secondLines.contains("--patch-module"), second);

            var argumentFile = directory.resolve("launch.args");
            Files.writeString(argumentFile, second);
            var javaCommand = ProcessHandle.current()
                    .info()
                    .command()
                    .orElseThrow();
            var process = new ProcessBuilder(javaCommand, "@" + argumentFile).redirectErrorStream(true).start();
            var output = new String(process.getInputStream()
                    .readAllBytes(),
                            StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
            assertEquals("after", output);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void changedSourcesReplaceClassOutput(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("src/com.example.application/p"));
        Files.writeString(source.getParent()
                                .resolve("module-info.java"),
                "module com.example.application {}\n");
        var javaSource = source.resolve("Example.java");
        Files.writeString(javaSource, "package p; public class Example {}\n");
        var output = directory.resolve("modules");

        var firstOut = new StringWriter();
        var firstErr = new StringWriter();
        int first = new Jig().run(
                new PrintWriter(firstOut),
                new PrintWriter(firstErr),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");
        assertEquals(0, first, firstErr.toString());

        Files.writeString(javaSource, "package p; public class Example { syntax error }\n");
        var secondOut = new StringWriter();
        var secondErr = new StringWriter();
        int second = new Jig().run(
                new PrintWriter(secondOut),
                new PrintWriter(secondErr),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");

        assertEquals(1, second);
        assertTrue(secondErr.toString().contains("Compilation failed"),
                secondErr.toString());
    }

    @Test
    void reportsSuccessfulCompilationDiagnostics(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("src/com.example.application/p"));
        var api = Files.createDirectories(source.getParent()
                .resolve("api"));
        var sourceIdentity = UUID.randomUUID().toString();
        Files.writeString(source.getParent()
                                .resolve("module-info.java"),
                "module com.example.application { exports p; }\n");
        Files.writeString(api.resolve("Api.java"),
                """
                package api;
                public class Api {
                    public static final String SOURCE_IDENTITY = "%s";
                    public static int value() {
                        return System.getSecurityManager() == null ? 1 : 0;
                    }
                }
                """
                        .formatted(sourceIdentity));
        Files.writeString(source.resolve("Example.java"),
                """
                package p;
                public class Example { public int value() { return api.Api.value() + 1; } }
                """);
        var silentErr = new StringWriter();
        int silent = new Jig().run(
                new PrintWriter(new StringWriter()),
                new PrintWriter(silentErr),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--no-compile-diagnostics",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");
        assertEquals(0, silent, silentErr.toString());
        assertFalse(silentErr.toString().contains("getSecurityManager"),
                silentErr.toString());

        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");

        assertEquals(0, result, err.toString());
        assertTrue(err.toString().contains("warning"),
                err.toString());
        assertTrue(err.toString().contains("getSecurityManager"),
                err.toString());
        assertTrue(err.toString().contains(api.resolve("Api.java") + ":"),
                err.toString());
        assertTrue(err.toString().contains("[diagnostics replayed from previous compilation]\n"),
                err.toString());
        assertEquals(err.toString().indexOf("[diagnostics replayed from previous compilation]"),
                err.toString().lastIndexOf("[diagnostics replayed from previous compilation]"));
        assertTrue(err.toString().contains("warning: [removal]"),
                err.toString());
        assertTrue(err.toString().contains("return System.getSecurityManager() == null ? 1 : 0;"),
                err.toString());
        assertTrue(err.toString().contains("^"),
                err.toString());
        assertFalse(err.toString().contains("[diagnostics replayed from previous compilation] " + api.resolve("Api.java")),
                err.toString());

        Files.writeString(source.resolve("Example.java"),
                """
                package p;
                public class Example { public int value() { return api.Api.value() + 2; } }
                """);
        var replayedErr = new StringWriter();
        int replayed = new Jig().run(
                new PrintWriter(new StringWriter()),
                new PrintWriter(replayedErr),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");

        assertEquals(0, replayed, replayedErr.toString());
        assertTrue(replayedErr.toString().contains(api.resolve("Api.java") + ":"),
                replayedErr.toString());
        assertTrue(replayedErr.toString().contains("getSecurityManager"),
                replayedErr.toString());
        assertTrue(replayedErr.toString().contains("[diagnostics replayed from previous compilation]\n"),
                replayedErr.toString());
        assertEquals(replayedErr.toString().indexOf("[diagnostics replayed from previous compilation]"),
                replayedErr.toString().lastIndexOf("[diagnostics replayed from previous compilation]"));
        assertTrue(replayedErr.toString().contains("\n" + source.resolve("Example.java")),
                replayedErr.toString());
        assertFalse(replayedErr.toString().contains("[diagnostics replayed from previous compilation] " + source.resolve("Example.java")),
                replayedErr.toString());

        var recompiledErr = new StringWriter();
        int recompiled = new Jig().run(
                new PrintWriter(new StringWriter()),
                new PrintWriter(recompiledErr),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--recompile",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");

        assertEquals(0, recompiled, recompiledErr.toString());
        assertTrue(recompiledErr.toString().contains("getSecurityManager"),
                recompiledErr.toString());
        assertFalse(recompiledErr.toString().contains("[diagnostics replayed from previous compilation]"),
                recompiledErr.toString());
        assertEquals(recompiledErr.toString(),
                replayedErr.toString().replace("[diagnostics replayed from previous compilation]" + System.lineSeparator(), ""));
    }

    @Test
    void qualifiedExportTargetsAreVisibleFromTheModuleSourcePath(@TempDir Path directory) throws Exception {
        var application = Files.createDirectories(directory.resolve("src/com.example.application/p"));
        Files.writeString(application.getParent()
                .resolve("module-info.java"),
                """
                module com.example.application {
                    exports p to com.example.tests;
                }
                """);
        Files.writeString(application.resolve("Example.java"), "package p; public class Example {}\n");
        var tests = Files.createDirectories(directory.resolve("src/com.example.tests"));
        Files.writeString(tests.resolve("module-info.java"), "module com.example.tests {}\n");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--module",
                "com.example.tests",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");

        assertEquals(0, result, err.toString());
        assertFalse(err.toString().contains("module not found"),
                err.toString());
    }

    @Test
    void materializationCompilesReadableSourceDependencies(@TempDir Path directory) throws Exception {
        var library = Files.createDirectories(directory.resolve("src/com.example.library/p"));
        Files.writeString(library.getParent()
                .resolve("module-info.java"),
                """
                module com.example.library {
                    exports p;
                }
                """);
        Files.writeString(library.resolve("Library.java"), "package p; public class Library {}\n");
        var application = Files.createDirectories(directory.resolve("src/com.example.application/app"));
        Files.writeString(application.getParent()
                .resolve("module-info.java"),
                """
                module com.example.application {
                    requires com.example.library;
                }
                """);
        Files.writeString(application.resolve("Application.java"), "package app; public class Application { p.Library library; }\n");

        var arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example.application",
                "-m",
                "com.example.library",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");

        var lines = arguments.lines().toList();
        var paths = lines.get(lines.indexOf("--module-path") + 1).split(Pattern.quote(System.getProperty("path.separator")));
        assertEquals(2, paths.length);
        assertTrue(Arrays.stream(paths)
                .map(Path::of)
                .anyMatch(path -> Files.isRegularFile(path.resolve("app/Application.class"))));
        assertTrue(Arrays.stream(paths)
                .map(Path::of)
                .anyMatch(path -> Files.isRegularFile(path.resolve("p/Library.class"))));
    }

    @Test
    void runtimeAndAccessProjectIndependentArgumentFamilies(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                /**
                 * @enableNativeAccess com.example.application
                 * @enableFinalFieldMutation com.example.application
                 * @addOpens java.base/java.lang=com.example.application
                 * @addExports java.base/jdk.internal.misc=com.example.application
                 */
                module com.example.application {}
                """);
        String runtimeArguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", "module-path,upgrade-module-path,patch-module,add-modules");

        Path compiled = optionPath(runtimeArguments, "--module-path");
        assertTrue(Files.isRegularFile(compiled.resolve("module-info.class")));
        assertTrue(runtimeArguments.endsWith("--add-modules\ncom.example.application\n"), runtimeArguments);

        String accessArguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", ACCESS_OPTIONS, "--validate-runtime-access");

        assertEquals(
                """
                --enable-native-access
                com.example.application
                --enable-final-field-mutation
                com.example.application
                --add-opens
                java.base/java.lang=com.example.application
                --add-exports
                java.base/jdk.internal.misc=com.example.application
                """,
                accessArguments);
    }

    @Test
    void materializationRejectsReleaseWithSystemModuleExport(@TempDir Path directory) throws Exception {
        var source = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(source.resolve("module-info.java"),
                """
                /**
                 * @release 21
                 * @addExports java.base/jdk.internal.misc=com.example.application
                 */
                module com.example.application {}
                """);
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module",
                "com.example.application",
                "--resolve-options",
                "module-path,upgrade-module-path,patch-module,add-modules");

        assertEquals(1, result);
        assertTrue(err.toString()
                      .contains("exporting a package from system module java.base is not allowed with --release"));
    }

    @Test
    void multiReleaseOptionUsesTheSourceRelease(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @release 21 */
                module com.example.application {}
                """);

        String arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", "multi-release");

        assertEquals("""
                --multi-release
                21
                """,
                arguments);
    }

    @Test
    void multiReleaseOptionFallsBackToTheCurrentReleaseWithoutAModuleGraph() {
        String arguments = runJig("--resolve-options", "multi-release");

        assertEquals(
                """
                --multi-release
                %s
                """
                        .formatted(Runtime.version().feature()),
                arguments);
    }

    @Test
    void optionProjectionIsPrintedToStandardOutput(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");

        String arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "--module", "com.example.application", "--resolve-options", "module-source-path,module,release");

        assertEquals(
                """
                --module-source-path
                com.example.application=%s
                --module
                com.example.application
                """
                        .formatted(source),
                arguments);
    }

    @Test
    void optionProjectionCanBeWrittenToAnArgumentFile() {
        Options opts = Options.parse(new String[] {"--resolve-options", "module-path,module=list", "--write-argfile",
                "run.args", "-m", "app"});

        assertEquals(Set.of("module-path", "module"), opts.resolveOptions);
        assertEquals(ModuleForm.LIST, opts.moduleForm);
        assertEquals(Path.of("run.args"), opts.argumentFile);
    }

    @Test
    void shortProjectionOptionsAreAccepted() {
        Options opts = Options.parse(new String[] {"-r", "module-path,module=main", "-w", "run.args", "-m", "app"});

        assertEquals(Set.of("module-path", "module"), opts.resolveOptions);
        assertEquals(ModuleForm.MAIN, opts.moduleForm);
        assertEquals(Path.of("run.args"), opts.argumentFile);
    }

    @Test
    void unqualifiedModuleUsesTheSingleModuleForm() {
        Options opts = Options.parse(new String[] {"--resolve-options", "module-path,module", "-m", "app"});

        assertEquals(ModuleForm.SINGLE, opts.moduleForm);
    }

    @Test
    void explicitSingleModuleFormIsAccepted() {
        Options opts = Options.parse(new String[] {"--resolve-options", "module-path,module=single", "-m", "app"});

        assertEquals(ModuleForm.SINGLE, opts.moduleForm);
    }

    @Test
    void rootModuleFormIsAcceptedWithAddModules() {
        Options opts = Options.parse(new String[] {"--resolve-options", "module-path,module=roots,add-modules", "-m", "app"});

        assertEquals(ModuleForm.ROOTS, opts.moduleForm);
    }

    @Test
    void baselineProjectsRootsWithoutSourceOptions(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("source trees");
        Path application = sources.resolve("com.example.application");
        Path library = sources.resolve("com.example.library");
        Files.createDirectories(application);
        Files.createDirectories(library);
        Files.writeString(application.resolve("module-info.java"), "module com.example.application {}");
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(new PrintWriter(out), new PrintWriter(err), "--module-source-path",
                sources.toString(), "-m", "com.example.application", "--resolve-options", "add-modules");

        assertEquals(0, result, err.toString());
        assertEquals(
                """
                --add-modules
                com.example.application
                """,
                out.toString());
    }

    @Test
    void systemModulesOutsideBootRootsAreNotAddedToModulePath(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.application {
                    requires java.instrument;
                }
                """);

        String arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "-m", "com.example.application", "--resolve-options", "add-modules");

        assertEquals("""
                --add-modules
                com.example.application
                """,
                arguments);
    }

    @Test
    void retainsSuppliedCompiledModulePathAlongsideSourceDeclaration(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        Path compiled = directory.resolve("output/com.example.application");
        Files.createDirectories(compiled);
        var attribute = ModuleAttribute.of(ModuleDesc.of("com.example.application"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(compiled.resolve("module-info.class"),
                ClassFile.of().buildModule(attribute, builder -> builder.withVersion(ClassFileFormatVersion.RELEASE_9.major(), 0)));

        String arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                compiled.toString(),
                "-m",
                "com.example.application",
                "--resolve-options",
                "module-path,add-modules");

        assertEquals(
                """
                --module-path
                %s
                --add-modules
                com.example.application
                """
                        .formatted(compiled),
                arguments);
    }

    @Test
    void automaticModulesSelectedByJpmsAreNotStaticOnly(@TempDir Path directory) throws Exception {
        ModuleFinder finder = ModuleFinder.of(automaticJar(directory, "auto.parent"), automaticJar(directory, "auto.dependency"));
        Configuration configuration = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(), Set.of("auto.parent"));

        assertEquals(Set.of(), ModuleResolution.staticOnlyModules(configuration, Set.of("auto.parent")));
    }

    @Test
    void sourcePathsUseResolvedSourceDirectories(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("source trees");
        Path application = sources.resolve("com.example.application");
        Path library = sources.resolve("com.example.library");
        Files.createDirectories(application);
        Files.createDirectories(library);
        Files.writeString(application.resolve("module-info.java"), "module com.example.application {}");
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}");
        var out = new StringWriter();
        var err = new StringWriter();
        var previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.resolve("home")
                .toString());
        final int result;
        try {
            result = new Jig().run(
                    new PrintWriter(out),
                    new PrintWriter(err),
                    "--module-source-path",
                    sources.toString(),
                    "-m",
                    "com.example.application",
                    "-m",
                    "com.example.library",
                    "--resolve-options",
                    "module-source-path,source-path,release,module=list");
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }

        assertEquals(0, result, err.toString());
        var lines = out.toString()
                       .lines()
                       .toList();
        assertTrue(lines.contains("\"com.example.application=" + application + "\""));
        assertTrue(lines.contains("\"com.example.library=" + library + "\""));
        var sourcePath = lines.get(lines.indexOf("--source-path") + 1);
        var archives = Arrays.stream(sourcePath.split(Pattern.quote(System.getProperty("path.separator"))))
                .map(value -> value.startsWith("\"") ? value.substring(1) : value)
                .map(value -> value.endsWith("\"") ? value.substring(0, value.length() - 1) : value)
                .map(Path::of)
                .toList();
        assertEquals(List.of(application, library), archives);
        assertEquals("--module", lines.get(lines.size() - 2));
        assertEquals("com.example.application,com.example.library", lines.getLast());
    }

    @Test
    void rootModuleFormUsesModuleForASingleRoot(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        Path source = Files.createDirectories(sources.resolve("com.example.application"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");

        String arguments = runJig("--module-source-path", sources.toString(), "-m", "com.example.application", "--resolve-options",
                "module=roots,add-modules");

        assertEquals("""
                --module
                com.example.application
                """,
                arguments);
    }

    @Test
    void rootModuleFormUsesAddModulesForMultipleRoots(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        for (String module : List.of("com.example.application", "com.example.library")) {
            Path source = Files.createDirectories(sources.resolve(module));
            Files.writeString(source.resolve("module-info.java"), "module " + module + " {}");
        }

        String arguments = runJig("--module-source-path", sources.toString(), "-m", "com.example.application", "-m",
                "com.example.library", "--resolve-options", "module=roots,add-modules");

        assertEquals("""
                --add-modules
                com.example.application,com.example.library
                """,
                arguments);
    }

    @Test
    void unqualifiedModuleRejectsMultipleApplicableModules(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        for (String module : List.of("com.example.application", "com.example.library")) {
            Path source = Files.createDirectories(sources.resolve(module));
            Files.writeString(source.resolve("module-info.java"), "module " + module + " {}");
        }
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                sources.toString(),
                "-m",
                "com.example.application",
                "-m",
                "com.example.library",
                "--resolve-options",
                "module-source-path,module");

        assertEquals(1, result);
        assertEquals("", out.toString());
        assertTrue(err.toString().contains("module requires exactly one applicable module, found 2; use module=list"),
                err.toString());
    }

    @Test
    void compileTimeSelectsCompileTimeGraphForCustomTools(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        Path application = sources.resolve("com.example.application");
        Path annotations = sources.resolve("com.example.annotations");
        Files.createDirectories(application);
        Files.createDirectories(annotations);
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires static com.example.annotations;
                }
                """);
        Files.writeString(annotations.resolve("module-info.java"), "module com.example.annotations {}");

        String runtimeGraph = runJig("--module-source-path", sources.toString(), "-m", "com.example.application", "--resolve-options",
                "module-source-path,module");
        assertEquals(
                """
                --module-source-path
                com.example.application=%s
                --module
                com.example.application
                """
                        .formatted(application),
                runtimeGraph);

        String compileTimeGraph = runJig("--module-source-path", sources.toString(), "-m", "com.example.application", "--resolve-options",
                "module-source-path,module=list", "--compile-time");
        assertEquals(
                """
                --module-source-path
                com.example.annotations=%s
                --module-source-path
                com.example.application=%s
                --module
                com.example.application,com.example.annotations
                """
                        .formatted(annotations, application),
                compileTimeGraph);
    }

    @Test
    void sourceOptionsIncludeExplicitlySelectedStaticModules(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        Path application = sources.resolve("com.example.application");
        Path annotations = sources.resolve("com.example.annotations");
        Files.createDirectories(application);
        Files.createDirectories(annotations);
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires static com.example.annotations; // @1.0
                }
                """);
        Files.writeString(annotations.resolve("module-info.java"), "module com.example.annotations {}");

        String arguments = runJig("--module-source-path", sources.toString(), "-m", "com.example.application", "-m",
                "com.example.annotations", "--resolve-options", "module-source-path,release,module=list");

        assertEquals(
                """
                --module-source-path
                com.example.annotations=%s
                --module-source-path
                com.example.application=%s
                --module
                com.example.application,com.example.annotations
                """
                        .formatted(annotations, application),
                arguments);
    }

    @Test
    void sourceAndRuntimeOptionsProjectDifferentRequirements(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                /**
                 * @release %s
                 * @enablePreview
                 * @enableNativeAccess com.example.application
                 * @enableFinalFieldMutation com.example.application
                 * @addOpens java.base/java.lang=com.example.application
                 * @addExports java.base/jdk.internal.misc=com.example.application
                 */
                module com.example.application {}
                """
                        .formatted(Runtime.version().feature()));

        String sourceArguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "-m", "com.example.application", "--resolve-options", "module-source-path,release,enable-preview,add-exports,module");
        assertEquals(
                """
                --module-source-path
                com.example.application=%s
                --release
                %s
                --enable-preview
                --add-exports
                java.base/jdk.internal.misc=com.example.application
                --module
                com.example.application
                """
                        .formatted(source, Runtime.version().feature()),
                sourceArguments);

        String runtimeArguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "-m", "com.example.application", "--resolve-options", "enable-preview,enable-native-access,enable-final-field-mutation,add-opens,add-exports,add-modules");
        assertEquals(
                """
                --enable-preview
                --enable-native-access
                com.example.application
                --enable-final-field-mutation
                com.example.application
                --add-opens
                java.base/java.lang=com.example.application
                --add-exports
                java.base/jdk.internal.misc=com.example.application
                --add-modules
                com.example.application
                """,
                runtimeArguments);

        String combinedArguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "-m", "com.example.application", "--resolve-options", "module-source-path,release,enable-preview,enable-native-access,enable-final-field-mutation,add-opens,add-exports,module");
        assertEquals(
                """
                --module-source-path
                com.example.application=%s
                --release
                %s
                --enable-preview
                --enable-native-access
                com.example.application
                --enable-final-field-mutation
                com.example.application
                --add-opens
                java.base/java.lang=com.example.application
                --add-exports
                java.base/jdk.internal.misc=com.example.application
                --module
                com.example.application
                """
                        .formatted(source, Runtime.version().feature()),
                combinedArguments);
    }

    @Test
    void systemModuleAccessRequirementsAreImplicitlyAuthorized() throws Exception {
        var module = ModuleFinder.ofSystem()
                .find("com.netflix.tools.jfmt")
                .orElseThrow();
        var requirements = ModuleRuntimeAccess.read(module).orElseThrow();

        var arguments = runJig("-m", module.descriptor().name(),
                "--resolve-options", ACCESS_OPTIONS, "--validate-runtime-access");

        requirements.enableNativeAccess().forEach(name -> assertTrue(arguments.contains("--enable-native-access\n" + name + "\n"), arguments));
        requirements.enableFinalFieldMutation().forEach(name -> assertTrue(arguments.contains("--enable-final-field-mutation\n" + name + "\n"), arguments));
        requirements.addExports().forEach(access -> assertTrue(arguments.contains("--add-exports\n" + access.toFlagValue() + "\n"), arguments));
        requirements.addOpens().forEach(access -> assertTrue(arguments.contains("--add-opens\n" + access.toFlagValue() + "\n"), arguments));
    }

    @Test
    void runtimeAccessValidationIsIndependentOfAccessOutputOptions(@TempDir Path directory) throws Exception {
        var module = Files.createDirectories(directory.resolve("modules/com.example.application"));
        var declaration = ModuleAttribute.of(ModuleDesc.of("com.example.application"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        var access = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("com.example.application")
                .build();
        Files.write(module.resolve("module-info.class"),
                ClassFile.of().buildModule(declaration, builder -> builder.with(ModuleRuntimeAccessAttribute.of(access))));

        var out = new StringWriter();
        var err = new StringWriter();
        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-path",
                directory.resolve("modules").toString(),
                "-m",
                "com.example.application",
                "--validate-runtime-access");
        assertEquals(1, result);
        assertTrue(err.toString()
                      .contains("--enable-native-access=com.example.application"));

        String arguments = runJig(
                "--module-path",
                directory.resolve("modules").toString(),
                "-m",
                "com.example.application",
                "--validate-runtime-access",
                "--",
                "--enable-native-access",
                "com.example.application");
        assertEquals("", arguments);
    }

    @Test
    void selectedSourceModuleCanAuthorizeBinaryAccessRequirements(@TempDir Path directory) throws Exception {
        var library = Files.createDirectories(directory.resolve("modules/com.example.library"));
        var libraryDeclaration = ModuleAttribute.of(ModuleDesc.of("com.example.library"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        var access = ModuleRuntimeAccessOptions.newBuilder()
                .enableNativeAccess("com.example.library")
                .build();
        Files.write(library.resolve("module-info.class"),
                ClassFile.of().buildModule(libraryDeclaration, builder -> builder.with(ModuleRuntimeAccessAttribute.of(access))));

        var application = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                """
                /** @enableNativeAccess com.example.library */
                module com.example.application {
                    requires com.example.library;
                }
                """);

        var arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                directory.resolve("modules").toString(),
                "-m",
                "com.example.application",
                "--resolve-options",
                ACCESS_OPTIONS,
                "--validate-runtime-access");

        assertTrue(arguments.contains("--enable-native-access\ncom.example.library\n"));
    }

    @Test
    void releaseCompatibilityIsCheckedOnlyForSourceOptions(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        Path application = sources.resolve("com.example.application");
        Path library = sources.resolve("com.example.library");
        Files.createDirectories(application);
        Files.createDirectories(library);
        Files.writeString(application.resolve("module-info.java"),
                """
                /** @release 21 */
                module com.example.application {
                    requires com.example.library; // @1.0
                }
                """);
        Files.writeString(library.resolve("module-info.java"),
                """
                /** @release 25 */
                module com.example.library {}
                """);

        runJig("--module-source-path", sources.toString(), "-m", "com.example.application", "-m",
                "com.example.library", "--resolve-options", "add-modules");
        runJig("--module-source-path", sources.toString(), "-m", "com.example.application", "-m",
                "com.example.library", "--resolve-options", "enable-preview");

        var out = new StringWriter();
        var err = new StringWriter();
        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                sources.toString(),
                "-m",
                "com.example.application",
                "-m",
                "com.example.library",
                "--resolve-options",
                "module-source-path,release");
        assertEquals(1, result);
        assertTrue(err.toString().contains("different releases"),
                err.toString());
    }

    @Test
    void generatePomOptions() {
        Options consumer = Options.parse(new String[] {"--module-version", "1.2.3", "--generate-consumer-pom",
                "build/consumer", "-m", "com.example.app"});
        Options build = Options.parse(new String[] {"--generate-module-poms", "project", "-m", "com.example.app"});

        assertEquals("1.2.3", consumer.moduleVersion);
        assertEquals(Path.of("build/consumer"), consumer.consumerPomDirectory);
        assertEquals(Path.of("project"), build.modulePomRoot);
    }

    @Test
    void pomGenerationModesAreMutuallyExclusive() {
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--generate-module-poms",
                "project",
                "--generate-consumer-pom",
                "consumer",
                "--module-version",
                "1.0",
                "-m",
                "com.example.app");

        assertEquals(2, result);
        assertTrue(err.toString().contains("mutually exclusive"),
                err.toString());
    }

    @Test
    void consumerPomRequiresModuleVersion() {
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(new PrintWriter(out), new PrintWriter(err), "--generate-consumer-pom", "consumer",
                "-m", "com.example.app");

        assertEquals(2, result);
        assertTrue(err.toString().contains("--generate-consumer-pom requires --module-version"),
                err.toString());
    }

    @Test
    void moduleVersionIsAnIndependentOutputOption(@TempDir Path directory) throws Exception {
        Path module = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(module.resolve("module-info.java"), "module com.example.application {}\n");

        String arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example.application",
                "--module-version",
                "1.2.3",
                "--resolve-options",
                "module-version");

        assertEquals("--module-version\n1.2.3\n", arguments);
    }

    @Test
    void moduleVersionIsWrittenToCompiledSourceModule(@TempDir Path directory) throws Exception {
        Path source = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}\n");
        String arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example.application",
                "--module-version",
                "1.2.3",
                "--resolve-options",
                "module-path");

        var reference = ModuleFinder.of(optionPath(arguments, "--module-path"))
                .find("com.example.application")
                .orElseThrow();
        assertEquals("1.2.3",
                reference.descriptor()
                         .rawVersion()
                         .orElseThrow());
    }

    @Test
    void mainClassIsWrittenToCompiledSourceModule(@TempDir Path directory) throws Exception {
        Path source = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @mainClass com.example.Main */
                module com.example.application {}
                """);
        Path packageDirectory = Files.createDirectories(source.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Main.java"),
                """
                package com.example;
                public final class Main {
                    public static void main(String[] arguments) {}
                }
                """);
        String arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "-m", "com.example.application", "--resolve-options", "module-path");

        var reference = ModuleFinder.of(optionPath(arguments, "--module-path"))
                .find("com.example.application")
                .orElseThrow();
        assertEquals("com.example.Main",
                reference.descriptor()
                         .mainClass()
                         .orElseThrow());
    }

    @Test
    void targetPlatformUsesOsDetectorClassifierConvention() {
        Options options = Options.parse(new String[] {"--target-platform", "macos-aarch64", "--resolve-options",
                "module-path,add-modules", "-m", "com.example.tool"});

        assertEquals("osx-aarch_64", options.targetClassifier());
        assertEquals(Set.of("module-path", "add-modules"), options.resolveOptions);
        assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--target-platform", "plan9-mips", "-m", "com.example.tool"}));
    }

    @Test
    void currentTargetPlatformUsesHostOperatingSystemAndArchitecture() {
        assertEquals("macos-aarch64", Options.currentPlatform("Mac OS X", "aarch64"));
        assertEquals("macos-aarch64", Options.currentPlatform("Mac OS X", "arm64"));
        assertEquals("linux-x86_64", Options.currentPlatform("Linux", "amd64"));
        assertEquals("windows-x86_64", Options.currentPlatform("Windows 11", "x86_64"));

        Options options = Options.parse(new String[] {"--target-platform", "CURRENT", "-m", "com.example.tool"});
        String currentPlatform = Options.currentPlatform(System.getProperty("os.name"), System.getProperty("os.arch"));
        Options explicit = Options.parse(new String[] {"--target-platform", currentPlatform, "-m", "com.example.tool"});
        assertEquals(explicit.targetClassifier(), options.targetClassifier());

        assertThrows(IllegalArgumentException.class, () -> Options.currentPlatform("Plan 9", "mips"));
    }

    @Test
    void preferringJmodsDefaultsOnlyTheJmodTargetToCurrentPlatform() {
        Options options = Options.parse(new String[] {"--prefer-jmod", "-m", "com.example.tool"});
        Options explicit = Options.parse(new String[] {"--target-platform", "CURRENT", "-m", "com.example.tool"});

        assertNull(options.targetClassifier());
        assertEquals(explicit.targetClassifier(), options.jmodTargetClassifier());
    }

    @Test
    void targetPlatformMayOnlyBeSpecifiedOnce() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--target-platform", "CURRENT", "--target-platform", "linux-x86_64", "-m", "com.example.tool"}));

        assertEquals("--target-platform may only be specified once", exception.getMessage());
    }

    @Test
    void compileMaterializesASourceAnnotationProcessor(@TempDir Path directory) throws Exception {
        var application = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                """
                /** @processWith com.example.processor */
                module com.example.application {
                    requires static com.example.processor;
                }
                """);

        var processor = Files.createDirectories(directory.resolve("src/com.example.processor"));
        Files.writeString(processor.resolve("module-info.java"),
                """
                module com.example.processor {
                    requires java.compiler;
                    requires com.example.processor.support;
                    provides javax.annotation.processing.Processor
                            with com.example.processor.Implementation;
                }
                """);
        var processorPackage = Files.createDirectories(processor.resolve("com/example/processor"));
        Files.writeString(processorPackage.resolve("Implementation.java"),
                """
                package com.example.processor;

                import java.util.Set;
                import com.example.processor.support.Support;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;

                public final class Implementation extends AbstractProcessor {
                    @Override
                    public Set<String> getSupportedAnnotationTypes() {
                        return Support.annotationTypes();
                    }

                    @Override
                    public SourceVersion getSupportedSourceVersion() {
                        return SourceVersion.latestSupported();
                    }

                    @Override
                    public boolean process(
                            Set<? extends TypeElement> annotations,
                            RoundEnvironment roundEnvironment) {
                        return false;
                    }
                }
                """);

        var support = Files.createDirectories(directory.resolve("src/com.example.processor.support"));
        Files.writeString(support.resolve("module-info.java"),
                """
                module com.example.processor.support {
                    exports com.example.processor.support;
                }
                """);
        var supportPackage = Files.createDirectories(support.resolve("com/example/processor/support"));
        Files.writeString(supportPackage.resolve("Support.java"),
                """
                package com.example.processor.support;

                import java.util.Set;

                public final class Support {
                    private Support() {}

                    public static Set<String> annotationTypes() {
                        return Set.of("*");
                    }
                }
                """);

        var unrelated = Files.createDirectories(directory.resolve("src/com.example.unrelated"));
        Files.writeString(unrelated.resolve("module-info.java"), "module com.example.unrelated {}\n");

        var arguments = runJig("--module-source-path", directory.resolve("src").toString(),
                "-m", "com.example.application", "--resolve-options", COMPILE_OPTIONS, "--compile-time");

        var lines = arguments.lines().toList();
        var option = lines.indexOf("--processor-module-path");
        assertTrue(option >= 0, arguments);
        var processorPath = Arrays.stream(lines.get(option + 1).split(Pattern.quote(System.getProperty("path.separator"))))
                .map(Path::of)
                .collect(Collectors.toSet());
        var processorModules = ModuleFinder.of(processorPath.toArray(Path[]::new)).findAll().stream()
                .map(reference -> reference.descriptor().name())
                .collect(Collectors.toSet());
        assertEquals(Set.of("com.example.processor", "com.example.processor.support"), processorModules);
        assertFalse(processorModules.contains("com.example.unrelated"));
    }

    @Test
    void automaticProcessorUsesTheResolvedReadabilityClosure(@TempDir Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(sources.resolve("module-info.java"),
                """
                /** @processWith com.example.processor */
                module com.example.application {
                    requires static com.example.processor;
                }
                """);

        var processor = automaticProcessorJar(directory, "com.example.processor");
        var readable = automaticJar(directory, "com.example.readable");
        var arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                processor + System.getProperty("path.separator") + readable,
                "-m",
                "com.example.application",
                "--resolve-options",
                COMPILE_OPTIONS,
                "--compile-time");

        var lines = arguments.lines().toList();
        var option = lines.indexOf("--processor-module-path");
        assertTrue(option >= 0, arguments);
        var paths = Arrays.stream(lines.get(option + 1).split(Pattern.quote(System.getProperty("path.separator"))))
                .map(Path::of)
                .collect(Collectors.toSet());
        assertEquals(Set.of(processor, readable), paths);
    }

    @Test
    void compileIncludesOnlyDeclaredProcessorClosures(@TempDir Path directory) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("src/com.example.application"));
        Files.writeString(sources.resolve("module-info.java"),
                """
                /** @processWith com.example.processor */
                module com.example.application {
                    requires static com.example.processor; // @1.0
                    requires static com.example.unrelated; // @1.0
                }
                """);

        Path modules = Files.createDirectories(directory.resolve("modules"));
        Path processor = Files.createDirectories(modules.resolve("com.example.processor"));
        var processorAttribute = ModuleAttribute.of(
                ModuleDesc.of("com.example.processor"),
                builder -> {
                    builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null);
                    builder.requires(ModuleDesc.of("java.compiler"), Set.of(), null);
                    builder.requires(ModuleDesc.of("com.example.processor.dependency"), Set.of(), null);
                    builder.provides(ModuleProvideInfo.of(ClassDesc.of("javax.annotation.processing.Processor"), List.of(ClassDesc.of("com.example.processor.Implementation"))));
                });
        Files.write(
                processor.resolve("module-info.class"),
                ClassFile.of().buildModule(processorAttribute,
                        builder -> builder.with(ModulePackagesAttribute.ofNames(List.of(PackageDesc.of("com.example.processor"))))));

        for (String name : List.of("com.example.processor.dependency", "com.example.unrelated")) {
            Path module = Files.createDirectories(modules.resolve(name));
            var attribute = ModuleAttribute.of(ModuleDesc.of(name),
                    builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
            Files.write(module.resolve("module-info.class"),
                    ClassFile.of().buildModule(attribute, builder -> {}));
        }

        String arguments = runJig(
                "--module-source-path",
                directory.resolve("src").toString(),
                "--module-path",
                modules.toString(),
                "-m",
                "com.example.application",
                "--resolve-options",
                COMPILE_OPTIONS,
                "--compile-time");

        List<String> lines = arguments.lines().toList();
        int option = lines.indexOf("--processor-module-path");
        assertTrue(option >= 0, arguments);
        Set<Path> paths = Arrays.stream(lines.get(option + 1).split(Pattern.quote(System.getProperty("path.separator"))))
                .map(Path::of)
                .collect(Collectors.toSet());
        assertEquals(Set.of(processor, modules.resolve("com.example.processor.dependency")), paths);
        assertFalse(paths.contains(modules.resolve("com.example.unrelated")));
    }

    @Test
    void explicitJmodPreferenceUsesCanonicalJmodPath(@TempDir Path directory) throws Exception {
        Path module = Files.createDirectories(directory.resolve("com.example.tool"));
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.tool"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(
                module.resolve("module-info.class"),
                ClassFile.of().buildModule(moduleAttribute,
                        builder -> builder.withVersion(ClassFileFormatVersion.latest().major(), 0)));
        ModuleFinder finder = ModuleFinder.of(module);
        Configuration configuration = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(), Set.of("com.example.tool"));
        var resolution = new ModuleResolution(
                finder,
                configuration,
                new LinkedHashSet<>(Set.of("com.example.tool")),
                Set.of(),
                Map.of(),
                Map.of("com.example.tool", "1.0"),
                Set.of(),
                Map.of(),
                Map.of());
        Path jmod = directory.resolve("com.example.tool-1.0-osx-aarch_64.jmod");
        Options options = Options.parse(new String[] {"--prefer-jmod", "--resolve-options", "module-path,add-modules", "-m", "com.example.tool"});

        String arguments = Jig.renderArguments(
                Set.of("module-path", "add-modules"),
                options,
                resolution,
                Set.of(),
                new RepositoryPaths(Map.of(), Map.of("com.example.tool", jmod)));

        assertTrue(arguments.contains(jmod.toString()), arguments);
        assertTrue(arguments.contains("--add-modules\ncom.example.tool\n"), arguments);
        assertFalse(arguments.lines()
                             .anyMatch(module.toString()::equals),
                arguments);

        String compileArguments = Jig.renderArguments(
                Set.of("module-path", "upgrade-module-path", "release"),
                options,
                resolution,
                Set.of(),
                new RepositoryPaths(Map.of(), Map.of("com.example.tool", jmod)));
        assertTrue(compileArguments.contains(jmod.toString()), compileArguments);
        assertFalse(compileArguments.lines()
                .anyMatch(module.toString()::equals),
                compileArguments);

        Path fallbackJar = directory.resolve("com.example.tool-1.0-osx-aarch_64.jar");
        String fallbackArguments = Jig.renderArguments(
                Set.of("module-path", "add-modules"),
                options,
                resolution,
                Set.of(),
                new RepositoryPaths(Map.of("com.example.tool", fallbackJar), Map.of()));
        assertTrue(fallbackArguments.contains(fallbackJar.toString()), fallbackArguments);
    }

    @Test
    void compilationUpgradesResolvedSystemModules(@TempDir Path directory) throws Exception {
        Path replacement = automaticJar(directory, "java.logging");
        ModuleFinder finder = ModuleFinder.of(replacement);
        var resolution = new ModuleResolution(
                finder,
                ModuleLayer.boot().configuration(),
                new LinkedHashSet<>(),
                Set.of(),
                Map.of(),
                Map.of("java.logging", "1.0"),
                Set.of("java.logging"),
                Map.of(),
                Map.of());
        Options options = Options.parse(new String[] {"--resolve-options", COMPILE_OPTIONS, "--compile-time"});

        String arguments = Jig.renderArguments(options.resolveOptions, options, resolution, Set.of(),
                new RepositoryPaths(Map.of("java.logging", replacement), Map.of()));

        assertTrue(arguments.contains("--upgrade-module-path\n" + replacement + "\n"), arguments);
        assertFalse(arguments.contains("--module-path\n" + replacement + "\n"), arguments);
    }

    @Test
    void modulePathUsesJarWithoutJmodPreference(@TempDir Path directory) throws Exception {
        Path module = Files.createDirectories(directory.resolve("com.example.tool"));
        var moduleAttribute = ModuleAttribute.of(ModuleDesc.of("com.example.tool"),
                builder -> builder.requires(ModuleDesc.of("java.base"), Set.of(AccessFlag.MANDATED), null));
        Files.write(
                module.resolve("module-info.class"),
                ClassFile.of().buildModule(moduleAttribute,
                        builder -> builder.withVersion(ClassFileFormatVersion.latest().major(), 0)));
        ModuleFinder finder = ModuleFinder.of(module);
        Configuration configuration = Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(), Set.of("com.example.tool"));
        var resolution = new ModuleResolution(
                finder,
                configuration,
                new LinkedHashSet<>(Set.of("com.example.tool")),
                Set.of(),
                Map.of(),
                Map.of("com.example.tool", "1.0"),
                Set.of(),
                Map.of(),
                Map.of());
        Options options = Options.parse(new String[] {"--resolve-options", "module-path,add-modules", "-m", "com.example.tool"});

        String arguments = Jig.renderArguments(
                Set.of("module-path", "add-modules"),
                options,
                resolution,
                Set.of(),
                new RepositoryPaths(Map.of(), Map.of()));

        assertTrue(arguments.lines()
                            .anyMatch(module.toString()::equals),
                arguments);
    }

    @Test
    void generatesPomForEachSelectedSourceModule(@TempDir Path directory) throws Exception {
        Path sources = directory.resolve("src");
        Path application = sources.resolve("com.example.application");
        Path library = sources.resolve("com.example.library");
        Files.createDirectories(application);
        Files.createDirectories(library);
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires com.example.library; // @1.2.3
                }
                """);
        Files.writeString(library.resolve("module-info.java"),
                """
                module com.example.library {}
                """);
        Path poms = directory.resolve("poms");
        Path compileArguments = directory.resolve("compile.args");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = new Jig().run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                sources.toString(),
                "-m",
                "com.example.application",
                "-m",
                "com.example.library",
                "--module-version",
                "1.2.3",
                "--generate-consumer-pom",
                poms.toString(),
                "--compile-time",
                "--resolve-options",
                "module-path,module-source-path,module=list,module-version",
                "--write-argfile",
                compileArguments.toString());

        assertEquals(0, result, err.toString());
        String compileOptions = Files.readString(compileArguments);
        assertTrue(compileOptions.contains("--module-source-path"));
        assertTrue(compileOptions.contains("--module-version\n1.2.3\n"), compileOptions);
        Model applicationPom;
        try (var input = Files.newInputStream(poms.resolve("com.example.application/com.example.application-1.2.3.pom"))) {
            applicationPom = new MavenStaxReader().read(input);
        }
        assertEquals("com.example", applicationPom.getGroupId());
        assertEquals("com.example.application", applicationPom.getArtifactId());
        assertEquals("1.2.3", applicationPom.getVersion());
        assertEquals(1, applicationPom.getDependencies()
                .size());
        var dependency = applicationPom.getDependencies().getFirst();
        assertEquals("com.example", dependency.getGroupId());
        assertEquals("com.example.library", dependency.getArtifactId());
        assertEquals("1.2.3", dependency.getVersion());

        Model libraryPom;
        try (var input = Files.newInputStream(poms.resolve("com.example.library/com.example.library-1.2.3.pom"))) {
            libraryPom = new MavenStaxReader().read(input);
        }
        assertEquals("com.example.library", libraryPom.getArtifactId());
        assertEquals("1.2.3", libraryPom.getVersion());
    }

    // -- resolved options --

    @Test
    void resolveOptionsAcceptsLongAndShortForms() {
        assertEquals(Set.of("module-path", "add-modules"), Options.parse(new String[] {"--resolve-options", "module-path,add-modules", "-m", "app"}).resolveOptions);
        assertEquals(Set.of("module-path", "add-modules"), Options.parse(new String[] {"-r", "module-path,add-modules", "-m", "app"}).resolveOptions);
    }

    @Test
    void oldOutputOptionNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--options", "module-path", "-m", "app"}));
        assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"-o", "module-path", "-m", "app"}));
    }

    @Test
    void compileTimeSelectsStaticRequirements() {
        assertTrue(Jig.shouldIncludeStatics(Options.parse(new String[] {"--resolve-options", "module-path", "--compile-time", "-m", "app"})));
        assertFalse(Jig.shouldIncludeStatics(Options.parse(new String[] {"--resolve-options", "module-path", "-m", "app"})));
    }

    @Test
    void sourceResolutionIncludesStaticRequirementsForInternalCompilation() {
        assertTrue(Jig.shouldIncludeStatics(Options.parse(new String[] {"--module-source-path", "src", "--resolve-options",
                "module-path", "-m", "app"})));
    }

    @Test
    void modulePomGenerationIncludesDependencySources() {
        assertTrue(Jig.shouldIncludeSources(Options.parse(new String[] {"--generate-module-poms", "project", "-m", "app"})));
        assertFalse(Jig.shouldIncludeSources(Options.parse(new String[] {"--generate-consumer-pom", "consumer", "--module-version",
                "1.0", "-m", "app"})));
    }

    @Test
    void unknownOutputOptionIsRejected() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--resolve-options", "unknown", "-m", "app"}));
        assertTrue(exception.getMessage()
                            .contains("unknown resolve options"));
    }

    @Test
    void unknownModuleFormIsRejected() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--resolve-options", "module=unknown", "-m", "app"}));
        assertTrue(exception.getMessage()
                            .contains("unknown module form"));
    }

    @Test
    void rootModuleFormRequiresAddModules() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--resolve-options", "module=roots", "-m", "app"}));
        assertTrue(exception.getMessage()
                            .contains("module=roots requires add-modules"));
    }

    @Test
    void conflictingModuleFormsAreRejected() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--resolve-options", "module,module=list", "-m", "app"}));
        assertTrue(exception.getMessage()
                            .contains("conflicting forms"));
    }

    @Test
    void verboseEnablesDiagnosticTracing() {
        Options opts = Options.parse(new String[] {"--verbose", "-m", "app"});
        assertTrue(opts.verbose);
    }

    // -- error cases --

    @Test
    void writeArgumentFileRequiresOptions() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--write-argfile", "run.args", "-m", "app"}));
        assertTrue(exception.getMessage()
                            .contains("--write-argfile requires --resolve-options"));
    }

    @Test
    void onlyOneArgumentFileCanBeWrittenPerInvocation() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(new String[] {"--resolve-options", "module-path", "--write-argfile", "one.args", "--write-argfile", "two.args",
                        "-m", "app"}));
        assertTrue(exception.getMessage()
                            .contains("--write-argfile may only be specified once"));
    }

    @Test
    @DisplayName("positional arg is rejected")
    void positionalArgRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"com.example.app"}));
        assertTrue(ex.getMessage().contains("unexpected argument"),
                ex.getMessage());
    }

    @Test
    void executionOptionsAreRejected() {
        for (String option : List.of("--launch", "--tool")) {
            assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {option}), option);
        }
    }

    @Test
    @DisplayName("missing option argument fails")
    void missingOptionArg() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--module-path"}));
    }

    private static Path automaticJar(Path directory, String moduleName) throws Exception {
        Path jar = directory.resolve(moduleName + "-1.0.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(jar), manifest)) {}
        return jar;
    }

    private static Path automaticProcessorJar(Path directory, String moduleName) throws Exception {
        var jar = directory.resolve(moduleName + "-1.0.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("com/example/processor/Implementation.class"));
            output.write(new byte[] {0});
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor"));
            output.write("com.example.processor.Implementation\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static Path optionPath(String arguments, String option) {
        var lines = arguments.lines().toList();
        return Path.of(lines.get(lines.indexOf(option) + 1));
    }

    private static String runJig(String... arguments) {
        var out = new StringWriter();
        var err = new StringWriter();
        int result = new Jig().run(new PrintWriter(out), new PrintWriter(err), arguments);
        assertEquals(0, result, err.toString());
        return out.toString();
    }

    @Test
    @DisplayName("no root module prints a diagnostic and usage and exits 2")
    void noRootModuleUsage() {
        var jig = new Jig();
        var out = new StringWriter();
        var err = new StringWriter();
        int rc = jig.run(new PrintWriter(out), new PrintWriter(err), "--module-path", "/path/to/modules",
                "--resolve-options", "module-path");
        assertEquals(2, rc);
        assertEquals(
                "jig: no root module specified; use --module, --add-modules, or --add-requires\n",
                err.toString());
        assertTrue(out.toString()
                      .contains("Usage: jig"));
    }
}
