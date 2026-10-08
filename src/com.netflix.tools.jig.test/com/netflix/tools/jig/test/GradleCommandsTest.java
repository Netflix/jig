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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;

import com.netflix.tools.jig.Jig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleCommandsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void helpDescribesTheStandaloneContract() {
        var result = run("gradle", "--help");
        assertEquals(0, result.exitCode(), result.error());
        for (String option : List.of("--root-project-dir", "--list-project-dirs", "--project-dir",
                "--scope", "--resolve-options", "--write-argfile")) {
            assertTrue(result.output()
                             .contains(option),
                    option);
        }
        assertFalse(result.output()
                          .contains("--source-set"));
        assertEquals("", result.error());
    }

    @Test
    void requiresExplicitLocationsAndAResolutionScope() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        assertInvalid("--root-project-dir", "gradle", "--list-project-dirs");
        assertInvalid("--project-dir", "gradle", "--root-project-dir", root.toString(), "--scope",
                "compile", "--resolve-options", "source-path");
        assertInvalid("--scope", "gradle", "--root-project-dir", root.toString(), "--project-dir",
                root.toString(), "--resolve-options", "source-path");
        assertInvalid(
                "compile or runtime",
                "gradle",
                "--root-project-dir",
                root.toString(),
                "--project-dir",
                root.toString(),
                "--scope",
                "something",
                "--resolve-options",
                "source-path");
        assertInvalid("unknown option", "gradle", "-C", root.toString());
        assertInvalid("unknown option", "gradle", "--source-set", "main");
        assertInvalid("mutually exclusive", "gradle", "--root-project-dir", root.toString(), "--list-project-dirs",
                "--resolve-options", "source-path");
        assertInvalid("--write-argfile requires --resolve-options", "gradle", "--root-project-dir",
                root.toString(), "--list-project-dirs", "--write-argfile", "out.args");
    }

    @Test
    void completesTheGradleCommandAndItsOptions() {
        var command = run("__complete", "grad");
        assertEquals(0, command.exitCode(), command.error());
        assertTrue(command.output().contains("gradle\t"),
                command.output());
        var option = run("__complete", "gradle", "--root");
        assertEquals(0, option.exitCode(), option.error());
        assertTrue(option.output().contains("--root-project-dir\t"),
                option.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryListsActualProjectDirectoriesWithoutBuildOutputOnStdout() throws Exception {
        var fixture = fixture();
        var result = run("gradle", "--root-project-dir",
                fixture.root().toString(), "--list-project-dirs");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(fixture.root() + "\n" + fixture.project() + "\n", result.output());
        assertTrue(result.error().contains("Gradle build output"),
                result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolutionUsesDirectoriesFromDiscoveryAndProjectsRequestedOptions() throws Exception {
        var fixture = fixture();
        var result = resolve(fixture, "compile", "source-path,class-path,release");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                List.of(
                        "--class-path",
                        fixture.root()
                               .resolve("compile only.jar")
                               .toString(),
                        "--source-path",
                        fixture.project()
                               .resolve("custom sources")
                               .toString(),
                        "--release",
                        "17"),
                arguments(result));
        String invocation = Files.readString(fixture.root()
                .resolve("invocation.txt"));
        assertTrue(invocation.contains("--project-dir\n" + fixture.root()), invocation);
        assertTrue(invocation.contains("-Pjig.gradle.project-dir=" + fixture.project()), invocation);
        assertTrue(invocation.contains("-Pjig.gradle.scope=compile"), invocation);
        assertFalse(result.output()
                          .contains("Gradle build output"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compileAndRuntimeScopesAreDifferentViews() throws Exception {
        var fixture = fixture();
        var compile = resolve(fixture, "compile", "class-path");
        var runtime = resolve(fixture, "runtime", "class-path");
        assertEquals(0, compile.exitCode(), compile.error());
        assertEquals(0, runtime.exitCode(), runtime.error());
        assertEquals(
                List.of("--class-path",
                        fixture.root()
                               .resolve("compile only.jar")
                               .toString()),
                arguments(compile));
        assertEquals(
                List.of("--class-path",
                        fixture.root()
                               .resolve("runtime only.jar")
                               .toString()),
                arguments(runtime));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void absentScopeContentProducesNoArguments() throws Exception {
        var fixture = fixture();
        writeCapture(fixture.root().resolve("compile.properties"),
                fixture.root(), fixture.project(), false, false);
        var result = resolve(fixture, "compile", "class-path,source-path,release");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writesTheSameArgumentFileInsteadOfStdout() throws Exception {
        var fixture = fixture();
        Path file = temporaryDirectory.resolve("options.args");
        var stdout = resolve(fixture, "compile", "source-path,release");
        var result = run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-dir",
                fixture.project().toString(),
                "--scope",
                "compile",
                "-r",
                "source-path,release",
                "-w",
                file.toString());
        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.output());
        assertEquals(stdout.output(), Files.readString(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsUnknownOptionsBeforeInvokingGradle() throws Exception {
        var fixture = fixture();
        var result = resolve(fixture, "compile", "not-a-java-option");
        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("unknown resolve options"),
                result.error());
        assertFalse(Files.exists(fixture.root()
                .resolve("invocation.txt")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aFailedBuildDoesNotReturnCapturedArguments() throws Exception {
        var fixture = fixture();
        Files.writeString(fixture.root()
                .resolve("gradlew"),
                "#!/bin/sh\necho 'configuration failed' >&2\nexit 7\n");
        var result = resolve(fixture, "compile", "source-path");
        assertEquals(7, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains("configuration failed"),
                result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void modulePathUsesGradlesModuleDetectionPolicy() throws Exception {
        var fixture = fixture();
        Path plain = jar("plain.jar", null, false, false);
        Path automatic = jar("automatic.jar", "automatic.mod", false, false);
        Path explicit = jar("explicit.jar", null, true, false);
        Path multiRelease = jar("multi-release.jar", null, true, true);
        Path notMultiRelease = jar("not-multi-release.jar", null, true, null);
        Path exploded = Files.createDirectories(temporaryDirectory.resolve("exploded"));
        Files.write(exploded.resolve("module-info.class"), new byte[0]);
        Properties values = capture(fixture);
        values.setProperty("modular", "true");
        putList(
                values,
                "classpath",
                List.of(
                        plain.toString(),
                        automatic.toString(),
                        explicit.toString(),
                        multiRelease.toString(),
                        notMultiRelease.toString(),
                        exploded.toString()));
        storeCapture(fixture, values);
        var result = resolve(fixture, "compile", "class-path,module-path");
        assertEquals(0, result.exitCode(), result.error());
        String separator = System.getProperty("path.separator");
        assertEquals(
                List.of(
                        "--class-path",
                        plain + separator + notMultiRelease,
                        "--module-path",
                        String.join(
                                separator,
                                List.of(automatic.toString(), explicit.toString(), multiRelease.toString(),
                                        exploded.toString()))),
                arguments(result));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void projectsOnlyRequestedAdditionalOptionsAndPreservesRepeatedAccessOptions() throws Exception {
        var fixture = fixture();
        Properties values = capture(fixture);
        putList(
                values,
                "additional",
                List.of("-Xlint:all", "--enable-preview", "--add-exports", "java.base/a=ALL-UNNAMED", "--add-exports=java.base/b=ALL-UNNAMED", "--add-opens",
                        "java.base/c=ALL-UNNAMED"));
        storeCapture(fixture, values);
        var result = resolve(fixture, "compile", "enable-preview,add-exports");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(
                List.of("--enable-preview", "--add-exports", "java.base/a=ALL-UNNAMED", "--add-exports=java.base/b=ALL-UNNAMED"),
                arguments(result));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void argumentFileQuotingIsAcceptedByJavac() throws Exception {
        var fixture = fixture();
        Path sources = Files.createDirectories(temporaryDirectory.resolve("sources with \"quotes\" and \\ slash\nnew line\ttab"));
        Files.writeString(sources.resolve("Dependency.java"), "public class Dependency {}\n");
        Path main = temporaryDirectory.resolve("Main.java");
        Files.writeString(main, "public class Main { Dependency dependency; }\n");
        Properties values = capture(fixture);
        putList(values, "sources", List.of(sources.toString()));
        storeCapture(fixture, values);
        Path file = temporaryDirectory.resolve("quoted.args");
        var result = run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-dir",
                fixture.project().toString(),
                "--scope",
                "compile",
                "-r",
                "source-path",
                "-w",
                file.toString());
        assertEquals(0, result.exitCode(), result.error());
        var diagnostics = new StringWriter();
        int status = ToolProvider.findFirst("javac")
                .orElseThrow()
                .run(
                        new PrintWriter(diagnostics),
                        new PrintWriter(diagnostics),
                        "@" + file,
                        "-d",
                        temporaryDirectory.resolve("classes").toString(),
                        main.toString());
        assertEquals(0, status, diagnostics.toString());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void invalidCaptureDoesNotReturnPartialArguments() throws Exception {
        var fixture = fixture();
        Properties values = capture(fixture);
        values.setProperty("classpath.count", "2");
        storeCapture(fixture, values);
        var result = resolve(fixture, "compile", "class-path,release");
        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Missing Gradle capture entry: classpath.1"),
                result.error());
        assertEquals("", result.output());
    }

    private Path jar(String name, String module, boolean descriptor,
                     Boolean multiRelease)
            throws Exception {
        Path path = temporaryDirectory.resolve(name);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        if (module != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        }
        if (Boolean.TRUE.equals(multiRelease)) {
            manifest.getMainAttributes().put(Name.MULTI_RELEASE, "true");
        }
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (descriptor) {
                output.putNextEntry(new JarEntry(Boolean.FALSE.equals(multiRelease) ? "module-info.class" : "META-INF/versions/9/module-info.class"));
                output.closeEntry();
            }
        }
        return path;
    }

    private static Properties capture(Fixture fixture) throws Exception {
        var values = new Properties();
        try (var input = Files.newInputStream(fixture.root()
                .resolve("compile.properties"))) {
            values.load(input);
        }
        return values;
    }

    private static void storeCapture(Fixture fixture, Properties values) throws Exception {
        try (var output = Files.newOutputStream(fixture.root()
                .resolve("compile.properties"))) {
            values.store(output, "fixture");
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void equivalentRequestsUseStableConfigurationCacheInputs() throws Exception {
        var fixture = fixture();
        var first = resolve(fixture, "compile", "source-path,release");
        assertEquals(0, first.exitCode(), first.error());
        Path invocationFile = fixture.root().resolve("invocation.txt");
        String invocation = Files.readString(invocationFile);
        var second = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-dir", fixture.project().toString(),
                "--scope", "compile", "-r", "release,source-path", "-w", temporaryDirectory.resolve("equivalent.args").toString());
        assertEquals(0, second.exitCode(), second.error());
        assertEquals(invocation, Files.readString(invocationFile));
        assertFalse(invocation.contains("configuration-cache=false"), invocation);
        assertEquals(first.output(), Files.readString(temporaryDirectory.resolve("equivalent.args")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void missingCaptureCannotReturnAStaleResultFromAnEarlierQuery() throws Exception {
        var fixture = fixture();
        var first = resolve(fixture, "compile", "source-path");
        assertEquals(0, first.exitCode(), first.error());
        Files.writeString(fixture.root().resolve("gradlew"), "#!/bin/sh\nexit 0\n");
        var second = resolve(fixture, "compile", "source-path");
        assertEquals(1, second.exitCode(), second.error());
        assertEquals("", second.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void concurrentQueriesSerializeAccessToTheirInterchangeFile() throws Exception {
        var fixture = fixture();
        Path wrapper = fixture.root().resolve("gradlew");
        Files.writeString(wrapper, Files.readString(wrapper).replace("scope=compile", """
                if ! mkdir active-query; then
                    echo 'concurrent query' >&2
                    exit 11
                fi
                trap 'rmdir active-query' EXIT
                sleep 0.1
                scope=compile
                """));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var queries = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < 2; i++) {
                queries.add(executor.submit(() -> {
                    start.await();
                    return resolve(fixture, "compile", "source-path");
                }));
            }
            start.countDown();
            Result first = queries.getFirst().get();
            assertEquals(0, first.exitCode(), first.error());
            Result second = queries.getLast().get();
            assertEquals(0, second.exitCode(), second.error());
            assertEquals(first.output(), second.output());
        }
    }

    private Fixture fixture() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root with spaces")).toRealPath();
        Path project = Files.createDirectories(root.resolve("custom project")).toRealPath();
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        writeCapture(root.resolve("compile.properties"), root, project, true, false);
        writeCapture(root.resolve("runtime.properties"), root, project, true, true);
        Files.writeString(root.resolve("gradlew"),
                """
                #!/bin/sh
                printf '%s\\n' "$@" > invocation.txt
                echo 'Gradle build output'
                scope=compile
                for argument do
                    case "$argument" in
                        -Pjig.gradle.output=*) output=${argument#*=} ;;
                        -Pjig.gradle.scope=*) scope=${argument#*=} ;;
                    esac
                done
                cp "$scope.properties" "$output"
                """);
        return new Fixture(root, project);
    }

    private static void writeCapture(Path file, Path root, Path project,
            boolean present, boolean runtime)
            throws Exception {
        var values = new Properties();
        values.setProperty("version", "1");
        putList(values, "projects",
                List.of(root.toString(), project.toString()));
        putList(values, "sources",
                present ? List.of(project.resolve("custom sources").toString()) : List.of());
        putList(values, "classpath",
                present ? List.of(root.resolve(runtime ? "runtime only.jar" : "compile only.jar").toString()) : List.of());
        putList(values, "processors", List.of());
        putList(values, "additional", List.of());
        values.setProperty("modular", "false");
        if (present) {
            values.setProperty("release", "17");
        }
        try (var output = Files.newOutputStream(file)) {
            values.store(output, "fixture");
        }
    }

    private static void putList(Properties values, String name, List<String> elements) {
        values.setProperty(name + ".count", Integer.toString(elements.size()));
        for (int i = 0; i < elements.size(); i++) {
            values.setProperty(name + "." + i, elements.get(i));
        }
    }

    private Result resolve(Fixture fixture, String scope, String options) {
        return run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-dir",
                fixture.project().toString(),
                "--scope",
                scope,
                "--resolve-options",
                options);
    }

    private void assertInvalid(String message, String... arguments) {
        var result = run(arguments);
        assertEquals(2, result.exitCode(), result.error());
        assertTrue(result.error().contains(message),
                result.error());
        assertEquals("", result.output());
    }

    private static Result run(String... arguments) {
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jig().run(new PrintWriter(output, true), new PrintWriter(error, true), arguments);
        return new Result(code, output.toString(), error.toString());
    }

    private static List<String> arguments(Result result) {
        var tokens = new ArrayList<String>();
        for (String line : result.output()
                .lines()
                .toList()) {
            if (line.startsWith("\"") && line.endsWith("\"")) {
                line = line.substring(1, line.length() - 1)
                           .replace("\\\"", "\"")
                           .replace("\\\\", "\\");
            }
            tokens.add(line);
        }
        return tokens;
    }

    private record Fixture(Path root, Path project) {}

    private record Result(int exitCode, String output, String error) {}
}
