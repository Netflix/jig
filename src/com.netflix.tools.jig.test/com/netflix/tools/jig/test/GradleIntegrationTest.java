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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes.Name;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import com.netflix.tools.jig.Jig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests each Gradle version against local projects without remote project dependencies. */
@EnabledOnOs({OS.LINUX, OS.MAC})
@EnabledIf("availableJdks")
@ParameterizedClass(name = "Gradle {0}")
@MethodSource("gradleVersions")
class GradleIntegrationTest {
    @Parameter
    GradleDistribution gradle;

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversProjectsAndResolvesSourcesWithoutResolvingDependencies() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root()
                .resolve("prevent-resolution"),
                "");
        String projects = run("gradle", "--root-project-dir",
                fixture.root().toString(), "--list-project-dirs");
        assertEquals(
                List.of(
                                fixture.root().toString(),
                                fixture.app().toString(),
                                fixture.root()
                                       .resolve("library")
                                       .toString(),
                                fixture.root()
                                       .resolve("plain")
                                       .toString())
                        .stream()
                        .sorted()
                        .toList(),
                projects.lines().toList());
        String source = resolve(fixture, "compile", "source-path,release");
        assertTrue(source.contains(fixture.app()
                .resolve("sources/java")
                .toString()),
                source);
        if (supportsRelease()) {
            assertTrue(source.contains("\"--release\"\n\"" + javaRelease() + "\""), source);
        }
        assertFalse(Files.exists(fixture.app()
                .resolve("build")));
        assertEquals(
                "",
                run(
                        "gradle",
                        "--root-project-dir",
                        fixture.root().toString(),
                        "--project-dir",
                        fixture.root()
                               .resolve("plain")
                               .toString(),
                        "--scope",
                        "compile",
                        "-r",
                        "source-path,class-path,release"));
    }

    @Test
    void resolvesScopeSpecificPathsAndMaterializesDependencyOutputs() throws Exception {
        var fixture = fixture(false);
        String compile = resolve(fixture, "compile", "class-path");
        assertTrue(compile.contains("compile only.jar"), compile);
        assertTrue(compile.contains("shared.jar"), compile);
        assertTrue(compile.contains("library/" + classesDirectory()), compile);
        assertFalse(compile.contains("runtime only.jar"), compile);
        assertTrue(Files.isRegularFile(fixture.root().resolve("library/" + classesDirectory() + "/lib/Library.class")));
        assertFalse(Files.exists(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
        String runtime = resolve(fixture, "runtime", "class-path");
        assertTrue(runtime.contains("runtime only.jar"), runtime);
        assertTrue(runtime.contains("shared.jar"), runtime);
        assertFalse(runtime.contains("compile only.jar"), runtime);
        assertTrue(Files.isRegularFile(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
    }

    @Test
    @EnabledIf("supportsModules")
    void resolvesModuleOptionsThatComposeWithJavacAndJava() throws Exception {
        var fixture = fixture(true);
        Path options = temporaryDirectory.resolve("compile.args");
        run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-dir",
                fixture.app().toString(),
                "--scope",
                "compile",
                "-r",
                "module-path,class-path,module-source-path,module,release",
                "-w",
                options.toString());
        String captured = Files.readString(options);
        assertTrue(captured.contains("\"--module-path\""), captured);
        assertTrue(captured.contains("\"--class-path\""), captured);
        assertTrue(captured.contains("app.mod=" + fixture.app().resolve("sources/java")), captured);
        assertTrue(captured.contains("\"--module\"\n\"app.mod\""), captured);
        Path compiled = temporaryDirectory.resolve("compiled");
        execute("javac", "@" + options, "-d", compiled.toString());
        assertTrue(Files.isRegularFile(compiled.resolve("app.mod/module-info.class")));
        Path runtimeOptions = temporaryDirectory.resolve("runtime.args");
        run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-dir",
                fixture.app().toString(),
                "--scope",
                "runtime",
                "-r",
                "module-path,class-path,module=main",
                "-w",
                runtimeOptions.toString());
        String runtime = Files.readString(runtimeOptions);
        assertTrue(runtime.contains("\"--module\"\n\"app.mod/app.Main\""), runtime);
        assertEquals("hello", execute("java", "@" + runtimeOptions).strip());
    }

    @Test
    @EnabledIf("supportsModules")
    void respectsDisabledModulePathInference() throws Exception {
        var fixture = fixture(true);
        Files.writeString(fixture.app().resolve("build.gradle"), "\njava.modularity.inferModulePath = false\n",
                StandardOpenOption.APPEND);
        String capture = resolve(fixture, "compile", "module-path,class-path,module,source-path");
        assertTrue(capture.contains("\"--class-path\""), capture);
        assertFalse(capture.contains("\"--module-path\""), capture);
        assertFalse(capture.contains("\"--module\""), capture);
    }

    @Test
    @EnabledIf("supportsRelease")
    void capturesEffectiveReleaseWithoutConflictingSourceAndTargetOptions() throws Exception {
        var fixture = fixture(false);
        String capture = resolve(fixture, "compile", "release,source,target");
        String expected = "\"--release\"\n\"" + javaRelease() + "\"\n";
        assertEquals(expected, capture);
        Files.writeString(fixture.app().resolve("build.gradle"), "\ntasks.compileJava { options.release = null; options.compilerArgs += ['--release', '" + javaRelease() + "'] }\n",
                StandardOpenOption.APPEND);
        assertEquals(expected, resolve(fixture, "compile", "release,source,target"));
    }

    @Test
    @EnabledIf("supportsArgumentProviders")
    void materializesAnnotationProcessorDependenciesWithoutCompilingTheSelectedProject() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), "\ndependencies { annotationProcessor project(':library') }\n",
                StandardOpenOption.APPEND);
        String capture = resolve(fixture, "compile", "processor-path");
        assertTrue(capture.contains("library/build/libs/library.jar"), capture);
        assertTrue(Files.isRegularFile(fixture.root()
                .resolve("library/build/libs/library.jar")));
        assertFalse(Files.exists(fixture.app()
                .resolve("build/classes/java/main/app/Main.class")));
    }

    @Test
    @EnabledIf("supportsModules")
    void runtimeModuleInferenceUsesTheRunTasksConfiguration() throws Exception {
        var fixture = fixture(true);
        Files.writeString(fixture.app().resolve("build.gradle"), "\ntasks.run { modularity.inferModulePath = false }\n",
                java.nio.file.StandardOpenOption.APPEND);
        String capture = resolve(fixture, "runtime", "class-path,module-path,module=main");
        assertTrue(capture.contains("\"--class-path\""), capture);
        assertFalse(capture.contains("\"--module-path\""), capture);
        assertFalse(capture.contains("\"--module\""), capture);
    }

    @Test
    @EnabledIf("supportsArgumentProviders")
    void capturesArgumentProvidersAndApplicationMetadata() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                tasks.compileJava.options.compilerArgumentProviders.add(new CommandLineArgumentProvider() {
                    Iterable<String> asArguments() { return ['--add-exports', 'java.base/java.lang=ALL-UNNAMED'] }
                })
                tasks.run.jvmArgumentProviders.add(new CommandLineArgumentProvider() {
                    Iterable<String> asArguments() { return ['--add-opens', 'java.base/java.lang=ALL-UNNAMED'] }
                })
                """, java.nio.file.StandardOpenOption.APPEND);
        assertEquals("\"--main-class\"\n\"app.Main\"\n\"--add-exports\"\n\"java.base/java.lang=ALL-UNNAMED\"\n",
                resolve(fixture, "compile", "main-class,add-exports"));
        assertEquals("\"--add-opens\"\n\"java.base/java.lang=ALL-UNNAMED\"\n",
                resolve(fixture, "runtime", "add-opens"));
    }

    @Test
    @EnabledIf("supportsLazyTasks")
    void sourceDiscoveryDoesNotRealizeCompileRunOrUnrelatedTasks() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                tasks.named('compileJava').configure { throw new GradleException('compileJava was realized') }
                tasks.register('unrelated') { throw new GradleException('unrelated task was realized') }
                afterEvaluate { sourceSets.main.java.srcDirs = ['late/sources'] }
                """ + (atLeast(5, 0) ? "tasks.named('run').configure { throw new GradleException('run was realized') }\n" : ""), StandardOpenOption.APPEND);
        String capture = resolve(fixture, "compile", "source-path");
        assertTrue(capture.contains(fixture.app().resolve("late/sources").toString()), capture);
    }

    @Test
    @EnabledIf("supportsConfigurationCache")
    void reusesConfigurationCacheForDiscoveryCompileAndRuntimeAndInvalidatesChangedConfiguration() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.unsafe.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.unsafe.configuration-cache-problems=fail\n");
        Files.writeString(fixture.root().resolve("build.gradle"), """
                rootProject.file('evaluations.txt') << 'evaluated\\n'
                """, StandardOpenOption.APPEND);
        // Each request has its own cache entry. Replay must execute capture again,
        // but must not evaluate the build, even when dependencies have changed.
        for (String scope : List.of("discovery", "compile", "runtime")) {
            String first = scope.equals("discovery")
                    ? run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-dirs")
                    : resolve(fixture, scope, "class-path,source-path,release,main-class");
            String second = scope.equals("discovery")
                    ? run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-dirs")
                    : resolve(fixture, scope, "main-class,release,source-path,class-path");
            assertEquals(first, second);
        }
        Path evaluations = fixture.root().resolve("evaluations.txt");
        assertEquals(3, Files.readAllLines(evaluations).size(), Files.readString(evaluations));
        Path compiled = fixture.app().resolve("build/classes/java/main/app/Main.class");
        Files.delete(compiled);
        resolve(fixture, "runtime", "class-path,source-path,release,main-class");
        assertTrue(Files.isRegularFile(compiled));
        assertEquals(3, Files.readAllLines(evaluations).size());
        Files.writeString(fixture.app().resolve("build.gradle"), "\nsourceSets.main.java.srcDirs += ['changed/sources']\n", StandardOpenOption.APPEND);
        String changed = resolve(fixture, "compile", "class-path,source-path,release,main-class");
        assertTrue(changed.contains(fixture.app().resolve("changed/sources").toString()), changed);
        assertEquals(4, Files.readAllLines(evaluations).size());
    }

    @Test
    void configurationOnDemandEvaluatesOnlyTheSelectedProjectAndRequiredDependencies() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configureondemand=true\n");
        Files.writeString(fixture.root().resolve("plain/build.gradle"), "throw new GradleException('unrelated project was evaluated')\n");
        String capture = resolve(fixture, "compile", "class-path");
        assertTrue(capture.contains("library/" + classesDirectory()), capture);
        String projects = run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-dirs");
        assertTrue(projects.contains(fixture.root().resolve("plain").toString()), projects);
    }

    @Test
    @EnabledIf("supportsConfigurationCache")
    void argumentProvidersRemainLazyAndRetainProducerDependenciesOnCacheReplay() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.unsafe.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.unsafe.configuration-cache-problems=fail\n");
        Files.writeString(fixture.app().resolve("build.gradle"), """
                abstract class GenerateArguments extends DefaultTask {
                    @OutputFile abstract org.gradle.api.file.RegularFileProperty getDestination()
                    @TaskAction void generate() {
                        def file = destination.get().asFile
                        file.parentFile.mkdirs()
                        file.text = 'java.base/java.lang=ALL-UNNAMED'
                    }
                }
                class ArgumentsFromFile implements CommandLineArgumentProvider {
                    @InputFile @PathSensitive(PathSensitivity.NONE)
                    org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile> input
                    Iterable<String> asArguments() { return ['--add-exports', input.get().asFile.text] }
                }
                def producer = tasks.register('generateArguments', GenerateArguments) {
                    destination = layout.buildDirectory.file('generated-arguments.txt')
                }
                tasks.named('compileJava').configure {
                    options.compilerArgumentProviders.add(new ArgumentsFromFile(input: producer.flatMap { it.destination }))
                }
                """, StandardOpenOption.APPEND);
        String first = resolve(fixture, "compile", "add-exports");
        assertEquals("\"--add-exports\"\n\"java.base/java.lang=ALL-UNNAMED\"\n", first);
        assertFalse(Files.exists(fixture.app().resolve("build/classes/java/main/app/Main.class")));
        Path generated = fixture.app().resolve("build/generated-arguments.txt");
        Files.delete(generated);
        assertEquals(first, resolve(fixture, "compile", "add-exports"));
        assertTrue(Files.isRegularFile(generated));
    }

    private Fixture fixture(boolean modular) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("build with spaces")).toRealPath();
        Path app = Files.createDirectories(root.resolve("layout/application"));
        Path lib = Files.createDirectories(root.resolve("library"));
        Files.createDirectories(root.resolve("plain"));
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh\nexport JAVA_HOME="
                + shellQuote(gradle.javaHome().toString())
                + "\nexec sh "
                + shellQuote(gradle.executable().toString())
                + " \"$@\"\n");
        Files.writeString(root.resolve("settings.gradle"),
                """
                rootProject.name = 'fixture'
                include 'app', 'library', 'plain'
                project(':app').projectDir = file('layout/application')
                """);
        Files.writeString(root.resolve("build.gradle"),
                """
                allprojects {
                    configurations.all {
                        incoming.beforeResolve {
                            if (rootProject.file('prevent-resolution').exists()) {
                                throw new GradleException('Unexpected dependency resolution')
                            }
                        }
                    }
                }
                """);
        Files.writeString(lib.resolve("build.gradle"), "plugins { id 'java-library' }\n");
        Path librarySources = Files.createDirectories(lib.resolve("src/main/java/lib"));
        Files.writeString(librarySources.resolve("Library.java"), "package lib; public class Library { public static String hello() { return \"hello\"; } }\n");
        Files.writeString(app.resolve("build.gradle"),
                """
                plugins { id 'application' }
                sourceSets.main.java.srcDirs = ['sources/java']
                dependencies {
                    implementation project(':library')
                    implementation files(rootProject.file('shared.jar'))
                    implementation files(rootProject.file('plain.jar'))
                    compileOnly files(rootProject.file('compile only.jar'))
                    runtimeOnly files(rootProject.file('runtime only.jar'))
                }
                """
                        + (atLeast(6, 4) ? "application { mainClass = 'app.Main' }\n" : "mainClassName = 'app.Main'\n")
                        + (supportsRelease() ? "tasks.withType(JavaCompile).configureEach { options.release = " + javaRelease() + " }\n"
                                : "sourceCompatibility = targetCompatibility = '" + javaRelease() + "'\n")
                        + (modular ? "java.modularity.inferModulePath = true\napplication { mainModule = 'app.mod' }\n" : ""));
        Path sources = Files.createDirectories(app.resolve("sources/java/app"));
        Files.writeString(sources.resolve("Main.java"), "package app; public class Main { public static void main(String[] args) { System.out.println(lib.Library.hello()); } }\n");
        if (modular) {
            Files.writeString(librarySources.getParent()
                    .resolve("module-info.java"),
                    "module lib.mod { exports lib; }\n");
            Files.writeString(sources.getParent()
                    .resolve("module-info.java"),
                    "module app.mod { requires lib.mod; }\n");
        }
        jar(root.resolve("shared.jar"), "shared.mod");
        jar(root.resolve("compile only.jar"), "compile.only");
        jar(root.resolve("runtime only.jar"), "runtime.only");
        jar(root.resolve("plain.jar"), null);
        return new Fixture(root, app);
    }

    private static List<GradleDistribution> gradleVersions() {
        return List.of(new GradleDistribution("3.5.1", 8), new GradleDistribution("4.10.3", 8),
                new GradleDistribution("5.6.4", 11), new GradleDistribution("6.6.1", 11),
                new GradleDistribution("6.9.4", 11), new GradleDistribution("7.6.6", 17),
                new GradleDistribution("8.5", 17), new GradleDistribution("9.6.1", 17),
                new GradleDistribution("9.7.1", 17));
    }

    private static boolean availableJdks() {
        return List.of(8, 11, 17).stream().allMatch(version -> System.getenv("JIG_TEST_GRADLE_JAVA_" + version + "_HOME") != null);
    }

    private boolean atLeast(int major, int minor) {
        return gradle.atLeast(major, minor);
    }

    private String classesDirectory() {
        return atLeast(4, 0) ? "build/classes/java/main" : "build/classes/main";
    }

    private int javaRelease() {
        return gradle.javaVersion();
    }

    private boolean supportsRelease() {
        return atLeast(6, 6);
    }

    private boolean supportsModules() {
        return atLeast(6, 6) && javaRelease() >= 9;
    }

    private boolean supportsArgumentProviders() {
        return atLeast(4, 6);
    }

    private boolean supportsLazyTasks() {
        return atLeast(4, 9);
    }

    private boolean supportsConfigurationCache() {
        return atLeast(6, 6);
    }

    private static void jar(Path path, String module) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        if (module != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", module);
        }
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.finish();
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String execute(String tool, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", tool)
                .toString());
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static String resolve(Fixture fixture, String scope, String options) {
        return run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-dir",
                fixture.app().toString(),
                "--scope",
                scope,
                "-r",
                options);
    }

    private static String run(String... arguments) {
        var output = new StringWriter();
        var error = new StringWriter();
        int code = new Jig().run(new PrintWriter(output, true), new PrintWriter(error, true), arguments);
        assertEquals(0, code, error.toString());
        return output.toString();
    }

    private record Fixture(Path root, Path app) {}
}
