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
import java.util.spi.ToolProvider;

import com.netflix.tools.jig.Jig;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleCommandsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void helpDescribesTheStandaloneContract() {
        var result = run("gradle", "--help");
        assertEquals(0, result.exitCode(), result.error());
        for (String option : List.of("--root-project-dir", "--list-project-paths", "--project-path", "--list-source-sets",
                "--source-set", "--classpath", "--resolve-options", "--resolve-compiler-options", "--write-argfile")) {
            assertTrue(result.output().contains(option), option);
        }
        assertFalse(result.output().contains("--scope"));
        assertEquals("", result.error());
    }

    @Test
    void requiresExplicitLocationsSourceSetAndClasspath() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        assertInvalid("--root-project-dir", "gradle", "--list-project-paths");
        assertInvalid("--project-path", "gradle", "--root-project-dir", root.toString(), "--source-set", "main",
                "--classpath", "compile", "--resolve-options", "source-path");
        assertInvalid("--source-set", "gradle", "--root-project-dir", root.toString(), "--project-path", ":",
                "--classpath", "compile", "--resolve-options", "source-path");
        assertInvalid("--classpath", "gradle", "--root-project-dir", root.toString(), "--project-path", ":",
                "--source-set", "main", "--resolve-options", "source-path");
        assertInvalid("compile or runtime", "gradle", "--root-project-dir", root.toString(), "--project-path", ":",
                "--source-set", "main", "--classpath", "something", "--resolve-options", "source-path");
        assertInvalid("unknown option", "gradle", "-C", root.toString());
        assertInvalid("unknown option", "gradle", "--scope", "compile");
        assertInvalid("requires NAME", "gradle", "--root-project-dir", root.toString(), "--project-path", ":",
                "--source-set", "", "--classpath", "compile", "-r", "source-path");
        assertInvalid("must not be empty", "gradle", "--root-project-dir", root.toString(), "--project-path", ":",
                "--source-set", " ", "--classpath", "compile", "-r", "source-path");
        for (String path : List.of("app", "::app", ":app:")) {
            assertInvalid("absolute Gradle project path", "gradle", "--root-project-dir", root.toString(),
                    "--project-path", path, "--list-source-sets");
        }
        assertInvalid("only to source-set discovery or argument resolution", "gradle", "--root-project-dir",
                root.toString(), "--list-project-paths", "--project-path", ":app");
        assertInvalid("mutually exclusive", "gradle", "--root-project-dir", root.toString(), "--list-project-paths", "--list-source-sets");
        assertInvalid("mutually exclusive", "gradle", "--root-project-dir", root.toString(), "--list-source-sets", "-r", "source-path");
        assertInvalid("--project-path", "gradle", "--root-project-dir", root.toString(), "--list-source-sets");
        assertInvalid("only to argument resolution", "gradle", "--root-project-dir", root.toString(), "--project-path", ":",
                "--list-source-sets", "--source-set", "test");
        assertInvalid("--write-argfile requires --resolve-options", "gradle", "--root-project-dir",
                root.toString(), "--list-project-paths", "--write-argfile", "out.args");
    }

    @Test
    void completesTheGradleCommandAndItsOptions() {
        var command = run("__complete", "grad");
        assertEquals(0, command.exitCode(), command.error());
        assertTrue(command.output().contains("gradle\t"),
                command.output());
        var option = run("__complete", "gradle", "--root");
        assertEquals(0, option.exitCode(), option.error());
        assertTrue(option.output().contains("--root-project-dir\t"), option.output());
        assertTrue(run("__complete", "gradle", "--source-s").output().contains("--source-set\t"));
        assertTrue(run("__complete", "gradle", "--classpath").output().contains("--classpath\t"));
        assertTrue(run("__complete", "gradle", "--resolve-c").output().contains("--resolve-compiler-options\t"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryListsProjectPathsWithoutBuildOutputOnStdout() throws Exception {
        var fixture = fixture();
        var result = run("gradle", "--root-project-dir",
                fixture.root().toString(), "--list-project-paths");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(":\n:app\n", result.output());
        assertTrue(result.error().contains("Gradle build output"),
                result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolutionUsesProjectPathsFromDiscoveryAndProjectsRequestedOptions() throws Exception {
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
        assertTrue(invocation.contains("-Pjig.gradle.project-path=:app"), invocation);
        assertTrue(invocation.contains("-Pjig.gradle.source-set=main"), invocation);
        assertTrue(invocation.contains("-Pjig.gradle.classpath=compile"), invocation);
        assertFalse(result.output()
                          .contains("Gradle build output"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compileAndRuntimeClasspathsAreDifferentViews() throws Exception {
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
    void emptySourceSetContentProducesNoArguments() throws Exception {
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
                "--project-path",
                ":app",
                "--source-set", "main", "--classpath",
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
                """
                #!/bin/sh
                printf 'jig-gradle:'
                base64 < compile.properties | tr -d '\\n'
                printf '\\n'
                echo 'configuration failed' >&2
                exit 7
                """);
        var result = resolve(fixture, "compile", "source-path");
        assertEquals(7, result.exitCode());
        assertEquals("", result.output());
        assertTrue(result.error().contains("configuration failed"),
                result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void preservesGradlesCapturedPathsWithoutReclassifyingBinaries() throws Exception {
        var fixture = fixture();
        Path classpath = temporaryDirectory.resolve("classpath.jar");
        // Rendering must not open the binary or repeat module detection locally.
        Files.writeString(classpath, "opaque captured binary");
        Path modulepath = temporaryDirectory.resolve("modulepath.jar");
        Properties values = capture(fixture);
        values.setProperty("modular", "true");
        putList(values, "classpath", List.of(classpath.toString()));
        putList(values, "module-path", List.of(modulepath.toString()));
        storeCapture(fixture, values);
        var result = resolve(fixture, "compile", "class-path,module-path");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(List.of("--class-path", classpath.toString(), "--module-path", modulepath.toString()), arguments(result));
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
                "--project-path",
                ":app",
                "--source-set", "main", "--classpath",
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
    void requestsUseOnlyTheCachedScriptArtifactAndProcessResponse() throws Exception {
        var fixture = fixture();
        var result = resolve(fixture, "compile", "source-path");
        assertEquals(0, result.exitCode(), result.error());
        List<String> invocation = Files.readAllLines(fixture.root().resolve("invocation.txt"));
        Path script = Path.of(invocation.get(invocation.indexOf("--init-script") + 1));
        Path cache = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv())
                .toAbsolutePath().normalize();
        assertTrue(script.startsWith(cache), script.toString());
        assertTrue(Files.isRegularFile(script));
        assertTrue(script.getFileName().toString().matches("[0-9a-f]{64}\\.gradle"), script.toString());
        assertEquals(":app:_jigCapture", invocation.getLast());
        assertFalse(invocation.stream().anyMatch(argument -> argument.startsWith("-Pjig.gradle.output=") || argument.startsWith("-Pjig.gradle.task=")));
        assertFalse(Files.exists(fixture.root().resolve(".gradle/jig")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void invalidProcessCaptureDoesNotReturnArguments() throws Exception {
        var fixture = fixture();
        Files.writeString(fixture.root().resolve("gradlew"), "#!/bin/sh\nprintf 'jig-gradle:not-base64!\\n'\n");
        var result = resolve(fixture, "compile", "source-path");
        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("Invalid Gradle capture"), result.error());
        assertEquals("", result.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void equivalentRequestsUseStableConfigurationCacheInputs() throws Exception {
        var fixture = fixture();
        var first = resolve(fixture, "compile", "source-path,release");
        assertEquals(0, first.exitCode(), first.error());
        Path invocationFile = fixture.root().resolve("invocation.txt");
        String invocation = Files.readString(invocationFile);
        var second = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--classpath", "compile", "-r", "release,source-path", "-w", temporaryDirectory.resolve("equivalent.args").toString());
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
    void concurrentQueriesHaveIndependentProcessResponsesWithoutSerialization() throws Exception {
        var fixture = fixture();
        Path wrapper = fixture.root().resolve("gradlew");
        Files.writeString(wrapper, Files.readString(wrapper).replace("classpath=compile", """
                touch "ready-$$"
                attempts=0
                while [ "$(find . -name 'ready-*' | wc -l)" -lt 2 ]; do
                    attempts=$((attempts + 1))
                    if [ "$attempts" -ge 100 ]; then
                        echo 'queries were serialized' >&2
                        exit 11
                    fi
                    sleep 0.02
                done
                classpath=compile
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

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryListsSourceSetsForTheSelectedProject() throws Exception {
        var fixture = fixture();
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--list-source-sets");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals("integrationTest\nmain\ntest\n", result.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sourceSetSelectionIsPassedToGradle() throws Exception {
        var fixture = fixture();
        var main = resolve(fixture, "compile", "source-path");
        String first = Files.readString(fixture.root().resolve("invocation.txt"));
        var test = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "test", "--classpath", "compile", "-r", "source-path");
        assertEquals(0, main.exitCode(), main.error());
        assertEquals(0, test.exitCode(), test.error());
        String second = Files.readString(fixture.root().resolve("invocation.txt"));
        assertNotEquals(first, second);
        assertTrue(second.contains("-Pjig.gradle.source-set=test"), second);
        var unknown = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "missing", "--classpath", "compile", "-r", "source-path");
        assertEquals(2, unknown.exitCode(), unknown.error());
        assertEquals("", unknown.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compilerAndMetadataResponsesAreMergedWithoutPublishingTransportRecords() throws Exception {
        var fixture = fixture();
        var compiler = new Properties();
        List<String> options = List.of("-g:none", "-Akey=value with spaces");
        putList(compiler, "compiler-options", options);
        try (var output = Files.newOutputStream(fixture.root().resolve("compiler.properties"))) {
            compiler.store(output, "compiler");
        }
        Path wrapper = fixture.root().resolve("gradlew");
        Files.writeString(wrapper, Files.readString(wrapper).replace("echo 'Gradle build output'", """
                echo 'Gradle build output'
                printf 'jig-gradle:'
                base64 < compiler.properties | tr -d '\\n'
                printf '\\n'
                """));
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(options, arguments(result));
        assertTrue(result.error().contains("Gradle build output"), result.error());
        assertFalse(result.error().contains("jig-gradle:"), result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compilerOptionsAreOpaqueAndDoNotRequireClasspathSelection() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        List<String> options = List.of("-g:none", "-parameters", "-Werror", "-Xlint:deprecation", "-Akey=some value",
                "-processor", "example.Processor", "-Xplugin:example plugin", "--release", "17");
        putList(values, "compiler-options", options);
        storeCapture(fixture, values);
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(options, arguments(result));
        String invocation = Files.readString(fixture.root().resolve("invocation.txt"));
        assertTrue(invocation.contains("-Pjig.gradle.compiler=true"), invocation);
        assertTrue(invocation.contains("-Pjig.gradle.classpath=compile"), invocation);
        Path file = temporaryDirectory.resolve("compiler.args");
        var written = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options", "-w", file.toString());
        assertEquals(0, written.exitCode(), written.error());
        assertEquals("", written.output());
        assertEquals(result.output(), Files.readString(file));
        assertInvalid("mutually exclusive", "gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options", "-r", "release");
        assertInvalid("compile", "gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--classpath", "runtime", "--resolve-compiler-options");
        assertInvalid("mutually exclusive", "gradle", "--root-project-dir", fixture.root().toString(),
                "--list-project-paths", "--resolve-compiler-options");
        assertInvalid("unknown resolve options", "gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--classpath", "compile", "-r", "javac");
        values.remove("compiler-options.count");
        storeCapture(fixture, values);
        var missing = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options");
        assertEquals(1, missing.exitCode(), missing.error());
        assertTrue(missing.error().contains("Missing Gradle compiler options"), missing.error());
        assertEquals("", missing.output());
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
                classpath=compile
                for argument do
                    case "$argument" in
                        -Pjig.gradle.classpath=*) classpath=${argument#*=} ;;
                    esac
                done
                printf 'jig-gradle:'
                base64 < "$classpath.properties" | tr -d '\\n'
                printf '\\n'
                """);
        return new Fixture(root, project);
    }

    private static void writeCapture(Path file, Path root, Path project,
            boolean present, boolean runtime)
            throws Exception {
        var values = new Properties();
        values.setProperty("version", "1");
        putList(values, "project-paths", List.of(":", ":app"));
        putList(values, "source-sets", List.of("main", "test", "integrationTest"));
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

    private Result resolve(Fixture fixture, String classpath, String options) {
        return run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-path",
                ":app",
                "--source-set", "main", "--classpath",
                classpath,
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
