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
import java.util.Map;
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
                "--source-set", "--classpath", "--resolve-options", "--resolve-compiler-options", "--write-argfile", "--refresh")) {
            assertTrue(result.output().contains(option), option);
        }
        assertFalse(result.output().contains("--scope"));
        assertEquals("", result.error());
        assertTrue(run("__complete", "gradle", "--ref").output().contains("--refresh\t"));
    }

    @Test
    void helpUsesACompactOptionReferenceWithoutATextTail() {
        var help = run("gradle", "--help");
        String text = help.output();
        assertEquals(0, help.exitCode(), help.error());
        assertEquals("", help.error());
        assertEquals(text, run("gradle").output());
        assertTrue(text.startsWith("Usage: jig gradle --root-project-dir <DIRECTORY> [OPTIONS]\n"), text);
        assertTrue(text.contains("requires --project-path"), text);
        assertTrue(text.contains("default: main"), text);
        assertTrue(text.contains("default: compile"), text);
        assertTrue(text.contains("source filenames"), text);
        assertTrue(text.contains("build tasks"), text);
        assertTrue(text.endsWith("        Print this help message\n"), text);
        assertTrue(text.lines().count() <= 50, text);
        assertTrue(text.lines().allMatch(line -> line.length() <= 88), text);
        assertFalse(text.contains("Examples:"), text);
        assertFalse(text.contains("Resolve options:"), text);
        assertFalse(text.contains("native jig options"), text);
    }

    @Test
    void requiresExplicitLocationsAndValidSelections() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        assertInvalid("--root-project-dir", "gradle", "--list-project-paths");
        assertInvalid("--project-path", "gradle", "--root-project-dir", root.toString(), "--source-set", "main",
                "--classpath", "compile", "--resolve-options", "source-path");

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
        assertInvalid("only to argument resolution", "gradle", "--root-project-dir", root.toString(), "--list-project-paths", "--classpath", "compile");
        assertInvalid("requires NAME", "gradle", "--root-project-dir", root.toString(), "--project-path", ":", "-r", "source-path", "--source-set");
        assertInvalid("requires compile|runtime", "gradle", "--root-project-dir", root.toString(), "--project-path", ":", "-r", "source-path", "--classpath");
        assertInvalid("--write-argfile requires --resolve-options", "gradle", "--root-project-dir",
                root.toString(), "--list-project-paths", "--write-argfile", "out.args");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolutionDefaultsToMainAndCompileIndependently() throws Exception {
        var fixture = fixture();
        var expected = resolve(fixture, "compile", "class-path,source-path");
        assertEquals(0, expected.exitCode(), expected.error());
        for (var selection : List.of(List.<String>of(), List.of("--source-set", "main"), List.of("--classpath", "compile"))) {
            var request = new ArrayList<>(List.of("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                    "-r", "class-path,source-path", "--refresh"));
            request.addAll(selection);
            var result = run(request.toArray(String[]::new));
            assertEquals(0, result.exitCode(), result.error());
            assertEquals(expected.output(), result.output());
            var invocation = Files.readString(fixture.root().resolve("invocation.txt"));
            assertTrue(invocation.contains("-Pjig.gradle.source-set=main\n"), invocation);
            assertTrue(invocation.contains("-Pjig.gradle.classpath=compile\n"), invocation);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void explicitSelectionsOverrideOnlyTheirOwnDefault() throws Exception {
        var fixture = fixture();
        var runtime = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--classpath", "runtime", "-r", "class-path");
        assertEquals(0, runtime.exitCode(), runtime.error());
        assertEquals(List.of("--class-path", fixture.root().resolve("runtime only.jar").toString()), arguments(runtime));
        var invocation = Files.readString(fixture.root().resolve("invocation.txt"));
        assertTrue(invocation.contains("-Pjig.gradle.source-set=main\n"), invocation);
        var test = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "test", "-r", "class-path");
        assertEquals(0, test.exitCode(), test.error());
        invocation = Files.readString(fixture.root().resolve("invocation.txt"));
        assertTrue(invocation.contains("-Pjig.gradle.source-set=test\n"), invocation);
        assertTrue(invocation.contains("-Pjig.gradle.classpath=compile\n"), invocation);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void defaultAndExplicitSelectionsShareTheResolutionCache() throws Exception {
        var fixture = cacheFixture();
        var defaults = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "-r", "class-path");
        assertEquals(0, defaults.exitCode(), defaults.error());
        assertEquals(1, launches(fixture));
        var explicit = resolve(fixture, "compile", "class-path");
        assertEquals(0, explicit.exitCode(), explicit.error());
        assertEquals(defaults.output(), explicit.output());
        assertEquals("", explicit.error());
        assertEquals(1, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compilerResolutionDefaultsToMain() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        var options = List.of("--release", "17", "-parameters");
        putList(values, "compiler-options", options);
        storeCapture(fixture, values);
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "--resolve-compiler-options");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(options, arguments(result));
        var invocation = Files.readString(fixture.root().resolve("invocation.txt"));
        assertTrue(invocation.contains("-Pjig.gradle.source-set=main\n"), invocation);
        assertTrue(invocation.contains("-Pjig.gradle.classpath=compile\n"), invocation);
        assertInvalid("implies the compile classpath", "gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--resolve-compiler-options", "--classpath", "runtime");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void missingMainSourceSetDoesNotSelectAnotherSourceSet() throws Exception {
        var fixture = cacheFixture();
        var values = capture(fixture);
        putList(values, "source-sets", List.of("custom"));
        putList(values, "source-set-projects", List.of(":app"));
        storeCapture(fixture, values);
        assertEquals(0, discoverTsv(fixture).exitCode());
        assertInvalid("Unknown source set in project :app: main", "gradle", "--root-project-dir", fixture.root().toString(),
                "--project-path", ":app", "-r", "source-path");
        assertEquals(1, launches(fixture));
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
    void captureDoesNotAddBlankDiagnosticsOrSwallowUnterminatedBuildOutput() throws Exception {
        var fixture = fixture();
        Path wrapper = fixture.root().resolve("gradlew");
        String script = Files.readString(wrapper);
        for (String diagnostic : List.of("", "build output", "build output\n", "\n\nbuild output\n\n")) {
            Files.writeString(fixture.root().resolve("diagnostic.txt"), diagnostic);
            Files.writeString(wrapper, script.replace("echo 'Gradle build output'", "cat diagnostic.txt"));
            var result = resolve(fixture, "compile", "source-path");
            assertEquals(0, result.exitCode(), result.error());
            assertEquals(diagnostic.isEmpty() || diagnostic.endsWith("\n") ? diagnostic : diagnostic + "\n", result.error());
            assertEquals(List.of("--source-path", fixture.project().resolve("custom sources").toString()), arguments(result));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void projectDiscoveryRecordsValidateSelectionsWithoutRequiringArgumentMetadata() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        values.setProperty("kind", "projects");
        storeCapture(fixture, values);
        Files.writeString(fixture.root().resolve("gradlew"), """
                #!/bin/sh
                printf 'jig-gradle:'
                base64 < compile.properties | tr -d '\\n'
                printf '\\n'
                """);
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":missing",
                "--source-set", "main", "--classpath", "runtime", "-r", "class-path");
        assertEquals(2, result.exitCode(), result.error());
        assertEquals("", result.output());
        assertEquals("jig gradle: Unknown project path in this build: :missing\n", result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryCacheServesListingsAndRejectsInvalidSelectionsWithoutLaunchingGradle() throws Exception {
        var fixture = cacheFixture();
        var first = discoverTsv(fixture);
        assertEquals(0, first.exitCode(), first.error());
        var text = run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-paths");
        assertEquals(":\n:app\n", text.output());
        assertEquals("", text.error());
        var sourceSets = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "--list-source-sets");
        assertEquals("integrationTest\nmain\ntest\n", sourceSets.output());
        assertEquals("", sourceSets.error());
        assertInvalid("Unknown project path", "gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":missing", "--list-source-sets");
        assertInvalid("Unknown source set", "gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "missing", "--classpath", "compile", "-r", "class-path");
        assertEquals(1, launches(fixture));
        var resolved = resolve(fixture, "compile", "class-path");
        assertEquals(0, resolved.exitCode(), resolved.error());
        assertEquals(2, launches(fixture));
        var values = capture(fixture);
        putList(values, "classpath", List.of(fixture.root().resolve("changed.jar").toString()));
        storeCapture(fixture, values);
        var cached = resolve(fixture, "compile", "class-path");
        assertEquals(resolved.output(), cached.output());
        assertEquals(2, launches(fixture));
        var changed = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--classpath", "compile", "-r", "class-path", "--refresh");
        assertEquals(List.of("--class-path", fixture.root().resolve("changed.jar").toString()), arguments(changed));
        assertEquals(3, launches(fixture));
        assertEquals(first.output(), discoverTsv(fixture).output());
        assertEquals(4, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryCacheTracksReportedBuildFilesAndRefreshReplacesUntrackedFacts() throws Exception {
        var fixture = cacheFixture();
        assertEquals(0, discoverTsv(fixture).exitCode());
        int expected = 1;
        for (Path file : List.of(fixture.root().resolve("settings.gradle"), fixture.project().resolve("build.gradle"),
                fixture.root().resolve("included/settings.gradle"), fixture.root().resolve("applied.gradle"))) {
            var modified = Files.getLastModifiedTime(file);
            Files.writeString(file, Files.readString(file) + "\n");
            Files.setLastModifiedTime(file, modified);
            assertEquals(0, discoverTsv(fixture).exitCode());
            assertEquals(++expected, launches(fixture));
        }
        Path missing = fixture.root().resolve("included/build.gradle");
        Files.writeString(missing, "apply plugin: 'java'\n");
        assertEquals(0, discoverTsv(fixture).exitCode());
        assertEquals(++expected, launches(fixture));
        Files.delete(missing);
        assertEquals(0, discoverTsv(fixture).exitCode());
        assertEquals(expected, launches(fixture));
        var values = capture(fixture);
        putList(values, "source-sets", List.of("custom"));
        putList(values, "source-set-projects", List.of(":app"));
        storeCapture(fixture, values);
        assertFalse(discoverTsv(fixture).output().contains("\tcustom\t"));
        assertEquals(expected, launches(fixture));
        var refreshed = run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-paths", "--format", "tsv", "--refresh");
        assertEquals(0, refreshed.exitCode(), refreshed.error());
        assertTrue(refreshed.output().contains("\tcustom\t"), refreshed.output());
        assertEquals(++expected, launches(fixture));
        assertEquals(refreshed.output(), discoverTsv(fixture).output());
        assertEquals(expected, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void failedRefreshDoesNotReuseOrReplaceTheOldDiscovery() throws Exception {
        var fixture = cacheFixture();
        Path wrapper = fixture.root().resolve("gradlew");
        Files.writeString(wrapper, Files.readString(wrapper) + "if [ -f fail ]; then exit 7; fi\n");
        assertEquals(0, discoverTsv(fixture).exitCode());
        Files.writeString(fixture.root().resolve("fail"), "");
        var refreshed = run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-paths", "--refresh");
        assertEquals(7, refreshed.exitCode(), refreshed.error());
        assertEquals("", refreshed.output());
        assertEquals(7, discoverTsv(fixture).exitCode());
        assertEquals(3, launches(fixture));
        Files.delete(fixture.root().resolve("fail"));
        assertEquals(0, discoverTsv(fixture).exitCode());
        assertEquals(4, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolutionCacheReusesArgumentsAcrossWorktreesAndBuildStates() throws Exception {
        var fixture = cacheFixture();
        var first = resolve(fixture, "compile", "source-path,class-path");
        assertEquals(0, first.exitCode(), first.error());
        var second = resolve(fixture, "compile", "class-path,source-path");
        assertEquals(first.output(), second.output());
        assertEquals("", second.error());
        assertEquals(1, launches(fixture));

        Path settings = fixture.root().resolve("settings.gradle");
        String original = Files.readString(settings);
        Files.writeString(settings, original + "// another branch\n");
        assertEquals(0, resolve(fixture, "compile", "source-path,class-path").exitCode());
        assertEquals(2, launches(fixture));
        Files.writeString(settings, original);
        assertEquals(first.output(), resolve(fixture, "compile", "source-path,class-path").output());
        assertEquals(2, launches(fixture));

        Path other = temporaryDirectory.resolve("other worktree");
        copyTree(fixture.root(), other);
        other = other.toRealPath();
        var worktree = new Fixture(other, other.resolve(fixture.root().relativize(fixture.project())));
        Path argfile = other.resolve("cached.args");
        var relocated = run("gradle", "--root-project-dir", other.toString(), "--project-path", ":app",
                "--source-set", "main", "--classpath", "compile", "-r", "class-path,source-path", "-w", argfile.toString());
        assertEquals(0, relocated.exitCode(), relocated.error());
        assertEquals("", relocated.output());
        assertEquals("", relocated.error());
        assertEquals(first.output().replace(fixture.root().toString(), other.toString()), Files.readString(argfile));
        assertEquals(2, launches(worktree));

        var refreshed = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--classpath", "compile", "-r", "class-path,source-path", "--refresh");
        assertEquals(0, refreshed.exitCode(), refreshed.error());
        assertEquals(3, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryCacheReusesResultsAcrossWorktreesAndBuildStates() throws Exception {
        var fixture = cacheFixture();
        var first = discoverTsv(fixture);
        assertEquals(0, first.exitCode(), first.error());
        Path settings = fixture.root().resolve("settings.gradle");
        String original = Files.readString(settings);
        Files.writeString(settings, original + "// another branch\n");
        assertEquals(0, discoverTsv(fixture).exitCode());
        Files.writeString(settings, original);
        assertEquals(first.output(), discoverTsv(fixture).output());
        assertEquals(2, launches(fixture));
        Path other = temporaryDirectory.resolve("other worktree");
        copyTree(fixture.root(), other);
        other = other.toRealPath();
        var worktree = new Fixture(other, other.resolve(fixture.root().relativize(fixture.project())));
        var relocated = discoverTsv(worktree);
        assertEquals(0, relocated.exitCode(), relocated.error());
        assertEquals(first.output().replace(fixture.root().toString(), other.toString()), relocated.output());
        assertEquals("", relocated.error());
        assertEquals(2, launches(worktree));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void concurrentCachedQueriesPublishCompleteAnswers() throws Exception {
        var fixture = cacheFixture();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var queries = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int index = 0; index < 8; index++) {
                queries.add(executor.submit(() -> {
                    start.await();
                    return resolve(fixture, "compile", "class-path,source-path");
                }));
            }
            start.countDown();
            String expected = queries.getFirst().get().output();
            for (var query : queries) {
                var result = query.get();
                assertEquals(0, result.exitCode(), result.error());
                assertEquals(expected, result.output());
            }
            int launched = launches(fixture);
            assertEquals(expected, resolve(fixture, "compile", "class-path,source-path").output());
            assertEquals(launched, launches(fixture));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolutionCacheTracksModuleDescriptorsAndRetainsEarlierStates() throws Exception {
        var fixture = cacheFixture();
        var first = resolve(fixture, "compile", "source-path,module-path");
        assertEquals(0, first.exitCode(), first.error());
        Path sources = Files.createDirectories(fixture.project().resolve("custom sources"));
        Path descriptor = sources.resolve("module-info.java");
        Files.writeString(descriptor, "module fixture.app {}\n");
        assertEquals(0, resolve(fixture, "compile", "source-path,module-path").exitCode());
        assertEquals(2, launches(fixture));
        Files.delete(descriptor);
        assertEquals(first.output(), resolve(fixture, "compile", "source-path,module-path").output());
        assertEquals(2, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compilerCacheRelocatesKnownPathsButKeepsOpaqueOptionsLocal() throws Exception {
        var fixture = cacheFixture();
        Path external = temporaryDirectory.resolve("external dependency.jar").toAbsolutePath();
        var values = capture(fixture);
        putList(values, "compiler-options", List.of("--class-path", external + java.io.File.pathSeparator + fixture.root().resolve("classes"),
                "--module-source-path=example.app=" + fixture.project().resolve("sources"), "--release", "17"));
        storeCapture(fixture, values);
        String[] request = {"gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "--source-set", "main",
                "--resolve-compiler-options"};
        var first = run(request);
        assertEquals(0, first.exitCode(), first.error());
        Path other = temporaryDirectory.resolve("other worktree");
        copyTree(fixture.root(), other);
        other = other.toRealPath();
        request[2] = other.toString();
        var relocated = run(request);
        assertEquals(0, relocated.exitCode(), relocated.error());
        assertEquals(first.output().replace(fixture.root().toString(), other.toString()), relocated.output());
        assertTrue(relocated.output().contains(external.toString()), relocated.output());
        assertEquals("", relocated.error());
        assertEquals(1, Files.readAllLines(other.resolve("launches.txt")).size());

        putList(values, "compiler-options", List.of("-Aplugin.location=" + fixture.root().resolve("plugin data")));
        storeCapture(fixture, values);
        request[2] = fixture.root().toString();
        var refresh = new ArrayList<>(List.of(request));
        refresh.add("--refresh");
        assertEquals(0, run(refresh.toArray(String[]::new)).exitCode());
        assertEquals(2, launches(fixture));
        assertEquals(0, run(request).exitCode());
        assertEquals(2, launches(fixture));
        request[2] = other.toString();
        // The opaque answer cannot be used in the other worktree.
        assertEquals(0, run(request).exitCode());
        assertEquals(2, Files.readAllLines(other.resolve("launches.txt")).size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void cachePrunesUnusedAnswersDailyAndTouchesHits() throws Exception {
        var fixture = cacheFixture();
        String name = java.util.UUID.randomUUID() + ".gradle";
        Path input = fixture.root().resolve(name);
        Files.writeString(input, "// tracked input\n");
        var values = capture(fixture);
        putList(values, "build-files", List.of(input.toString()));
        storeCapture(fixture, values);
        assertEquals(0, discoverTsv(fixture).exitCode());
        assertEquals(0, resolve(fixture, "compile", "class-path").exitCode());
        Path cache = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv())
                .resolve("projects");
        List<Path> entries;
        try (var paths = Files.walk(cache)) {
            entries = paths.filter(path -> path.toString().endsWith(".properties"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(name);
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    }).toList();
        }
        assertEquals(2, entries.size());
        var old = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
        for (Path entry : entries) {
            Files.setLastModifiedTime(entry, old);
        }
        // A recent cleanup marker prevents another scan, even with old entries.
        assertEquals(0, discoverTsv(fixture).exitCode());
        assertEquals(2, launches(fixture));
        Path recent = entries.stream().filter(path -> {
            try {
                return !Files.getLastModifiedTime(path).equals(old);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }).findFirst().orElseThrow();
        Path unused = entries.stream().filter(path -> !path.equals(recent)).findFirst().orElseThrow();
        Files.setLastModifiedTime(cache.resolve(".pruned"), old);
        assertEquals(0, discoverTsv(fixture).exitCode());
        assertTrue(Files.exists(recent));
        assertFalse(Files.exists(unused));
        assertEquals(0, resolve(fixture, "compile", "class-path").exitCode());
        assertEquals(3, launches(fixture));
        Files.writeString(unused, "inputs=1\ninput.0=\\\\u00oops\n");
        assertEquals(0, resolve(fixture, "compile", "class-path").exitCode());
        assertEquals(4, launches(fixture));
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }

    private Fixture cacheFixture() throws Exception {
        var fixture = fixture();
        Path included = Files.createDirectories(fixture.root().resolve("included"));
        var files = List.of(fixture.root().resolve("settings.gradle"), fixture.project().resolve("build.gradle"),
                included.resolve("settings.gradle"), fixture.root().resolve("applied.gradle"));
        for (Path file : files) {
            Files.writeString(file, "// build configuration " + java.util.UUID.randomUUID() + "\n");
        }
        var values = capture(fixture);
        var inputs = new ArrayList<>(files);
        inputs.add(included.resolve("build.gradle"));
        putList(values, "build-files", inputs.stream().map(Path::toString).toList());
        storeCapture(fixture, values);
        var runtime = new Properties();
        try (var input = Files.newInputStream(fixture.root().resolve("runtime.properties"))) {
            runtime.load(input);
        }
        putList(runtime, "build-files", inputs.stream().map(Path::toString).toList());
        try (var output = Files.newOutputStream(fixture.root().resolve("runtime.properties"))) {
            runtime.store(output, "fixture");
        }
        Path wrapper = fixture.root().resolve("gradlew");
        Files.writeString(wrapper, Files.readString(wrapper).replace("echo 'Gradle build output'", "echo capture >> launches.txt\necho 'Gradle build output'"));
        return fixture;
    }

    private static int launches(Fixture fixture) throws Exception {
        return Files.readAllLines(fixture.root().resolve("launches.txt")).size();
    }

    @Test
    void validatesProjectListingFormatsBeforeLaunchingGradle() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        assertInvalid("text or tsv", "gradle", "--root-project-dir", root.toString(),
                "--list-project-paths", "--format", "json");
        assertInvalid("only be specified once", "gradle", "--root-project-dir", root.toString(),
                "--list-project-paths", "--format", "tsv", "--format", "text");
        assertInvalid("only to project listing", "gradle", "--root-project-dir", root.toString(),
                "--project-path", ":", "--list-source-sets", "--format", "tsv");
        assertTrue(run("__complete", "gradle", "--for").output().contains("--format\t"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryListsProjectDirectoriesAsTsv() throws Exception {
        var fixture = fixture();
        var result = discoverTsv(fixture);
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(projectTsv(Map.of(":", fixture.root(), ":app", fixture.project()),
                Map.of(":app", List.of("main", "test", "integrationTest"))), result.output());
        assertTrue(result.error().contains("Gradle build output"), result.error());
        var text = run("gradle", "--root-project-dir", fixture.root().toString(),
                "--list-project-paths", "--format", "text");
        assertEquals(0, text.exitCode(), text.error());
        assertEquals(":\n:app\n", text.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void invalidProjectDirectoriesNeverPublishPartialTsv() throws Exception {
        var fixture = fixture();
        var original = capture(fixture);
        for (String field : List.of("project-directories.count", "project-directories.1")) {
            var values = new Properties();
            values.putAll(original);
            values.remove(field);
            storeCapture(fixture, values);
            var result = discoverTsv(fixture);
            assertEquals(1, result.exitCode(), result.error());
            assertEquals("", result.output());
            assertTrue(result.error().contains(field), result.error());
        }
        for (String directory : List.of("relative", "", fixture.project() + "\tpart",
                fixture.project() + "\npart", fixture.project() + "\rpart")) {
            var values = new Properties();
            values.putAll(original);
            values.setProperty("project-directories.1", directory);
            storeCapture(fixture, values);
            var result = discoverTsv(fixture);
            assertEquals(1, result.exitCode(), result.error());
            assertEquals("", result.output());
        }
        original.setProperty("project-directories.count", "1");
        storeCapture(fixture, original);
        var mismatched = discoverTsv(fixture);
        assertEquals(1, mismatched.exitCode(), mismatched.error());
        assertEquals("", mismatched.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void tsvPreservesBackslashesAndRejectsUnrepresentableSelectors() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        String directory = fixture.project() + "\\directory";
        values.setProperty("project-directories.1", directory);
        storeCapture(fixture, values);
        var valid = discoverTsv(fixture);
        assertEquals(0, valid.exitCode(), valid.error());
        assertEquals(projectTsv(Map.of(":", fixture.root(), ":app", Path.of(directory)),
                Map.of(":app", List.of("main", "test", "integrationTest"))), valid.output());
        for (String selector : List.of("", ":app\tpart", ":app\npart", ":app\rpart")) {
            values.setProperty("project-paths.1", selector);
            storeCapture(fixture, values);
            var result = discoverTsv(fixture);
            assertEquals(1, result.exitCode(), result.error());
            assertEquals("", result.output());
            assertTrue(result.error().contains("selector"), result.error());
        }
        values.setProperty("project-paths.1", ":app");
        storeCapture(fixture, values);
        Path wrapper = fixture.root().resolve("gradlew");
        Files.writeString(wrapper, Files.readString(wrapper) + "exit 7\n");
        var failed = discoverTsv(fixture);
        assertEquals(7, failed.exitCode(), failed.error());
        assertEquals("", failed.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryMergesBuildQualifiedPathsFromMultipleCaptures() throws Exception {
        var fixture = fixture();
        var included = new Properties();
        included.setProperty("version", "2");
        included.setProperty("kind", "projects");
        included.setProperty("build-path", ":tools");
        putList(included, "project-paths", List.of(":tools", ":tools:plugin"));
        Path tools = temporaryDirectory.resolve("included tools");
        putList(included, "project-directories", List.of(tools.toString(), tools.resolve("plugin").toString()));
        putList(included, "source-set-projects", List.of(":tools:plugin", ":tools:plugin"));
        putList(included, "source-sets", List.of("main", "test"));
        var rootCapture = capture(fixture);
        rootCapture.setProperty("kind", "projects");
        storeCapture(fixture, rootCapture);
        try (var output = Files.newOutputStream(fixture.root().resolve("included.properties"))) {
            included.store(output, null);
        }
        Files.writeString(fixture.root().resolve("gradlew"), """
                #!/bin/sh
                for build in included compile included; do
                    printf 'jig-gradle:'
                    base64 < "$build.properties" | tr -d '\\n'
                    printf '\\n'
                done
                """);
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-paths");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(":\n:app\n:tools\n:tools:plugin\n", result.output());
        var tsv = discoverTsv(fixture);
        assertEquals(0, tsv.exitCode(), tsv.error());
        assertEquals(projectTsv(Map.of(":", fixture.root(), ":app", fixture.project(), ":tools", tools,
                ":tools:plugin", tools.resolve("plugin")), Map.of(":app", List.of("main", "test", "integrationTest"),
                ":tools:plugin", List.of("main", "test"))), tsv.output());
        putList(included, "project-paths", List.of(":app"));
        putList(included, "project-directories", List.of(tools.toString()));
        try (var output = Files.newOutputStream(fixture.root().resolve("included.properties"))) {
            included.store(output, null);
        }
        var conflicting = discoverTsv(fixture);
        assertEquals(1, conflicting.exitCode(), conflicting.error());
        assertEquals("", conflicting.output());
        assertTrue(conflicting.error().contains("Conflicting project directory"), conflicting.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sourceOptionsChooseThePathForTheSelectedProject() throws Exception {
        var fixture = fixture();
        var classpath = resolve(fixture, "compile", "source-path,module-source-path");
        assertEquals(0, classpath.exitCode(), classpath.error());
        var sources = fixture.project().resolve("custom sources");
        assertEquals(List.of("--source-path", sources.toString()), arguments(classpath));
        var values = capture(fixture);
        values.setProperty("modular", "true");
        values.setProperty("module-name", "example.app");
        storeCapture(fixture, values);
        var modular = resolve(fixture, "compile", "source-path,module-source-path");
        assertEquals(0, modular.exitCode(), modular.error());
        assertEquals(List.of("--module-source-path", "example.app=" + sources), arguments(modular));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sourceArchivesExtendTheSourcePathWithoutBecomingModuleSourceRoots() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        Path archive = fixture.root().resolve("dependency-sources.jar");
        putList(values, "dependency-sources", List.of(archive.toString()));
        storeCapture(fixture, values);
        var classpath = resolve(fixture, "compile", "source-path");
        assertEquals(0, classpath.exitCode(), classpath.error());
        assertEquals(List.of("--source-path", fixture.project().resolve("custom sources")
                + System.getProperty("path.separator") + archive), arguments(classpath));
        values.setProperty("modular", "true");
        values.setProperty("module-name", "example.app");
        storeCapture(fixture, values);
        var modular = resolve(fixture, "compile", "source-path,module-source-path");
        assertEquals(0, modular.exitCode(), modular.error());
        assertEquals(List.of("--module-source-path", "example.app=" + fixture.project().resolve("custom sources")), arguments(modular));
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
    void exposesTheGradleCompilerAsTheSystemImage() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        values.remove("release");
        values.setProperty("system", System.getProperty("java.home"));
        storeCapture(fixture, values);
        var result = resolve(fixture, "compile", "system");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(List.of("--system", System.getProperty("java.home")), arguments(result));
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
    void resolutionCachesEverySourceContextForTheRequestedProject() throws Exception {
        var fixture = cacheFixture();
        var compile = resolve(fixture, "compile", "class-path,source-path");
        var runtime = resolve(fixture, "runtime", "class-path,source-path");
        assertEquals(0, compile.exitCode(), compile.error());
        assertEquals(0, runtime.exitCode(), runtime.error());
        assertEquals(List.of("--class-path", fixture.root().resolve("compile only.jar").toString(), "--source-path",
                fixture.project().resolve("custom sources").toString()), arguments(compile));
        assertEquals(List.of("--class-path", fixture.root().resolve("runtime only.jar").toString(), "--source-path",
                fixture.project().resolve("custom sources").toString()), arguments(runtime));
        assertEquals(1, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void simpleArgumentsRemainUnquotedOnCacheMissAndHit() throws Exception {
        var fixture = cacheFixture();
        Path dependency = temporaryDirectory.resolve("dependency.jar");
        var values = capture(fixture);
        putList(values, "classpath", List.of(dependency.toString()));
        storeCapture(fixture, values);
        String expected = "--class-path\n" + dependency + "\n";
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = resolve(fixture, "compile", "class-path");
            assertEquals(0, result.exitCode(), result.error());
            assertEquals(expected, result.output());
        }
        assertEquals(1, launches(fixture));
        Path file = temporaryDirectory.resolve("classpath.args");
        var written = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "-r", "class-path", "-w", file.toString());
        assertEquals(0, written.exitCode(), written.error());
        assertEquals("", written.output());
        assertEquals(expected, Files.readString(file));
        assertEquals(1, launches(fixture));
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
    void portableRequestsOmitOptionsNotSupportedByGradle() throws Exception {
        var fixture = fixture();
        var expected = resolve(fixture, "compile", "class-path,source-path,release");
        var result = resolve(fixture, "compile", "class-path,source-path,release,d");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(expected.output(), result.output());
        String invocation = Files.readString(fixture.root().resolve("invocation.txt"));
        assertTrue(invocation.contains("-Pjig.gradle.options=class-path,release,source-path"), invocation);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unsupportedOnlyRequestsProduceNoArguments() throws Exception {
        var fixture = fixture();
        var result = resolve(fixture, "compile", "d");
        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unsupportedOptionsDoNotHideInvalidRequests() throws Exception {
        var fixture = fixture();
        for (String options : List.of("d,source-paht", "d,", "d,module=unknown", "d,module=list,module=main", "d,module=roots")) {
            var result = resolve(fixture, "compile", options);
            assertEquals(2, result.exitCode(), result.error());
            assertFalse(Files.exists(fixture.root().resolve("invocation.txt")));
        }
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

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void missingListCountsAreNotTreatedAsEmptyResults() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        values.remove("classpath.count");
        storeCapture(fixture, values);
        Path file = temporaryDirectory.resolve("options.args");
        Files.writeString(file, "original\n");
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--classpath", "compile", "-r", "class-path", "-w", file.toString());
        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("classpath.count"), result.error());
        assertEquals("", result.output());
        assertEquals("original\n", Files.readString(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void requiredCaptureFieldsAreValidatedBeforeProjection() throws Exception {
        var fixture = fixture();
        var original = capture(fixture);
        Files.writeString(fixture.root().resolve("gradlew"), """
                #!/bin/sh
                printf 'jig-gradle:'
                base64 < compile.properties | tr -d '\\n'
                printf '\\n'
                """);
        for (String field : List.of("version", "kind", "project-path", "source-set", "project-paths.count", "source-sets.count",
                "sources.count", "dependency-sources.count", "classpath.count", "module-path.count", "processors.count", "additional.count", "modular")) {
            var values = new Properties();
            values.putAll(original);
            values.remove(field);
            storeCapture(fixture, values);
            var result = resolve(fixture, "compile", "source-path");
            assertEquals(1, result.exitCode(), field + ": " + result.error());
            assertEquals("", result.output(), field);
            assertTrue(result.error().contains(field), result.error());
        }
        for (String count : List.of("-1", "100001", "invalid", "")) {
            var values = new Properties();
            values.putAll(original);
            values.setProperty("classpath.count", count);
            storeCapture(fixture, values);
            var result = resolve(fixture, "compile", "source-path");
            assertEquals(1, result.exitCode(), result.error());
            assertTrue(result.error().contains("Invalid Gradle capture count"), result.error());
        }
        original.setProperty("modular", "perhaps");
        storeCapture(fixture, original);
        var result = resolve(fixture, "compile", "source-path");
        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("Invalid Gradle capture flag"), result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unexpectedKindsAndProjectIdentitiesAreRejected() throws Exception {
        var fixture = fixture();
        var original = capture(fixture);
        Files.writeString(fixture.root().resolve("gradlew"), """
                #!/bin/sh
                printf 'jig-gradle:'
                base64 < compile.properties | tr -d '\\n'
                printf '\\n'
                """);
        for (var mismatch : List.of(List.of("kind", "compiler-options"),
                List.of("project-path", ":other"), List.of("source-set", "other"))) {
            var values = new Properties();
            values.putAll(original);
            values.setProperty(mismatch.getFirst(), mismatch.getLast());
            storeCapture(fixture, values);
            var result = resolve(fixture, "compile", "source-path");
            assertEquals(1, result.exitCode(), result.error());
            assertTrue(result.error().contains("capture " + mismatch.getFirst()), result.error());
            assertEquals("", result.output());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void recordsCannotOverwriteOtherRecordsOrMaskUnsupportedVersions() throws Exception {
        var fixture = fixture();
        Path wrapper = fixture.root().resolve("gradlew");
        String script = Files.readString(wrapper);
        Files.writeString(wrapper, script.replace("emit \"$kind\"", "emit \"$kind\"; emit \"$kind\""));
        var duplicate = resolve(fixture, "compile", "source-path");
        assertEquals(1, duplicate.exitCode(), duplicate.error());
        assertTrue(duplicate.error().contains("Duplicate Gradle project capture"), duplicate.error());
        assertEquals("", duplicate.output());
        var unsupported = capture(fixture);
        unsupported.setProperty("version", "99");
        try (var output = Files.newOutputStream(fixture.root().resolve("unsupported.properties"))) {
            unsupported.store(output, null);
        }
        Files.writeString(wrapper, script.replace("emit \"$kind\"", """
                printf 'jig-gradle:'
                base64 < unsupported.properties | tr -d '\\n'
                printf '\\n'
                emit "$kind"
                """));
        var result = resolve(fixture, "compile", "source-path");
        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("Unsupported Gradle capture version: 99"), result.error());
        assertEquals("", result.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compilerRecordsMustMatchTheSelectedProjectAndSourceSet() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        putList(values, "compiler-options", List.of("-g:none"));
        storeCapture(fixture, values);
        Path wrapper = fixture.root().resolve("gradlew");
        String script = Files.readString(wrapper);
        Files.writeString(wrapper, script.replace("emit compiler-options; break", "source_set=wrong; emit compiler-options; source_set=main; break"));
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options");
        assertEquals(1, result.exitCode(), result.error());
        assertTrue(result.error().contains("capture source-set: wrong"), result.error());
        assertEquals("", result.output());
        Files.writeString(wrapper, script.replace("emit compiler-options; break", "emit compiler-options; emit compiler-options; break"));
        var duplicate = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options");
        assertEquals(1, duplicate.exitCode(), duplicate.error());
        assertTrue(duplicate.error().contains("Duplicate Gradle compiler options capture"), duplicate.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compilerArgumentEncodingRoundTripsThroughTheJavaLauncher() throws Exception {
        var fixture = fixture();
        Path echo = temporaryDirectory.resolve("ArgumentEcho.java");
        Files.writeString(echo, """
                public class ArgumentEcho {
                    public static void main(String[] arguments) {
                        for (String argument : arguments) {
                            System.out.println(java.util.Base64.getEncoder().encodeToString(argument.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        }
                    }
                }
                """);
        Path classes = temporaryDirectory.resolve("classes");
        var diagnostics = new StringWriter();
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(new PrintWriter(diagnostics), new PrintWriter(diagnostics),
                "-d", classes.toString(), echo.toString()), diagnostics.toString());
        var arguments = List.of("", "#comment", "double\"quote", "single'quote", "back\\slash\\", "new\nline", "carriage\rreturn", "tab\ttab", "form\ffeed", "space and quote\"");
        var compiler = new ArrayList<>(List.of("--class-path", classes.toString(), "ArgumentEcho"));
        compiler.addAll(arguments);
        var values = capture(fixture);
        putList(values, "compiler-options", compiler);
        storeCapture(fixture, values);
        var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", "main", "--resolve-compiler-options");
        assertEquals(0, result.exitCode(), result.error());
        Path file = temporaryDirectory.resolve("launcher.args");
        Files.writeString(file, result.output());
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "@" + file)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertEquals(arguments.stream().map(argument -> java.util.Base64.getEncoder().encodeToString(argument.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList(),
                output.lines().toList());
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
        assertEquals(":_jigCapture", invocation.getLast());
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
        Files.writeString(wrapper, Files.readString(wrapper).replace("                classpath=compile\n                kind=arguments", """
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
                kind=arguments"""));
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
    void compilerAndMetadataRecordsRemainSeparateWithoutPublishingTransportRecords() throws Exception {
        var fixture = fixture();
        var compiler = new Properties();
        compiler.setProperty("version", "2");
        compiler.setProperty("kind", "compiler-options");
        compiler.setProperty("project-path", ":app");
        compiler.setProperty("source-set", "main");
        List<String> options = List.of("-g:none", "-Akey=value with spaces");
        putList(compiler, "compiler-options", options);
        try (var output = Files.newOutputStream(fixture.root().resolve("compiler.properties"))) {
            compiler.store(output, "compiler");
        }
        Path wrapper = fixture.root().resolve("gradlew");
        String script = Files.readString(wrapper);
        String compilerRecord = """
                printf 'jig-gradle:'
                base64 < compiler.properties | tr -d '\\n'
                printf '\\n'
                """;
        for (boolean compilerFirst : List.of(true, false)) {
            Files.writeString(wrapper, compilerFirst
                    ? script.replace("echo 'Gradle build output'", compilerRecord + "echo 'Gradle build output'")
                    : script + compilerRecord);
            var result = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                    "--source-set", "main", "--resolve-compiler-options");
            assertEquals(0, result.exitCode(), result.error());
            assertEquals(options, arguments(result));
            assertTrue(result.error().contains("Gradle build output"), result.error());
            assertFalse(result.error().contains("jig-gradle:"), result.error());
        }
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

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void projectListingIncludesSourceSetsAndClasspathsWithDirectoriesFirst() throws Exception {
        var fixture = fixture();
        var expected = new ArrayList<String>();
        expected.add(fixture.root() + "\t:\t\t");
        for (String sourceSet : List.of("main", "test", "integrationTest")) {
            for (String classpath : List.of("compile", "runtime")) {
                expected.add(fixture.project() + "\t:app\t" + sourceSet + "\t" + classpath);
            }
        }
        var result = discoverTsv(fixture);
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(expected.stream().sorted().toList(), result.output().lines().toList());
        assertTrue(result.error().contains("Gradle build output"), result.error());
        String invocation = Files.readString(fixture.root().resolve("invocation.txt"));
        assertTrue(invocation.contains("-Pjig.gradle.list-contexts=true"), invocation);
        assertFalse(invocation.contains("-Pjig.gradle.source-set="), invocation);
        assertFalse(invocation.contains("-Pjig.gradle.classpath="), invocation);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void contextFieldsRemainSeparateAndEmptySourceSetListsDoNotImplyDefaults() throws Exception {
        var fixture = fixture();
        var values = capture(fixture);
        putList(values, "source-set-projects", List.of(":app"));
        putList(values, "source-sets", List.of("custom/test set"));
        storeCapture(fixture, values);
        var custom = discoverTsv(fixture);
        assertEquals(0, custom.exitCode(), custom.error());
        assertTrue(custom.output().contains(fixture.project() + "\t:app\tcustom/test set\tcompile\n"), custom.output());
        assertFalse(custom.output().contains("\tmain\t"), custom.output());
        putList(values, "source-set-projects", List.of());
        putList(values, "source-sets", List.of());
        storeCapture(fixture, values);
        var empty = discoverTsv(fixture);
        assertEquals(0, empty.exitCode(), empty.error());
        assertEquals(List.of(fixture.root() + "\t:\t\t", fixture.project() + "\t:app\t\t").stream().sorted().toList(),
                empty.output().lines().toList());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void contextListingRejectsIncompleteOrUnrepresentableNativeFields() throws Exception {
        var fixture = fixture();
        var original = capture(fixture);
        for (String field : List.of("source-set-projects.count", "source-set-projects.0", "source-sets.count", "source-sets.0", "project-directories.0")) {
            var values = new Properties();
            values.putAll(original);
            values.remove(field);
            storeCapture(fixture, values);
            var result = discoverTsv(fixture);
            assertEquals(1, result.exitCode(), field + ": " + result.error());
            assertEquals("", result.output());
        }
        for (String sourceSet : List.of("", "main\tpart", "main\npart", "main\rpart")) {
            var values = new Properties();
            values.putAll(original);
            values.setProperty("source-sets.0", sourceSet);
            storeCapture(fixture, values);
            var result = discoverTsv(fixture);
            assertEquals(1, result.exitCode(), result.error());
            assertEquals("", result.output());
        }
        for (String project : List.of(":missing", "", ":app\tpart")) {
            var values = new Properties();
            values.putAll(original);
            values.setProperty("source-set-projects.0", project);
            storeCapture(fixture, values);
            var result = discoverTsv(fixture);
            assertEquals(1, result.exitCode(), result.error());
            assertEquals("", result.output());
        }
        original.setProperty("source-set-projects.count", "2");
        storeCapture(fixture, original);
        var mismatched = discoverTsv(fixture);
        assertEquals(1, mismatched.exitCode(), mismatched.error());
        assertEquals("", mismatched.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void contextListingMergesIncludedBuildsAndRejectsConflictingSourceSets() throws Exception {
        var fixture = fixture();
        var root = capture(fixture);
        root.setProperty("kind", "projects");
        storeCapture(fixture, root);
        var included = new Properties();
        included.setProperty("version", "2");
        included.setProperty("kind", "projects");
        included.setProperty("build-path", ":tools");
        Path tools = temporaryDirectory.resolve("included tools");
        putList(included, "project-paths", List.of(":tools", ":tools:plugin"));
        putList(included, "project-directories", List.of(tools.toString(), tools.resolve("plugin").toString()));
        putList(included, "source-set-projects", List.of(":tools:plugin", ":tools:plugin"));
        putList(included, "source-sets", List.of("main", "test"));
        Path capture = fixture.root().resolve("included.properties");
        try (var output = Files.newOutputStream(capture)) {
            included.store(output, null);
        }
        Path wrapper = fixture.root().resolve("gradlew");
        Files.writeString(wrapper, """
                #!/bin/sh
                for build in included compile included; do
                    printf 'jig-gradle:'
                    base64 < "$build.properties" | tr -d '\\n'
                    printf '\\n'
                done
                """);
        var result = discoverTsv(fixture);
        assertEquals(0, result.exitCode(), result.error());
        assertEquals(12, result.output().lines().count());
        assertTrue(result.output().contains(tools.resolve("plugin") + "\t:tools:plugin\ttest\truntime\n"), result.output());
        putList(included, "project-paths", List.of(":app"));
        putList(included, "project-directories", List.of(fixture.project().toString()));
        putList(included, "source-set-projects", List.of(":app"));
        putList(included, "source-sets", List.of("main"));
        try (var output = Files.newOutputStream(capture)) {
            included.store(output, null);
        }
        var conflicting = discoverTsv(fixture);
        assertEquals(1, conflicting.exitCode(), conflicting.error());
        assertEquals("", conflicting.output());
        assertTrue(conflicting.error().contains("Conflicting Gradle source sets"), conflicting.error());
        Files.writeString(wrapper, Files.readString(wrapper) + "exit 7\n");
        var failed = discoverTsv(fixture);
        assertEquals(7, failed.exitCode(), failed.error());
        assertEquals("", failed.output());
    }

    @Test
    void listingFormatAppliesOnlyToProjectDiscovery() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("contexts"));
        assertInvalid("only to project listing", "gradle", "--root-project-dir", root.toString(), "--project-path", ":app",
                "--list-source-sets", "--format", "tsv");
        assertFalse(run("gradle", "--help").output().contains("tsv-contexts"));
        assertInvalid("text or tsv", "gradle", "--root-project-dir", root.toString(), "--list-project-paths", "--format", "tsv-contexts");
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
                kind=arguments
                project=:app
                source_set=main
                all_contexts=false
                for argument do
                    case "$argument" in
                        -Pjig.gradle.classpath=*) classpath=${argument#*=} ;;
                        -Pjig.gradle.list=true) kind=projects ;;
                        -Pjig.gradle.list-source-sets=true) kind=source-sets ;;
                        -Pjig.gradle.compiler=true) kind=compiler-metadata ;;
                        -Pjig.gradle.project-path=*) project=${argument#*=} ;;
                        -Pjig.gradle.source-set=*) source_set=${argument#*=} ;;
                        -Pjig.gradle.all-contexts=true) all_contexts=true ;;
                    esac
                done
                emit() {
                    printf 'jig-gradle:'
                    {
                        while IFS= read -r line; do printf '%s\\n' "$line"; done < "$classpath.properties"
                        printf 'kind=%s\\nproject-path=%s\\nsource-set=%s\\ncontext-classpath=%s\\n' "$1" "$project" "$source_set" "$classpath"
                    } | base64 | tr -d '\\n'
                    printf '\\n'
                }
                if [ "$kind" = compiler-metadata ]; then
                    while IFS= read -r line; do
                        case "$line" in compiler-options.count=*) emit compiler-options; break ;; esac
                    done < "$classpath.properties"
                fi
                emit "$kind"
                if [ "$kind" = arguments ] && [ "$all_contexts" = true ]; then
                    if [ "$classpath" = compile ]; then classpath=runtime; else classpath=compile; fi
                    emit "$kind"
                fi
                """);
        return new Fixture(root, project);
    }

    private static void writeCapture(Path file, Path root, Path project,
            boolean present, boolean runtime)
            throws Exception {
        var values = new Properties();
        values.setProperty("version", "2");
        values.setProperty("kind", "arguments");
        values.setProperty("build-path", ":");
        values.setProperty("project-path", ":app");
        values.setProperty("source-set", "main");
        values.setProperty("context-classpath", runtime ? "runtime" : "compile");
        putList(values, "project-paths", List.of(":", ":app"));
        putList(values, "project-directories", List.of(root.toString(), project.toString()));
        putList(values, "source-sets", List.of("main", "test", "integrationTest"));
        putList(values, "source-set-projects", List.of(":app", ":app", ":app"));
        putList(values, "sources",
                present ? List.of(project.resolve("custom sources").toString()) : List.of());
        putList(values, "classpath",
                present ? List.of(root.resolve(runtime ? "runtime only.jar" : "compile only.jar").toString()) : List.of());
        putList(values, "module-path", List.of());
        putList(values, "dependency-sources", List.of());
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

    private static String projectTsv(Map<String, Path> projects, Map<String, List<String>> sourceSets) {
        var rows = new ArrayList<String>();
        projects.forEach((selector, directory) -> {
            var names = sourceSets.getOrDefault(selector, List.of());
            if (names.isEmpty()) {
                rows.add(directory + "\t" + selector + "\t\t");
            } else {
                for (String name : names) {
                    for (String classpath : List.of("compile", "runtime")) {
                        rows.add(directory + "\t" + selector + "\t" + name + "\t" + classpath);
                    }
                }
            }
        });
        return String.join("\n", rows.stream().sorted().toList()) + "\n";
    }

    private static Result discoverTsv(Fixture fixture) {
        return run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-paths", "--format", "tsv");
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
