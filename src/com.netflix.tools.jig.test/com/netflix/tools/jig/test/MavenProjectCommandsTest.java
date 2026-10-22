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

import java.io.DataInputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;

import com.netflix.tools.jig.Jig;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenProjectCommandsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void helpAndCompletionIncludeDiscoveryAndPreserveRepositoryOperations() {
        var help = run("maven", "--help");
        assertEquals(0, help.status(), help.error());
        for (String option : List.of("--project-base-dir", "--project", "--list-projects", "--scope", "--resolve-options", "--write-argfile", "--verbose", "--refresh", "maven install", "maven deploy", "maven deploy-central")) {
            assertTrue(help.output().contains(option), help.output());
        }
        assertTrue(run("__complete", "maven", "--project-b").output().contains("--project-base-dir\t"));
        assertTrue(run("__complete", "maven", "--list-project").output().contains("--list-projects\t"));
        assertTrue(run("__complete", "maven", "--proj").output().contains("--project\t"));
        assertFalse(help.output().contains("--list-project-dirs"));
        assertFalse(help.output().contains("relative path"));
        assertTrue(run("__complete", "maven", "ins").output().contains("install\t"));
        assertTrue(run("__complete", "maven", "--sc").output().contains("--scope\t"));
        assertTrue(run("__complete", "maven", "--ref").output().contains("--refresh\t"));
    }

    @Test
    void helpPutsProjectQueriesFirstAndSeparatesPublishing() {
        var help = run("maven", "--help");
        String text = help.output();
        assertEquals(0, help.status(), help.error());
        assertEquals("", help.error());
        var bare = run("maven");
        assertEquals(0, bare.status(), bare.error());
        assertEquals(text, bare.output());
        assertTrue(text.startsWith("Usage: jig maven --project-base-dir <DIRECTORY> [OPTIONS]\n"), text);
        assertTrue(text.indexOf("Publishing commands:") < text.indexOf("Options:"), text);
        assertTrue(text.contains("--project and --scope"), text);
        assertTrue(text.contains("build goals"), text);
        assertTrue(text.endsWith("        Print this help message\n"), text);
        assertTrue(text.lines().count() <= 45, text);
        assertTrue(text.lines().allMatch(line -> line.length() <= 88), text);
        assertFalse(text.contains("Examples:"), text);
        assertFalse(text.contains("Resolve options:"), text);
        var queryHelp = run("maven", "--project-base-dir", ".", "--help");
        assertEquals(0, queryHelp.status(), queryHelp.error());
        assertEquals(text.substring(text.indexOf("Options:")),
                queryHelp.output().substring(queryHelp.output().indexOf("Options:")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryAndResolutionCachesServeAnswersUntilRefresh() throws Exception {
        var fixture = cacheFixture();
        var first = discover(fixture, "--format", "tsv");
        assertEquals(0, first.status(), first.error());
        var text = discover(fixture);
        assertEquals("example:app\nexample:root\n", text.output());
        assertEquals("", text.error());
        assertEquals(1, launches(fixture));
        assertInvalid("Unknown Maven project", "maven", "--project-base-dir", fixture.project().toString(),
                "--project", "example:missing", "--scope", "compile", "-r", "class-path");
        assertEquals(1, launches(fixture));
        String[] resolve = {"maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile", "-r", "class-path"};
        assertEquals(0, run(resolve).status());
        assertEquals(2, launches(fixture));
        var values = cacheCapture(fixture);
        values.setProperty("classpath.0", fixture.project().resolve("changed.jar").toString());
        storeCacheCapture(fixture, values);
        var cached = run(resolve);
        assertTrue(cached.output().contains("dependency.jar"), cached.output());
        assertEquals(2, launches(fixture));
        var refresh = new ArrayList<>(List.of(resolve));
        refresh.add("--refresh");
        var changed = run(refresh.toArray(String[]::new));
        assertEquals(0, changed.status(), changed.error());
        assertEquals("\"--class-path\"\n\"" + fixture.project().resolve("changed.jar") + "\"\n", changed.output());
        assertEquals(3, launches(fixture));
        assertEquals(first.output(), discover(fixture, "--format", "tsv").output());
        assertEquals(4, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryCacheTracksPomsParentsAndSettingsAndSupportsRefresh() throws Exception {
        var fixture = cacheFixture();
        assertEquals(0, discover(fixture).status());
        int expected = 1;
        for (String name : List.of("pom.xml", "app/pom.xml", "parent.xml", "settings.xml")) {
            Path file = fixture.project().resolve(name);
            var modified = Files.getLastModifiedTime(file);
            Files.writeString(file, Files.readString(file) + "\n");
            Files.setLastModifiedTime(file, modified);
            assertEquals(0, discover(fixture).status());
            assertEquals(++expected, launches(fixture));
        }
        var values = cacheCapture(fixture);
        values.setProperty("projects.0", "example:changed");
        values.setProperty("projects.2", "example:changed");
        storeCacheCapture(fixture, values);
        assertFalse(discover(fixture).output().contains("example:changed"));
        assertEquals(expected, launches(fixture));
        var refreshed = discover(fixture, "--refresh");
        assertEquals(0, refreshed.status(), refreshed.error());
        assertTrue(refreshed.output().contains("example:changed"), refreshed.output());
        assertEquals(++expected, launches(fixture));
        assertEquals(refreshed.output(), discover(fixture).output());
        assertEquals(expected, launches(fixture));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolutionAndDiscoveryCachesReuseAnswersAcrossWorktreesAndBuildStates() throws Exception {
        var fixture = cacheFixture();
        String[] request = {"maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile",
                "-r", "class-path,source-path"};
        var first = run(request);
        assertEquals(0, first.status(), first.error());
        var repeated = run(request);
        assertEquals(first.output(), repeated.output());
        assertEquals("", repeated.error());
        assertEquals(1, launches(fixture));
        var listing = discover(fixture, "--format", "tsv");
        assertEquals(0, listing.status(), listing.error());
        assertEquals(2, launches(fixture));
        Path pom = fixture.project().resolve("pom.xml");
        String original = Files.readString(pom);
        Files.writeString(pom, original + "<!-- another branch -->\n");
        assertEquals(0, run(request).status());
        assertEquals(3, launches(fixture));
        Files.writeString(pom, original);
        assertEquals(first.output(), run(request).output());
        assertEquals(3, launches(fixture));

        Path other = temporaryDirectory.resolve("other worktree");
        try (var paths = Files.walk(fixture.project())) {
            for (Path path : paths.toList()) {
                Path destination = other.resolve(fixture.project().relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
        other = other.toRealPath();
        request[2] = other.toString();
        var relocated = run(request);
        assertEquals(0, relocated.status(), relocated.error());
        assertEquals(first.output().replace(fixture.project().toString(), other.toString()), relocated.output());
        assertEquals("", relocated.error());
        var discovered = run("maven", "--project-base-dir", other.toString(), "--list-projects", "--format", "tsv");
        assertEquals(0, discovered.status(), discovered.error());
        assertEquals(listing.output().replace(fixture.project().toString(), other.toString()), discovered.output());
        assertEquals("", discovered.error());
        assertEquals(3, Files.readAllLines(other.resolve("launches.txt")).size());
    }

    private Fixture cacheFixture() throws Exception {
        var fixture = fixture(false);
        var files = List.of("pom.xml", "app/pom.xml", "parent.xml", "settings.xml");
        var values = cacheCapture(fixture);
        values.setProperty("build-files.count", Integer.toString(files.size()));
        for (int index = 0; index < files.size(); index++) {
            Path file = fixture.project().resolve(files.get(index));
            Files.writeString(file, "<project><!-- " + java.util.UUID.randomUUID() + " --></project>\n");
            values.setProperty("build-files." + index, file.toString());
        }
        storeCacheCapture(fixture, values);
        Path wrapper = fixture.project().resolve("mvnw");
        Files.writeString(wrapper, Files.readString(wrapper).replace("echo 'Maven build output'", "echo capture >> launches.txt\necho 'Maven build output'"));
        return fixture;
    }

    private static Properties cacheCapture(Fixture fixture) throws Exception {
        var values = new Properties();
        try (var input = Files.newInputStream(fixture.project().resolve("capture.properties"))) {
            values.load(input);
        }
        return values;
    }

    private static void storeCacheCapture(Fixture fixture, Properties values) throws Exception {
        try (var output = Files.newOutputStream(fixture.project().resolve("capture.properties"))) {
            values.store(output, null);
        }
    }

    private static int launches(Fixture fixture) throws Exception {
        return Files.readAllLines(fixture.project().resolve("launches.txt")).size();
    }

    @Test
    void validatesExplicitLocationsAndOperationBeforeLaunchingMaven() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        assertInvalid("--project-base-dir", "maven", "--list-projects");
        assertInvalid("does not exist", "maven", "--project-base-dir", project.resolve("missing").toString(), "--list-projects");
        assertInvalid("pom.xml", "maven", "--project-base-dir", project.toString(), "--list-projects");
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        assertInvalid("--list-projects", "maven", "--project-base-dir", project.toString());
        assertInvalid("only be specified once", "maven", "--project-base-dir", project.toString(), "--project-base-dir", project.toString(), "--list-projects");
        assertInvalid("only be specified once", "maven", "--project-base-dir", project.toString(), "--list-projects", "--project", "example:app", "--project", "example:other");
        assertInvalid("SELECTOR", "maven", "--project-base-dir", project.toString(), "--list-projects", "--project", "");
        assertInvalid("selector", "maven", "--project-base-dir", project.toString(), "--list-projects", "--project", " ");
        assertInvalid("unknown", "maven", "--project-base-dir", project.toString(), "--list-projects", "--unknown");
        assertInvalid("Unknown option", "maven", "--project-base-dir", project.toString(), "--list-project-dirs");
    }

    @Test
    void validatesArgumentResolutionBeforeLaunchingMaven() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        assertInvalid("--project", "maven", "--project-base-dir", project.toString(), "--scope", "compile", "-r", "class-path");
        assertInvalid("--scope", "maven", "--project-base-dir", project.toString(), "--project", "example:app", "-r", "class-path");
        assertInvalid("compile, runtime or test", "maven", "--project-base-dir", project.toString(), "--project", "example:app", "--scope", "provided", "-r", "class-path");
        assertInvalid("mutually exclusive", "maven", "--project-base-dir", project.toString(), "--list-projects", "-r", "class-path");
        assertInvalid("argument resolution", "maven", "--project-base-dir", project.toString(), "--list-projects", "--scope", "compile");
        assertInvalid("requires --resolve-options", "maven", "--project-base-dir", project.toString(), "--list-projects", "-w", project.resolve("args").toString());
        assertInvalid("unknown resolve options", "maven", "--project-base-dir", project.toString(), "--project", "example:app", "--scope", "compile", "-r", "imaginary");
        assertInvalid("must not be empty", "maven", "--project-base-dir", project.toString(), "--project", "example:app", "--scope", "compile", "-r", "class-path,");
        assertInvalid("unknown module form", "maven", "--project-base-dir", project.toString(), "--project", "example:app", "--scope", "compile", "-r", "module=unknown");
        assertInvalid("requires add-modules", "maven", "--project-base-dir", project.toString(), "--project", "example:app", "--scope", "compile", "-r", "module=roots");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolvesRequestedArgumentsToStdoutOrAnArgumentFile() throws Exception {
        var fixture = fixture(false);
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", "class-path,source-path");
        assertEquals(0, result.status(), result.error());
        String expected = "\"--class-path\"\n\"" + fixture.project().resolve("dependency.jar") + "\"\n\"--source-path\"\n\"" + fixture.project().resolve("sources") + "\"\n";
        assertEquals(expected, result.output());
        String invocation = Files.readString(fixture.project().resolve("invocation.txt"));
        assertTrue(invocation.contains("--projects\nexample:app\n"), invocation);
        assertTrue(invocation.contains("-Djig.maven.project=example:app\n"), invocation);
        assertTrue(invocation.contains("-Djig.maven.scope=compile\n"), invocation);
        assertTrue(invocation.contains("-Djig.maven.options=class-path,source-path\n"), invocation);
        Path file = fixture.project().resolve("tool args");
        var written = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile", "-r", "class-path,source-path", "-w", file.toString());
        assertEquals(0, written.status(), written.error());
        assertEquals("", written.output());
        assertEquals(expected, Files.readString(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void portableRequestsOmitOptionsNotSupportedByMaven() throws Exception {
        var fixture = fixture(false);
        String supported = "class-path,source-path";
        String portable = supported + ",upgrade-module-path,processor-module-path,patch-module,release,enable-preview,add-exports,processor-path,source,target,encoding,system,add-reads,d";
        var expected = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", supported);
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", portable);
        assertEquals(0, result.status(), result.error());
        assertEquals(expected.output(), result.output());
        var invocation = Files.readAllLines(fixture.project().resolve("invocation.txt"));
        assertTrue(invocation.contains("-Djig.maven.options=class-path,source-path"), invocation.toString());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unsupportedOnlyRequestsWriteAnEmptyArgumentFileWithoutPreparingBinaries() throws Exception {
        var fixture = fixture(false);
        Path file = fixture.project().resolve("unsupported.args");
        Path source = Files.createDirectories(fixture.project().resolve("sources"));
        Files.writeString(source.resolve("module-info.java"), "not a module declaration\n");
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile",
                "-r", "release,encoding,d", "-w", file.toString());
        assertEquals(0, result.status(), result.error());
        assertEquals("", result.output());
        assertEquals("", Files.readString(file));
        var invocation = Files.readAllLines(fixture.project().resolve("invocation.txt"));
        assertFalse(invocation.contains("--also-make"), invocation.toString());
        assertEquals("validate", invocation.getLast());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void sourceOptionsChooseThePathForTheSelectedProject() throws Exception {
        var fixture = fixture(false);
        var sources = Files.createDirectories(fixture.project().resolve("sources"));
        var arguments = new String[] {"maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app",
                "--scope", "compile", "-r", "source-path,module-source-path"};
        var classpath = run(arguments);
        assertEquals(0, classpath.status(), classpath.error());
        assertEquals("\"--source-path\"\n\"" + sources + "\"\n", classpath.output());
        Files.writeString(sources.resolve("module-info.java"), "module example.app {}\n");
        var modular = run(arguments);
        assertEquals(0, modular.status(), modular.error());
        assertEquals("\"--module-source-path\"\n\"example.app=" + sources + "\"\n", modular.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void formatterRequestsPreserveSupportedModuleOptionsAndTheModuleForm() throws Exception {
        var fixture = fixture(false);
        Path source = Files.createDirectories(fixture.project().resolve("sources"));
        Files.writeString(source.resolve("module-info.java"), "module example.app {}\n");
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile",
                "-r", "module-source-path,module=list,upgrade-module-path,patch-module,release,enable-preview,add-exports");
        assertEquals(0, result.status(), result.error());
        assertEquals("\"--module-source-path\"\n\"example.app=" + source + "\"\n\"--module\"\n\"example.app\"\n", result.output());
        assertTrue(Files.readAllLines(fixture.project().resolve("invocation.txt")).contains("-Djig.maven.options=module,module-source-path"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unknownOptionsInPortableRequestsFailBeforeLaunchingMaven() throws Exception {
        var fixture = fixture(false);
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile",
                "-r", "release,source-paht");
        assertEquals(2, result.status(), result.error());
        assertTrue(result.error().contains("unknown resolve options"), result.error());
        assertFalse(Files.exists(fixture.project().resolve("invocation.txt")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void binaryPathRequestsUseNativeBuildPreparationButSourceOnlyRequestsDoNot() throws Exception {
        var fixture = fixture(false);
        for (String scope : List.of("compile", "runtime", "test")) {
            var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", scope,
                    "-r", "class-path");
            assertEquals(0, result.status(), result.error());
            List<String> invocation = Files.readAllLines(fixture.project().resolve("invocation.txt"));
            assertTrue(invocation.contains("--also-make"), invocation.toString());
            assertEquals(scope.equals("test") ? "test-compile" : "compile", invocation.getLast());
        }
        var sources = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile",
                "-r", "source-path");
        assertEquals(0, sources.status(), sources.error());
        List<String> invocation = Files.readAllLines(fixture.project().resolve("invocation.txt"));
        assertFalse(invocation.contains("--also-make"), invocation.toString());
        assertEquals("validate", invocation.getLast());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aFailedResolutionDoesNotOverwriteAnExistingArgumentFile() throws Exception {
        var fixture = fixture(false);
        Path wrapper = fixture.project().resolve("mvnw");
        Files.writeString(wrapper, Files.readString(wrapper) + "exit 7\n");
        Path file = fixture.project().resolve("tool.args");
        Files.writeString(file, "original\n");
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "runtime", "-r", "class-path", "-w", file.toString());
        assertEquals(7, result.status(), result.error());
        assertEquals("", result.output());
        assertEquals("original\n", Files.readString(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void incompleteArgumentsAreRejectedBeforeOverwritingAnArgumentFile() throws Exception {
        var fixture = fixture(false);
        Path capture = fixture.project().resolve("capture.properties");
        var values = new Properties();
        try (var input = Files.newInputStream(capture)) {
            values.load(input);
        }
        values.remove("sources.0");
        try (var output = Files.newOutputStream(capture)) {
            values.store(output, null);
        }
        Path file = fixture.project().resolve("tool.args");
        Files.writeString(file, "original\n");
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", "class-path,source-path", "-w", file.toString());
        assertEquals(1, result.status(), result.error());
        assertTrue(result.error().contains("Missing Maven capture entry: sources.0"), result.error());
        assertEquals("", result.output());
        assertEquals("original\n", Files.readString(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void projectsNamedModulesAndLeavesUnnamedArtifactsOnTheClasspath() throws Exception {
        var fixture = fixture(false);
        Path sources = Files.createDirectories(fixture.project().resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "/** @mainClass example.Main */ module example.app { requires example.dep; }\n");
        Path main = Files.createDirectories(sources.resolve("example")).resolve("Main.java");
        Files.writeString(main, "package example; public class Main { public static void main(String[] args) {} }\n");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "example.dep");
        try (var jar = new JarOutputStream(Files.newOutputStream(fixture.project().resolve("dependency.jar")), manifest)) {
            jar.finish();
        }
        Path plain = fixture.project().resolve("plain.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(plain))) {
            // A default-package entry is valid on the classpath, not the module path.
            jar.putNextEntry(new JarEntry("Plain.class"));
            jar.write(new byte[] {0});
            jar.closeEntry();
        }
        Path capture = fixture.project().resolve("capture.properties");
        var values = new Properties();
        try (var input = Files.newInputStream(capture)) {
            values.load(input);
        }
        values.setProperty("classpath.count", "3");
        values.setProperty("classpath.1", plain.toString());
        values.setProperty("classpath.2", fixture.project().resolve("output").toString());
        try (var output = Files.newOutputStream(capture)) {
            values.store(output, null);
        }
        var compile = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", "class-path,module-path,module-source-path,module");
        assertEquals(0, compile.status(), compile.error());
        assertEquals("\"--class-path\"\n\"" + plain + "\"\n\"--module-path\"\n\"" + fixture.project().resolve("dependency.jar")
                + "\"\n\"--module-source-path\"\n\"example.app=" + sources + "\"\n\"--module\"\n\"example.app\"\n", compile.output());
        var binaryCompile = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", "module-path,source-path");
        assertEquals(0, binaryCompile.status(), binaryCompile.error());
        assertTrue(binaryCompile.output().contains(fixture.project().resolve("output").toString()), binaryCompile.output());
        var runtime = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "runtime", "-r", "module-path,module=main");
        assertEquals(0, runtime.status(), runtime.error());
        assertTrue(runtime.output().contains(fixture.project().resolve("output").toString()), runtime.output());
        assertTrue(runtime.output().contains("\"--module\"\n\"example.app/example.Main\""), runtime.output());
        assertFalse(runtime.output().contains(plain.toString()), runtime.output());
        for (String form : List.of("module=single", "module=list", "module=roots,add-modules")) {
            var selected = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", form);
            assertEquals(0, selected.status(), selected.error());
            assertEquals("\"--module\"\n\"example.app\"\n", selected.output());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nonmodularScopesDoNotInventModuleArguments() throws Exception {
        var fixture = fixture(false);
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", "example:app", "--scope", "compile", "-r", "module-path,module-source-path,module,add-modules,describe-module");
        assertEquals(0, result.status(), result.error());
        assertEquals("", result.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void wrapperDiscoveryPrintsOnlyQualifiedProjectIdentifiers() throws Exception {
        var fixture = fixture(false);
        var result = discover(fixture);
        assertEquals(0, result.status(), result.error());
        assertEquals(List.of("example:app", "example:root"), result.output().lines().toList());
        assertTrue(result.error().contains("Maven build output"), result.error());
        assertFalse(result.output().contains("Maven build output"));
        String invocation = Files.readString(fixture.project().resolve("invocation.txt"));
        assertTrue(invocation.contains("--file\n" + fixture.project().resolve("pom.xml")), invocation);
        assertTrue(invocation.contains("-Dmaven.ext.class.path="), invocation);
        assertTrue(invocation.endsWith("validate\n"), invocation);
        assertFalse(Files.exists(fixture.project().resolve(".gradle/jig")));
        assertFalse(Files.exists(fixture.project().resolve("target")));
    }

    @Test
    void validatesProjectListingFormatsBeforeLaunchingMaven() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        assertInvalid("text or tsv", "maven", "--project-base-dir", project.toString(),
                "--list-projects", "--format", "json");
        assertInvalid("only be specified once", "maven", "--project-base-dir", project.toString(),
                "--list-projects", "--format", "tsv", "--format", "text");
        assertInvalid("only to project listing", "maven", "--project-base-dir", project.toString(),
                "--project", ":app", "--scope", "compile", "-r", "source-path", "--format", "tsv");
        assertTrue(run("__complete", "maven", "--for").output().contains("--format\t"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void discoveryListsProjectDirectoriesAsTsv() throws Exception {
        var fixture = fixture(false);
        var result = discover(fixture, "--format", "tsv");
        assertEquals(0, result.status(), result.error());
        assertEquals(projectTsv(Map.of("example:app", fixture.project().resolve("app"), "example:root", fixture.project())), result.output());
        assertTrue(result.error().contains("Maven build output"), result.error());
        var text = discover(fixture, "--format", "text");
        assertEquals(0, text.status(), text.error());
        assertEquals("example:app\nexample:root\n", text.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void invalidProjectDirectoriesNeverPublishPartialTsv() throws Exception {
        var fixture = fixture(false);
        Path capture = fixture.project().resolve("capture.properties");
        var original = new Properties();
        try (var input = Files.newInputStream(capture)) {
            original.load(input);
        }
        for (String field : List.of("project-directories.count", "project-directories.1")) {
            var values = new Properties();
            values.putAll(original);
            values.remove(field);
            try (var output = Files.newOutputStream(capture)) {
                values.store(output, null);
            }
            var result = discover(fixture, "--format", "tsv");
            assertEquals(1, result.status(), result.error());
            assertEquals("", result.output());
            assertTrue(result.error().contains(field), result.error());
        }
        for (String directory : List.of("relative", "", fixture.project() + "\tpart",
                fixture.project() + "\npart", fixture.project() + "\rpart")) {
            var values = new Properties();
            values.putAll(original);
            values.setProperty("project-directories.1", directory);
            try (var output = Files.newOutputStream(capture)) {
                values.store(output, null);
            }
            var result = discover(fixture, "--format", "tsv");
            assertEquals(1, result.status(), result.error());
            assertEquals("", result.output());
        }
        original.setProperty("project-directories.2", fixture.project().resolve("different").toString());
        try (var output = Files.newOutputStream(capture)) {
            original.store(output, null);
        }
        var conflicting = discover(fixture, "--format", "tsv");
        assertEquals(1, conflicting.status(), conflicting.error());
        assertEquals("", conflicting.output());
        assertTrue(conflicting.error().contains("Conflicting project directory"), conflicting.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void passesProjectSelectorsToMavenWithoutReinterpretingThem() throws Exception {
        var fixture = fixture(false);
        for (String selector : List.of("example:app", ":app")) {
            var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", selector, "--list-projects");
            assertEquals(0, result.status(), result.error());
            String invocation = Files.readString(fixture.project().resolve("invocation.txt"));
            assertTrue(invocation.contains("--projects\n" + selector + "\n"), invocation);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsPathAndMalformedProjectSelectorsBeforeLaunchingMaven() throws Exception {
        var fixture = fixture(false);
        for (String selector : List.of("app", "./app", "../app", fixture.project().toString(), ":", "example:", "example:app:1", "example:app,example:other")) {
            assertInvalid("groupId:artifactId", "maven", "--project-base-dir", fixture.project().toString(), "--project", selector, "--list-projects");
        }
        assertFalse(Files.exists(fixture.project().resolve("invocation.txt")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void compilesAndReusesOnlyTheJava8ExtensionArtifact() throws Exception {
        var fixture = fixture(false);
        assertEquals(0, discover(fixture).status());
        Path first = extension(fixture);
        var modified = Files.getLastModifiedTime(first);
        assertEquals(0, discover(fixture).status());
        assertEquals(first, extension(fixture));
        assertEquals(modified, Files.getLastModifiedTime(first));
        Path cache = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv()).toAbsolutePath().normalize();
        assertTrue(first.startsWith(cache), first.toString());
        assertTrue(first.getFileName().toString().matches("[0-9a-f]{64}\\.jar"), first.toString());
        try (var jar = new JarFile(first.toFile()); var input = new DataInputStream(jar.getInputStream(jar.getJarEntry("com/netflix/tools/jig/maven/capture/Capture.class")))) {
            assertEquals(0xcafebabe, input.readInt());
            input.readUnsignedShort();
            assertEquals(52, input.readUnsignedShort());
            assertTrue(jar.getJarEntry("META-INF/plexus/components.xml") != null);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void supportsMaven4sLifecycleInterface() throws Exception {
        var fixture = fixture(true);
        var result = discover(fixture);
        assertEquals(0, result.status(), result.error());
        assertTrue(Files.isRegularFile(extension(fixture)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void failedOrMissingCaptureNeverPublishesProjects() throws Exception {
        var fixture = fixture(false);
        Path wrapper = fixture.project().resolve("mvnw");
        Files.writeString(wrapper, Files.readString(wrapper) + "exit 7\n");
        for (String format : List.of("text", "tsv")) {
            var failed = discover(fixture, "--format", format);
            assertEquals(7, failed.status(), failed.error());
            assertEquals("", failed.output());
        }
        Files.writeString(wrapper, Files.readString(wrapper).replace("printf 'jig-maven:'", "printf 'not-capture:'").replace("exit 7", "exit 0"));
        var missing = discover(fixture);
        assertEquals(1, missing.status(), missing.error());
        assertEquals("", missing.output());
        assertTrue(missing.error().contains("capture"), missing.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void invalidCaptureIsAnErrorWithoutPartialOutput() throws Exception {
        var fixture = fixture(false);
        Path wrapper = fixture.project().resolve("mvnw");
        Files.writeString(wrapper, Files.readString(wrapper).replace("base64 | tr -d '\\n'", "{ printf 'not-base64!'; }"));
        var result = discover(fixture);
        assertEquals(1, result.status(), result.error());
        assertEquals("", result.output());
        assertTrue(result.error().contains("Invalid Maven capture"), result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void concurrentRequestsHaveIndependentProcessResponses() throws Exception {
        var fixture = fixture(false);
        Path wrapper = fixture.project().resolve("mvnw");
        Files.writeString(wrapper, Files.readString(wrapper).replace("echo 'Maven build output'", """
                touch "ready-$$"
                attempts=0
                while [ "$(find . -name 'ready-*' | wc -l)" -lt 2 ]; do
                    attempts=$((attempts + 1))
                    if [ "$attempts" -ge 100 ]; then exit 11; fi
                    sleep 0.02
                done
                echo 'Maven build output'
                """));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> { start.await(); return discover(fixture); }));
            }
            start.countDown();
            var first = futures.getFirst().get();
            var second = futures.getLast().get();
            assertEquals(0, first.status(), first.error());
            assertEquals(0, second.status(), second.error());
            assertEquals(first.output(), second.output());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void diagnosticsAreForwardedBeforeMavenExits() throws Exception {
        var fixture = fixture(false);
        Path wrapper = fixture.project().resolve("mvnw");
        Files.writeString(wrapper, Files.readString(wrapper).replace("echo 'Maven build output'", """
                echo 'Maven build output'
                attempts=0
                while [ ! -f diagnostic-received ]; do
                    attempts=$((attempts + 1))
                    if [ "$attempts" -ge 200 ]; then exit 11; fi
                    sleep 0.02
                done
                """));
        var output = new StringWriter();
        var error = new StringWriter();
        var diagnostics = new PrintWriter(error, true) {
            @Override
            public void println(String line) {
                super.println(line);
                if (line.equals("Maven build output")) {
                    try {
                        Files.writeString(fixture.project().resolve("diagnostic-received"), "received\n");
                    } catch (java.io.IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }
            }
        };
        int status = new Jig().run(new PrintWriter(output, true), diagnostics,
                "maven", "--project-base-dir", fixture.project().toString(), "--list-projects");
        assertEquals(0, status, error.toString());
        assertTrue(output.toString().contains("example:app"), output.toString());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void extensionCacheIncludesNonCoreCompilationLibraries() throws Exception {
        var fixture = fixture(false);
        assertEquals(0, discover(fixture).status());
        Path first = extension(fixture);
        Path library = temporaryDirectory.resolve("maven home/lib/extra.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new JarEntry("extra.properties"));
            jar.write(new byte[] {1});
            jar.closeEntry();
        }
        assertEquals(0, discover(fixture).status());
        Path second = extension(fixture);
        assertFalse(first.equals(second), second.toString());
        try (var jar = new JarOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new JarEntry("extra.properties"));
            jar.write(new byte[] {2});
            jar.closeEntry();
        }
        assertEquals(0, discover(fixture).status());
        assertFalse(second.equals(extension(fixture)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theCapturedPreparationDecisionControlsSelectedBinaryExclusion() throws Exception {
        var fixture = fixture(false);
        Path sources = Files.createDirectories(fixture.project().resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module generated.mod {}\n");
        Path capture = fixture.project().resolve("capture.properties");
        var values = new Properties();
        try (var input = Files.newInputStream(capture)) {
            values.load(input);
        }
        values.setProperty("classpath.count", "2");
        values.setProperty("classpath.1", fixture.project().resolve("output").toString());
        try (var output = Files.newOutputStream(capture)) {
            values.store(output, null);
        }
        // Model a generator creating module-info.java after preparation chose binaries.
        Path wrapper = fixture.project().resolve("mvnw");
        Files.writeString(wrapper, Files.readString(wrapper).replace("\"$scope\" \"$source_view\"", "\"$scope\" false"));
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile",
                "-r", "module-path,module-source-path");
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains(fixture.project().resolve("output").toString()), result.output());
        assertTrue(result.output().contains("generated.mod=" + sources), result.output());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void duplicateOrIncompleteCapturesDoNotOverwriteArgumentFiles() throws Exception {
        var fixture = fixture(false);
        Path wrapper = fixture.project().resolve("mvnw");
        String script = Files.readString(wrapper);
        Files.writeString(wrapper, script + "printf 'jig-maven:'; base64 < capture.properties | tr -d '\\n'; printf '\\n'\n");
        Path file = fixture.project().resolve("tool.args");
        Files.writeString(file, "original\n");
        var duplicate = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile",
                "-r", "class-path", "-w", file.toString());
        assertEquals(1, duplicate.status(), duplicate.error());
        assertTrue(duplicate.error().contains("Duplicate Maven capture"), duplicate.error());
        assertEquals("original\n", Files.readString(file));
        Files.writeString(wrapper, script);
        var original = new Properties();
        Path capture = fixture.project().resolve("capture.properties");
        try (var input = Files.newInputStream(capture)) {
            original.load(input);
        }
        for (String field : List.of("version", "project", "projects.count", "sources.count", "classpath.count", "output-directory")) {
            var values = new Properties();
            values.putAll(original);
            values.remove(field);
            try (var output = Files.newOutputStream(capture)) {
                values.store(output, null);
            }
            var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile",
                    "-r", "class-path", "-w", file.toString());
            assertEquals(1, result.status(), field + ": " + result.error());
            assertTrue(result.error().contains(field), result.error());
            assertEquals("original\n", Files.readString(file));
        }
        for (String project : List.of("malformed", "example:other")) {
            original.setProperty("project", project);
            original.setProperty("projects.0", project);
            try (var output = Files.newOutputStream(capture)) {
                original.store(output, null);
            }
            var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile",
                    "-r", "class-path", "-w", file.toString());
            assertEquals(1, result.status(), result.error());
            assertTrue(result.error().contains("Unexpected Maven capture project"), result.error());
            assertEquals("original\n", Files.readString(file));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aMissingMainClassIsAnArgumentErrorWithoutAStackTrace() throws Exception {
        var fixture = fixture(false);
        Path sources = Files.createDirectories(fixture.project().resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module example.app {}\n");
        var result = run("maven", "--project-base-dir", fixture.project().toString(), "--project", ":app", "--scope", "compile",
                "-r", "module=main");
        assertEquals(2, result.status(), result.error());
        assertTrue(result.error().contains("No main class declared"), result.error());
        assertFalse(result.error().contains("Exception"), result.error());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void projectListingIncludesNativeScopesWithDirectoriesFirst() throws Exception {
        var fixture = fixture(false);
        var expected = new ArrayList<String>();
        for (String project : List.of("example:app", "example:root")) {
            Path location = project.endsWith(":app") ? fixture.project().resolve("app") : fixture.project();
            for (String scope : List.of("compile", "runtime", "test")) {
                expected.add(location + "\t" + project + "\t" + scope);
            }
        }
        var result = discover(fixture, "--format", "tsv");
        assertEquals(0, result.status(), result.error());
        assertEquals(expected.stream().sorted().toList(), result.output().lines().toList());
        assertTrue(result.error().contains("Maven build output"), result.error());
        var invocation = Files.readAllLines(fixture.project().resolve("invocation.txt"));
        assertEquals("validate", invocation.getLast());
        assertFalse(invocation.contains("--also-make"));
        assertFalse(invocation.stream().anyMatch(argument -> argument.startsWith("-Djig.maven.scope=")));
        assertFalse(Files.exists(fixture.project().resolve("target")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void contextListingRejectsInvalidCapturesWithoutPartialOutput() throws Exception {
        var fixture = fixture(false);
        Path capture = fixture.project().resolve("capture.properties");
        var original = new Properties();
        try (var input = Files.newInputStream(capture)) {
            original.load(input);
        }
        for (String directory : List.of("relative", "", fixture.project() + "\tpart", fixture.project() + "\npart")) {
            var values = new Properties();
            values.putAll(original);
            values.setProperty("project-directories.1", directory);
            try (var output = Files.newOutputStream(capture)) {
                values.store(output, null);
            }
            var result = discover(fixture, "--format", "tsv");
            assertEquals(1, result.status(), result.error());
            assertEquals("", result.output());
        }
        original.remove("project-directories.1");
        try (var output = Files.newOutputStream(capture)) {
            original.store(output, null);
        }
        var incomplete = discover(fixture, "--format", "tsv");
        assertEquals(1, incomplete.status(), incomplete.error());
        assertEquals("", incomplete.output());
    }

    @Test
    void richerListingDoesNotAddDefaultScopesOrCombinedSelectors() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("contexts"));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        assertInvalid("--scope", "maven", "--project-base-dir", root.toString(), "--project", "example:app", "-r", "source-path");
        assertInvalid("selector", "maven", "--project-base-dir", root.toString(), "--project", "example:app/test", "--scope", "test", "-r", "source-path");
        assertInvalid("only to project listing", "maven", "--project-base-dir", root.toString(), "--project", "example:app",
                "--scope", "compile", "-r", "source-path", "--format", "tsv");
        assertFalse(run("maven", "--help").output().contains("tsv-contexts"));
        assertInvalid("text or tsv", "maven", "--project-base-dir", root.toString(), "--list-projects", "--format", "tsv-contexts");
    }

    private Fixture fixture(boolean lifecycleInterface) throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project with spaces")).toRealPath();
        Files.createDirectories(project.resolve("app"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Path home = mavenHome(lifecycleInterface);
        var values = new Properties();
        values.setProperty("version", "2");
        values.setProperty("project", "example:app");
        values.setProperty("projects.count", "3");
        values.setProperty("projects.0", "example:app");
        values.setProperty("projects.1", "example:root");
        values.setProperty("projects.2", "example:app");
        values.setProperty("project-directories.count", "3");
        values.setProperty("project-directories.0", project.resolve("app").toString());
        values.setProperty("project-directories.1", project.toString());
        values.setProperty("project-directories.2", project.resolve("app").toString());
        values.setProperty("classpath.count", "1");
        values.setProperty("classpath.0", project.resolve("dependency.jar").toString());
        values.setProperty("sources.count", "1");
        values.setProperty("sources.0", project.resolve("sources").toString());
        values.setProperty("output-directory", project.resolve("output").toString());
        try (var output = Files.newOutputStream(project.resolve("capture.properties"))) {
            values.store(output, "fixture");
        }
        Files.writeString(project.resolve("mvnw"), """
                #!/bin/sh
                for argument do
                    if [ "$argument" = '--version' ]; then
                        printf 'Apache Maven fixture\nMaven home: %%s\n' %s
                        exit 0
                    fi
                done
                printf '%%s\n' "$@" > invocation.txt
                echo 'Maven build output'
                scope=
                options=
                for argument do
                    case "$argument" in
                        -Djig.maven.scope=*) scope=${argument#*=} ;;
                        -Djig.maven.options=*) options=${argument#*=} ;;
                    esac
                done
                printf 'jig-maven:'
                {
                    while IFS= read -r line; do printf '%%s\\n' "$line"; done < capture.properties
                    if [ -z "$scope" ]; then
                        printf 'kind=projects\\n'
                    else
                        source_option=source-path
                        if [ -f sources/module-info.java ]; then source_option=module-source-path; fi
                        source_view=false
                        case ",$options," in *,"$source_option",*) source_view=true ;; esac
                        printf 'kind=arguments\\nscope=%%s\\nsource-view=%%s\\n' "$scope" "$source_view"
                    fi
                } | base64 | tr -d '\\n'
                printf '\\n'
                """.formatted(shellQuote(home.toString())));
        return new Fixture(project);
    }

    private Path mavenHome(boolean lifecycleInterface) throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("maven home"));
        Path sources = Files.createDirectories(home.resolve("sources"));
        Path session = sources.resolve("MavenSession.java");
        Path lifecycleParticipant = sources.resolve("AbstractMavenLifecycleParticipant.java");
        Path listener = sources.resolve("ExecutionListener.java");
        Path request = sources.resolve("MavenExecutionRequest.java");
        Path event = sources.resolve("ExecutionEvent.java");
        Path result = sources.resolve("MavenExecutionResult.java");
        Path project = sources.resolve("MavenProject.java");
        Path lifecycle = sources.resolve("LifecycleStarter.java");
        Path resolver = sources.resolve("LifecycleDependencyResolver.java");
        Path filter = sources.resolve("ArtifactFilter.java");
        Path cumulative = sources.resolve("CumulativeScopeArtifactFilter.java");
        Path build = sources.resolve("Build.java");
        Files.writeString(session, "package org.apache.maven.execution; public class MavenSession { public java.util.List<org.apache.maven.project.MavenProject> getProjects() { return java.util.Collections.emptyList(); } public void setProjects(java.util.List<org.apache.maven.project.MavenProject> projects) {} public MavenExecutionResult getResult() { return new MavenExecutionResult(); } public MavenSession clone() { return this; } public MavenExecutionRequest getRequest() { return new MavenExecutionRequest(); } public java.util.Properties getUserProperties() { return new java.util.Properties(); } public void setCurrentProject(org.apache.maven.project.MavenProject project) {} }\n");
        Files.writeString(lifecycleParticipant, "package org.apache.maven; public abstract class AbstractMavenLifecycleParticipant { public void afterProjectsRead(org.apache.maven.execution.MavenSession session) {} }\n");
        Files.writeString(listener, "package org.apache.maven.execution; public interface ExecutionListener {}\n");
        Files.writeString(request, "package org.apache.maven.execution; public class MavenExecutionRequest { public java.io.File getUserSettingsFile() { return null; } public java.io.File getGlobalSettingsFile() { return null; } public ExecutionListener getExecutionListener() { return null; } public MavenExecutionRequest setExecutionListener(ExecutionListener listener) { return this; } public MavenExecutionRequest setGoals(java.util.List<String> goals) { return this; } }\n");
        Files.writeString(event, "package org.apache.maven.execution; public interface ExecutionEvent { enum Type { SessionStarted, SessionEnded } Type getType(); MavenSession getSession(); }\n");
        Files.writeString(result, "package org.apache.maven.execution; public class MavenExecutionResult { public boolean hasExceptions() { return false; } }\n");
        Files.writeString(project, """
                package org.apache.maven.project;
                public class MavenProject {
                    public java.io.File getFile() { return null; }
                    public MavenProject getParent() { return null; }
                    public String getGroupId() { return null; }
                    public String getArtifactId() { return null; }
                    public java.io.File getBasedir() { return null; }
                    public java.util.List<String> getCompileSourceRoots() { return java.util.Collections.emptyList(); }
                    public java.util.List<String> getTestCompileSourceRoots() { return java.util.Collections.emptyList(); }
                    public java.util.List<String> getCompileClasspathElements() { return java.util.Collections.emptyList(); }
                    public java.util.List<String> getRuntimeClasspathElements() { return java.util.Collections.emptyList(); }
                    public java.util.List<String> getTestClasspathElements() { return java.util.Collections.emptyList(); }
                    public void setArtifactFilter(org.apache.maven.artifact.resolver.filter.ArtifactFilter filter) {}
                    public org.apache.maven.model.Build getBuild() { return new org.apache.maven.model.Build(); }
                }
                """);
        Files.writeString(resolver, "package org.apache.maven.lifecycle.internal; public class LifecycleDependencyResolver { public void resolveProjectDependencies(org.apache.maven.project.MavenProject project, java.util.Collection<String> collect, java.util.Collection<String> resolve, org.apache.maven.execution.MavenSession session, boolean aggregating, java.util.Set<?> artifacts) {} }\n");
        Files.writeString(build, "package org.apache.maven.model; public class Build { public String getOutputDirectory() { return null; } public String getTestOutputDirectory() { return null; } }\n");
        Files.writeString(filter, "package org.apache.maven.artifact.resolver.filter; public interface ArtifactFilter {}\n");
        Files.writeString(cumulative, "package org.apache.maven.artifact.resolver.filter; public class CumulativeScopeArtifactFilter implements ArtifactFilter { public CumulativeScopeArtifactFilter(java.util.Collection<String> scopes) {} }\n");
        Files.writeString(lifecycle, lifecycleInterface
                ? "package org.apache.maven.lifecycle.internal; public interface LifecycleStarter { void execute(org.apache.maven.execution.MavenSession session); }\n"
                : "package org.apache.maven.lifecycle.internal; public class LifecycleStarter { public void execute(org.apache.maven.execution.MavenSession session) {} }\n");
        Path classes = home.resolve("classes");
        var diagnostics = new StringWriter();
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(new PrintWriter(diagnostics), new PrintWriter(diagnostics),
                "--release", "8", "-Xlint:-options", "-g:none", "-d", classes.toString(), session.toString(), lifecycleParticipant.toString(), listener.toString(), request.toString(), event.toString(), result.toString(), project.toString(), lifecycle.toString(), resolver.toString(), filter.toString(), cumulative.toString(), build.toString()), diagnostics.toString());
        Path library = Files.createDirectories(home.resolve("lib")).resolve("maven-core-fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(library)); var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0);
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return home;
    }

    private static Path extension(Fixture fixture) throws Exception {
        return Files.readAllLines(fixture.project().resolve("invocation.txt")).stream()
                .filter(argument -> argument.startsWith("-Dmaven.ext.class.path="))
                .map(argument -> Path.of(argument.substring("-Dmaven.ext.class.path=".length()))).findFirst().orElseThrow();
    }

    private static String projectTsv(Map<String, Path> projects) {
        var rows = new ArrayList<String>();
        projects.forEach((selector, directory) -> {
            for (String scope : List.of("compile", "runtime", "test")) {
                rows.add(directory + "\t" + selector + "\t" + scope);
            }
        });
        return String.join("\n", rows.stream().sorted().toList()) + "\n";
    }

    private static Result discover(Fixture fixture, String... options) {
        var arguments = new ArrayList<>(List.of("maven", "--project-base-dir", fixture.project().toString(), "--list-projects"));
        arguments.addAll(List.of(options));
        return run(arguments.toArray(String[]::new));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void assertInvalid(String message, String... arguments) {
        var result = run(arguments);
        assertEquals(2, result.status(), result.error());
        assertTrue(result.error().contains(message), result.error());
        assertEquals("", result.output());
    }

    private static Result run(String... arguments) {
        var output = new StringWriter();
        var error = new StringWriter();
        int status = new Jig().run(new PrintWriter(output, true), new PrintWriter(error, true), arguments);
        return new Result(status, output.toString(), error.toString());
    }

    private record Fixture(Path project) {}
    private record Result(int status, String output, String error) {}
}
