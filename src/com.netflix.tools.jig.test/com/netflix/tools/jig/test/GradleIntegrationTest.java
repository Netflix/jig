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
import java.util.jar.JarEntry;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
                fixture.root().toString(), "--list-project-paths");
        assertEquals(List.of(":", ":app", ":library", ":plain"), projects.lines().toList());
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
        assertEquals("", run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":plain", "--list-source-sets"));
    }

    @Test
    void resolvesClasspathSpecificPathsAndMaterializesDependencyOutputs() throws Exception {
        var fixture = fixture(false);
        String compile = resolve(fixture, "compile", "class-path,source-path");
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
    void binaryPathsDelegateSelectedCompilationButSourcePathsLeaveItToTheCaller() throws Exception {
        var fixture = fixture(false);
        if (supportsConfigurationCache()) {
            Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.unsafe.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.unsafe.configuration-cache-problems=fail\n");
        }
        String sources = resolve(fixture, "runtime", "class-path,source-path");
        assertTrue(Files.isRegularFile(fixture.root().resolve("library/" + classesDirectory() + "/lib/Library.class")));
        assertFalse(Files.exists(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
        assertFalse(sources.contains(fixture.app().resolve(classesDirectory()).toString()), sources);
        // Module sources are not an equivalent source view for an unnamed set.
        String binaries = resolve(fixture, "compile", "class-path,module-source-path");
        assertTrue(Files.isRegularFile(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
        assertTrue(binaries.contains(fixture.app().resolve(classesDirectory()).toString()), binaries);
        Files.delete(fixture.app().resolve(classesDirectory() + "/app/Main.class"));
        assertEquals(sources, resolve(fixture, "runtime", "class-path,source-path"));
        assertFalse(Files.exists(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
        assertFalse(Files.exists(fixture.app().resolve("build/test-results")));
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
                "--project-path",
                ":app",
                "--source-set", "main", "--classpath",
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
        assertFalse(Files.exists(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
        Path compiled = temporaryDirectory.resolve("compiled");
        execute("javac", "@" + options, "-d", compiled.toString());
        assertTrue(Files.isRegularFile(compiled.resolve("app.mod/module-info.class")));
        Path runtimeOptions = temporaryDirectory.resolve("runtime.args");
        run(
                "gradle",
                "--root-project-dir",
                fixture.root().toString(),
                "--project-path",
                ":app",
                "--source-set", "main", "--classpath",
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
    void moduleBinaryPathsRequireTheCorrespondingModuleSourceOption() throws Exception {
        var fixture = fixture(true);
        String binaries = resolve(fixture, "compile", "module-path,source-path");
        assertTrue(Files.isRegularFile(fixture.app().resolve(classesDirectory() + "/module-info.class")));
        assertTrue(binaries.contains(fixture.app().resolve(classesDirectory()).toString()), binaries);
    }

    @Test
    @EnabledIf("supportsModules")
    void generalResolutionDelegatesModuleDetectionForCompileAndRuntimeAcrossCacheReplay() throws Exception {
        var fixture = fixture(true);
        Path explicit = fixture.root().resolve("explicit.jar");
        Path multiRelease = fixture.root().resolve("multi-release.jar");
        Path notMultiRelease = fixture.root().resolve("not-multi-release.jar");
        Path exploded = moduleClasses("exploded.mod");
        moduleJar(explicit, Files.readAllBytes(moduleClasses("explicit.mod").resolve("module-info.class")), false, false);
        moduleJar(multiRelease, Files.readAllBytes(moduleClasses("multirelease.mod").resolve("module-info.class")), true, true);
        moduleJar(notMultiRelease, Files.readAllBytes(moduleClasses("unmarked.mod").resolve("module-info.class")), true, false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.unsafe.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.unsafe.configuration-cache-problems=fail\n");
        Files.writeString(fixture.root().resolve("build.gradle"), "\nrootProject.file('evaluations.txt') << 'evaluated\\n'\n", StandardOpenOption.APPEND);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                dependencies {
                    implementation files(rootProject.file('explicit.jar'), rootProject.file('multi-release.jar'),
                        rootProject.file('not-multi-release.jar'), rootProject.file('missing.jar'), '%s')
                }
                """.formatted(exploded), StandardOpenOption.APPEND);
        for (String classpath : List.of("compile", "runtime")) {
            String captured = resolve(fixture, classpath, "class-path,module-path");
            assertEquals(captured, resolve(fixture, classpath, "class-path,module-path"));
            List<String> lines = captured.lines().toList();
            String plain = lines.get(lines.indexOf("\"--class-path\"") + 1);
            String modular = lines.get(lines.indexOf("\"--module-path\"") + 1);
            assertEquals("\"" + fixture.root().resolve("plain.jar") + System.getProperty("path.separator") + notMultiRelease + "\"", plain);
            for (Path module : List.of(explicit, multiRelease, exploded, fixture.root().resolve("shared.jar"))) {
                assertTrue(modular.contains(module.toString()), captured);
            }
            assertFalse(modular.contains(notMultiRelease.toString()), captured);
            assertFalse(captured.contains("missing.jar"), captured);
        }
        assertEquals(2, Files.readAllLines(fixture.root().resolve("evaluations.txt")).size());
        for (String classpath : List.of("compile", "runtime")) {
            // Runtime resolution builds the selected output, so leave compilation
            // modular while disabling inference on the run task itself.
            Files.writeString(fixture.app().resolve("build.gradle"), "\ntasks.compileJava.modularity.inferModulePath = "
                    + classpath.equals("runtime") + "\ntasks.run.modularity.inferModulePath = false\n", StandardOpenOption.APPEND);
            String captured = resolve(fixture, classpath, "class-path,module-path" + (classpath.equals("compile") ? ",module-source-path" : ""));
            assertFalse(captured.contains("\"--module-path\""), captured);
            for (Path binary : List.of(explicit, multiRelease, notMultiRelease, exploded)) {
                assertTrue(captured.contains(binary.toString()), captured);
            }
        }
    }

    @Test
    @EnabledIf("supportsModules")
    void respectsDisabledModulePathInference() throws Exception {
        var fixture = fixture(true);
        Files.writeString(fixture.app().resolve("build.gradle"), "\njava.modularity.inferModulePath = false\n",
                StandardOpenOption.APPEND);
        String capture = resolve(fixture, "compile", "module-path,class-path,module,module-source-path");
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
    void reusesConfigurationCacheForDiscoveryAndSourceSetClasspathsAndInvalidatesChangedConfiguration() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.unsafe.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.unsafe.configuration-cache-problems=fail\n");
        Files.writeString(fixture.root().resolve("build.gradle"), """
                rootProject.file('evaluations.txt') << 'evaluated\\n'
                """, StandardOpenOption.APPEND);
        // Repeated requests must return a fresh response without evaluating the
        // build again, even when dependency outputs have changed.
        for (String[] discovery : List.of(
                new String[] {"gradle", "--root-project-dir", fixture.root().toString(), "--list-project-paths"},
                new String[] {"gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "--list-source-sets"})) {
            assertEquals(run(discovery), run(discovery));
        }
        for (String sourceSet : List.of("main", "test")) {
            for (String classpath : List.of("compile", "runtime")) {
                String first = resolve(fixture, sourceSet, classpath, "class-path,source-path,release,main-class");
                String second = resolve(fixture, sourceSet, classpath, "main-class,release,source-path,class-path");
                assertEquals(first, second);
                assertEquals(sourceSet.equals("main"), first.contains("--main-class"), first);
            }
        }
        Path evaluations = fixture.root().resolve("evaluations.txt");
        assertEquals(6, Files.readAllLines(evaluations).size(), Files.readString(evaluations));
        Path compiled = fixture.app().resolve("build/classes/java/main/app/Main.class");
        Files.delete(compiled);
        // Repeat the last request to check producer freshness on cache replay.
        resolve(fixture, "test", "runtime", "class-path,source-path,release,main-class");
        assertTrue(Files.isRegularFile(compiled));
        assertEquals(6, Files.readAllLines(evaluations).size());
        Files.writeString(fixture.app().resolve("build.gradle"), "\nsourceSets.main.java.srcDirs += ['changed/sources']\n", StandardOpenOption.APPEND);
        String changed = resolve(fixture, "compile", "class-path,source-path,release,main-class");
        assertTrue(changed.contains(fixture.app().resolve("changed/sources").toString()), changed);
        assertEquals(7, Files.readAllLines(evaluations).size());
    }

    @Test
    void configurationOnDemandEvaluatesOnlyTheSelectedProjectAndRequiredDependencies() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configureondemand=true\n");
        Files.writeString(fixture.root().resolve("plain/build.gradle"), "throw new GradleException('unrelated project was evaluated')\n");
        String capture = resolve(fixture, "compile", "class-path");
        assertTrue(capture.contains("library/" + classesDirectory()), capture);
        String projects = run("gradle", "--root-project-dir", fixture.root().toString(), "--list-project-paths");
        assertTrue(projects.contains(":plain"), projects);
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

    @Test
    @EnabledIf("supportsLazyTasks")
    void discoversSourceSetsWithoutRealizingCompilationTasks() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                sourceSets { integrationTest {} }
                tasks.named('compileJava').configure { throw new GradleException('main compilation realized') }
                tasks.named('compileTestJava').configure { throw new GradleException('test compilation realized') }
                tasks.named('compileIntegrationTestJava').configure { throw new GradleException('custom compilation realized') }
                """, StandardOpenOption.APPEND);
        String sourceSets = run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "--list-source-sets");
        assertEquals("integrationTest\nmain\ntest\n", sourceSets);
        assertFalse(Files.exists(fixture.app().resolve("build")));
    }

    @Test
    void resolvesTestAndCustomSourceSetsWithoutLeakingMainApplicationMetadata() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                sourceSets {
                    test.java.srcDirs = ['tests/java']
                    integrationTest {
                        java.srcDirs = ['integration/java']
                        compileClasspath += sourceSets.main.output + configurations.testCompileClasspath
                        runtimeClasspath += sourceSets.main.output + configurations.testRuntimeClasspath
                    }
                }
                dependencies {
                    testCompileOnly files(rootProject.file('test compile only.jar'))
                    testRuntimeOnly files(rootProject.file('test runtime only.jar'))
                }
                tasks.compileIntegrationTestJava { options.encoding = 'ISO-8859-1' }
                tasks.run.jvmArgs = ['--add-opens', 'java.base/java.lang=ALL-UNNAMED']
                """, StandardOpenOption.APPEND);
        jar(fixture.root().resolve("test compile only.jar"), null);
        jar(fixture.root().resolve("test runtime only.jar"), null);
        for (String root : List.of("tests/java", "integration/java")) {
            Files.createDirectories(fixture.app().resolve(root));
            Files.writeString(fixture.app().resolve(root).resolve("Case.java"), "public class Case { app.Main main; }\n");
        }
        for (String name : List.of("test", "integrationTest")) {
            String compile = resolve(fixture, name, "compile", "class-path,source-path,encoding");
            String source = name.equals("test") ? "tests/java" : "integration/java";
            String output = name.equals("test") ? "test" : "integrationTest";
            assertTrue(compile.contains(fixture.app().resolve(source).toString()), compile);
            assertTrue(compile.contains("test compile only.jar"), compile);
            assertFalse(compile.contains("test runtime only.jar"), compile);
            assertTrue(Files.isRegularFile(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
            Path selectedOutput = fixture.app().resolve(classesDirectory().replace("main", output) + "/Case.class");
            assertFalse(Files.exists(selectedOutput));
            if (name.equals("integrationTest")) {
                assertTrue(compile.contains("ISO-8859-1"), compile);
            }
            String runtime = resolve(fixture, name, "runtime", "class-path,main-class,add-opens");
            assertTrue(runtime.contains("test runtime only.jar"), runtime);
            assertFalse(runtime.contains("test compile only.jar"), runtime);
            assertFalse(runtime.contains("--main-class"), runtime);
            assertFalse(runtime.contains("--add-opens"), runtime);
            assertTrue(Files.isRegularFile(selectedOutput));
        }
    }

    @Test
    @EnabledIf("supportsConfigurationCache")
    void generatedSourceProducersRemainLazyAndRunOnConfigurationCacheReplay() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.unsafe.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.unsafe.configuration-cache-problems=fail\n");
        Files.writeString(fixture.app().resolve("build.gradle"), """
                abstract class GenerateSources extends DefaultTask {
                    @OutputDirectory abstract org.gradle.api.file.DirectoryProperty getDestination()
                    @TaskAction void generate() {
                        def file = new File(destination.get().asFile, 'Generated.java')
                        file.parentFile.mkdirs()
                        file.text = 'public class Generated {}'
                    }
                }
                def generator = tasks.register('generateSources', GenerateSources) {
                    destination = layout.buildDirectory.dir('generated/custom')
                }
                sourceSets.test.java.srcDir(generator.flatMap { it.destination })
                tasks.named('compileTestJava').configure { throw new GradleException('test compiler was realized') }
                """, StandardOpenOption.APPEND);
        run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "--list-source-sets");
        Path generated = fixture.app().resolve("build/generated/custom/Generated.java");
        assertFalse(Files.exists(generated));
        String first = resolve(fixture, "test", "compile", "source-path");
        assertTrue(first.contains(generated.getParent().toString()), first);
        assertTrue(Files.isRegularFile(generated));
        Files.delete(generated);
        assertEquals(first, resolve(fixture, "test", "compile", "source-path"));
        assertTrue(Files.isRegularFile(generated));
        assertFalse(Files.exists(fixture.app().resolve("build/classes/java/test")));
    }

    @Test
    void classpathResolutionUsesTheSourceSetRatherThanTheCompilationTaskOverride() throws Exception {
        var fixture = fixture(false);
        jar(fixture.root().resolve("source set.jar"), null);
        jar(fixture.root().resolve("compile task.jar"), null);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                sourceSets.main.compileClasspath = files(rootProject.file('source set.jar'))
                tasks.compileJava.classpath = files(rootProject.file('compile task.jar'))
                """, StandardOpenOption.APPEND);
        String capture = resolve(fixture, "compile", "class-path,source-path");
        assertTrue(capture.contains("source set.jar"), capture);
        assertFalse(capture.contains("compile task.jar"), capture);
    }

    @Test
    void rejectsUnknownProjectPathsAndSourceSetsWithoutReturningArguments() throws Exception {
        var fixture = fixture(false);
        for (String[] selection : List.of(
                new String[] {"--project-path", ":missing", "--list-source-sets"},
                new String[] {"--project-path", ":app", "--source-set", "missing", "--classpath", "compile", "-r", "source-path"})) {
            var arguments = new ArrayList<>(List.of("gradle", "--root-project-dir", fixture.root().toString()));
            arguments.addAll(List.of(selection));
            var output = new StringWriter();
            var error = new StringWriter();
            int code = new Jig().run(new PrintWriter(output, true), new PrintWriter(error, true), arguments.toArray(String[]::new));
            assertEquals(1, code, error.toString());
            assertEquals("", output.toString());
            assertTrue(error.toString().contains(selection.length == 3 ? "Unknown project path" : "Unknown source set"), error.toString());
        }
    }

    @Test
    void compilerOptionsMatchGradleCompilationWithoutEmittingSourceFilesOrLauncherOptions() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                tasks.compileJava {
                    options.debug = false
                    options.deprecation = true
                    options.warnings = false
                    options.encoding = 'UTF-8'
                    options.compilerArgs += ['-parameters', '-Xlint:deprecation', '-Werror', '-g:lines,vars']
                    options.forkOptions.jvmArgs = ['-Dcompiler.launcher.only=true']
                }
                """, StandardOpenOption.APPEND);
        String captured = compilerOptions(fixture, "main");
        for (String option : List.of("-deprecation", "-nowarn", "-encoding", "-g:none", "-parameters", "-Werror", "-Xlint:deprecation", "-g:lines,vars")) {
            assertTrue(captured.contains('"' + option + '"'), captured);
        }
        assertFalse(captured.contains("Main.java"), captured);
        assertFalse(captured.contains("compiler.launcher.only"), captured);
        Path gradleClass = fixture.app().resolve(classesDirectory() + "/app/Main.class");
        assertFalse(Files.exists(gradleClass));
        Path options = temporaryDirectory.resolve("compiler.args");
        run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app", "--source-set", "main",
                "--resolve-compiler-options", "-w", options.toString());
        assertEquals(captured, Files.readString(options));
        Path classes = Files.createDirectories(temporaryDirectory.resolve("manual classes"));
        execute(gradle.javaHome().resolve("bin/javac"), "@" + options, "-d", classes.toString(), fixture.app().resolve("sources/java/app/Main.java").toString());
        execute(Path.of("sh"), fixture.root().resolve("gradlew").toString(), "--project-dir", fixture.root().toString(), "--quiet", ":app:compileJava");
        assertArrayEquals(Files.readAllBytes(classes.resolve("app/Main.class")), Files.readAllBytes(gradleClass));
    }

    @Test
    @EnabledIf("supportsModules")
    void compilerResolutionDelegatesModuleInferenceAndUsesTheCompileTasksModuleVersion() throws Exception {
        var fixture = fixture(true);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                version = 'project-version'
                tasks.compileJava.options.javaModuleVersion = '2.0'
                """, StandardOpenOption.APPEND);
        String captured = compilerOptions(fixture, "main");
        assertTrue(captured.contains("\"--module-version\"\n\"2.0\""), captured);
        assertTrue(captured.contains("\"--module-path\""), captured);
        assertTrue(captured.contains("plain.jar"), captured);
        assertTrue(captured.contains("shared.jar"), captured);
        Path options = temporaryDirectory.resolve("module.args");
        Files.writeString(options, captured);
        Path classes = temporaryDirectory.resolve("modules");
        execute(gradle.javaHome().resolve("bin/javac"), "@" + options, "-d", classes.toString(),
                fixture.app().resolve("sources/java/module-info.java").toString(), fixture.app().resolve("sources/java/app/Main.java").toString());
        assertTrue(Files.isRegularFile(classes.resolve("module-info.class")));
        Files.writeString(fixture.app().resolve("build.gradle"), "\ntasks.compileJava.modularity.inferModulePath = false\n", StandardOpenOption.APPEND);
        assertFalse(compilerOptions(fixture, "main").contains("\"--module-path\""));
    }

    @Test
    @EnabledIf("supportsModules")
    void compilerResolutionUsesGradlesOwnModuleSourcePathDefaults() throws Exception {
        var fixture = fixture(true);
        Path configured = Files.createDirectories(fixture.app().resolve("configured source path"));
        Files.writeString(fixture.app().resolve("build.gradle"), "\ntasks.compileJava.options.sourcepath = files('configured source path')\n",
                StandardOpenOption.APPEND);
        String captured = compilerOptions(fixture, "main");
        // Gradle 6.x replaces an explicit source path for modular compilation;
        // the tested 7.x+ versions retain it. Use the build's own behavior.
        Path expected = atLeast(7, 0) ? configured : fixture.app().resolve("sources/java");
        assertTrue(captured.contains("\"-sourcepath\"\n\"" + expected + "\""), captured);
    }

    @Test
    void compilerResolutionRunsExplicitProducerDependenciesBeforeCapturingOptions() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                task generateCompilerArguments {
                    def destination = file('build/compiler-arguments.txt')
                    outputs.file(destination)
                    doLast {
                        destination.parentFile.mkdirs()
                        destination.text = 'generated argument'
                    }
                }
                tasks.compileJava.dependsOn(generateCompilerArguments)
                """ + (atLeast(5, 0) ? """
                class CompilerArgumentsFromFile implements CommandLineArgumentProvider {
                    @InputFile File input
                    Iterable<String> asArguments() { return ['-Akey=' + input.text] }
                }
                tasks.compileJava.options.compilerArgumentProviders.add(new CompilerArgumentsFromFile(input: file('build/compiler-arguments.txt')))
                """ : ""), StandardOpenOption.APPEND);
        // Gradle 4.x itself evaluates compiler providers while discovering task
        // dependencies. Still check explicit producer execution on older versions.
        String captured = compilerOptions(fixture, "main");
        assertEquals("generated argument", Files.readString(fixture.app().resolve("build/compiler-arguments.txt")));
        if (atLeast(5, 0)) {
            assertTrue(captured.contains("\"-Akey=generated argument\""), captured);
        }
        assertFalse(Files.exists(fixture.app().resolve(classesDirectory() + "/app/Main.class")));
    }

    @Test
    void compilerResolutionPreservesOutputsAndHistoryWithoutRunningActionsOrFinalizers() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                if (project.hasProperty('jig.gradle.compiler')) {
                    task compilerFinalizer {
                        doLast { throw new GradleException('compiler finalizer ran') }
                    }
                    tasks.compileJava {
                        enabled = false
                        onlyIf { throw new GradleException('original compiler predicate ran') }
                        doFirst { throw new GradleException('compiler action ran') }
                        doLast { throw new GradleException('compiler action ran') }
                        finalizedBy(compilerFinalizer)
                    }
                }
                """, StandardOpenOption.APPEND);
        execute(Path.of("sh"), fixture.root().resolve("gradlew").toString(), "--project-dir", fixture.root().toString(), "--quiet", ":app:compileJava");
        Path compiled = fixture.app().resolve(classesDirectory() + "/app/Main.class");
        byte[] original = Files.readAllBytes(compiled);
        var modified = Files.getLastModifiedTime(compiled);
        assertTrue(compilerOptions(fixture, "main").contains("\"-d\""));
        assertArrayEquals(original, Files.readAllBytes(compiled));
        assertEquals(modified, Files.getLastModifiedTime(compiled));
        String rebuild = execute(Path.of("sh"), fixture.root().resolve("gradlew").toString(), "--project-dir", fixture.root().toString(), "--console", "plain", ":app:compileJava");
        assertTrue(rebuild.contains(":app:compileJava UP-TO-DATE"), rebuild);
    }

    @Test
    @EnabledIf("supportsConfigurationCache")
    void compilerResolutionKeepsGeneratorsAndArgumentProvidersLazyAcrossCacheReplay() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.root().resolve("gradle.properties"), "org.gradle.configuration-cache=true\norg.gradle.unsafe.configuration-cache=true\norg.gradle.configuration-cache.problems=fail\norg.gradle.unsafe.configuration-cache-problems=fail\n");
        Files.writeString(fixture.root().resolve("build.gradle"), "\nrootProject.file('evaluations.txt') << 'evaluated\\n'\n", StandardOpenOption.APPEND);
        Files.writeString(fixture.app().resolve("build.gradle"), """
                abstract class GenerateCompilerInput extends DefaultTask {
                    @OutputDirectory abstract org.gradle.api.file.DirectoryProperty getDestination()
                    @TaskAction void generate() {
                        def dir = destination.get().asFile
                        dir.mkdirs()
                        new File(dir, 'Generated.java').text = 'public class Generated {}'
                    }
                }
                abstract class GenerateCompilerArguments extends DefaultTask {
                    @OutputFile abstract org.gradle.api.file.RegularFileProperty getDestination()
                    @TaskAction void generate() {
                        def file = destination.get().asFile
                        file.parentFile.mkdirs()
                        file.text = 'value with spaces'
                    }
                }
                class CompilerArgumentsFromFile implements CommandLineArgumentProvider {
                    @InputFile @PathSensitive(PathSensitivity.NONE)
                    org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile> input
                    @Internal int evaluations
                    Iterable<String> asArguments() {
                        if (++evaluations != 1) { throw new GradleException('compiler provider evaluated more than once') }
                        return ['-Akey=' + input.get().asFile.text]
                    }
                }
                def generator = tasks.register('generateCompilerInput', GenerateCompilerInput) {
                    destination = layout.buildDirectory.dir('compiler-input')
                }
                def arguments = tasks.register('generateCompilerArguments', GenerateCompilerArguments) {
                    destination = layout.buildDirectory.file('compiler-arguments.txt')
                }
                sourceSets.test.java.srcDir(generator.flatMap { it.destination })
                tasks.named('compileTestJava').configure {
                    options.compilerArgumentProviders.add(new CompilerArgumentsFromFile(input: arguments.flatMap { it.destination }))
                }
                """, StandardOpenOption.APPEND);
        Path generated = fixture.app().resolve("build/compiler-input");
        Path arguments = fixture.app().resolve("build/compiler-arguments.txt");
        assertFalse(Files.exists(generated));
        assertFalse(Files.exists(arguments));
        String first = compilerOptions(fixture, "test");
        assertTrue(first.contains("\"-Akey=value with spaces\""), first);
        assertFalse(Files.exists(fixture.app().resolve("build/classes/java/test")));
        Files.delete(generated.resolve("Generated.java"));
        Files.delete(arguments);
        assertEquals(first, compilerOptions(fixture, "test"));
        assertTrue(Files.isRegularFile(generated.resolve("Generated.java")));
        assertTrue(Files.isRegularFile(arguments));
        assertEquals(1, Files.readAllLines(fixture.root().resolve("evaluations.txt")).size());
        Files.writeString(fixture.app().resolve("build.gradle"), "\ntasks.compileTestJava.options.debug = false\n", StandardOpenOption.APPEND);
        assertTrue(compilerOptions(fixture, "test").contains("\"-g:none\""));
        assertEquals(2, Files.readAllLines(fixture.root().resolve("evaluations.txt")).size());
    }

    @Test
    void compilerResolutionHandlesEmptyCustomSourceSets() throws Exception {
        var fixture = fixture(false);
        Files.writeString(fixture.app().resolve("build.gradle"), "\nsourceSets { empty {} }\ntasks.compileEmptyJava.options.debug = false\n", StandardOpenOption.APPEND);
        String captured = compilerOptions(fixture, "empty");
        assertTrue(captured.contains("\"-g:none\""), captured);
        // Gradle's spec preparation may create its temporary directory, but must
        // not produce compilation outputs, even when there are no source files.
        assertFalse(Files.exists(fixture.app().resolve(classesDirectory())));
    }

    @Test
    void compilerOptionsPreserveProcessingGeneratedSourcesAndNativeHeaders() throws Exception {
        var fixture = fixture(false);
        Path processor = processorJar();
        Path generated = Files.createDirectories(temporaryDirectory.resolve("generated sources"));
        Path headers = Files.createDirectories(temporaryDirectory.resolve("native headers"));
        Files.writeString(fixture.app().resolve("build.gradle"), """
                tasks.compileJava {
                    options.annotationProcessorPath = files('%s')
                    options.compilerArgs += ['-processor', 'processor.Generator', '-Akey=custom value', '-s', '%s', '-h', '%s']
                }
                """.formatted(processor, generated, headers), StandardOpenOption.APPEND);
        Path main = fixture.app().resolve("sources/java/app/Main.java");
        Files.writeString(main, "package app; public class Main { public static String value = generated.Generated.VALUE; public native int number(); }\n");
        String captured = compilerOptions(fixture, "main");
        assertTrue(captured.contains("\"-processor\"\n\"processor.Generator\""), captured);
        assertTrue(captured.contains("\"-Akey=custom value\""), captured);
        assertFalse(captured.contains("\"-proc:none\""), captured);
        assertFalse(Files.exists(generated.resolve("generated/Generated.java")));
        Path options = temporaryDirectory.resolve("processing.args");
        Files.writeString(options, captured);
        Path classes = Files.createDirectories(temporaryDirectory.resolve("processing classes"));
        execute(gradle.javaHome().resolve("bin/javac"), "@" + options, "-d", classes.toString(), main.toString());
        String generatedSource = Files.readString(generated.resolve("generated/Generated.java"));
        String generatedHeader = Files.readString(headers.resolve("app_Main.h"));
        execute(Path.of("sh"), fixture.root().resolve("gradlew").toString(), "--project-dir", fixture.root().toString(), "--quiet", ":app:compileJava");
        assertEquals(generatedSource, Files.readString(generated.resolve("generated/Generated.java")));
        assertEquals(generatedHeader, Files.readString(headers.resolve("app_Main.h")));
        for (String name : List.of("app/Main.class", "generated/Generated.class")) {
            assertArrayEquals(Files.readAllBytes(classes.resolve(name)),
                    Files.readAllBytes(fixture.app().resolve(classesDirectory()).resolve(name)), name);
        }
    }

    @Test
    @EnabledIf("supportsConfigurationCache")
    void compilerResolutionUsesTaskOverridesWithoutRealizingUnusedSourceSetProducers() throws Exception {
        var fixture = fixture(false);
        Path alternative = Files.createDirectories(fixture.app().resolve("alternative"));
        Files.writeString(alternative.resolve("Alternative.java"), "public class Alternative {}\n");
        Files.writeString(fixture.app().resolve("build.gradle"), """
                abstract class UnusedSourceProducer extends DefaultTask {
                    @OutputDirectory abstract org.gradle.api.file.DirectoryProperty getDestination()
                }
                def unused = tasks.register('unusedSourceProducer', UnusedSourceProducer) {
                    throw new GradleException('unused source-set producer was realized')
                }
                sourceSets.main.java.srcDir(unused.flatMap { it.destination })
                tasks.compileJava {
                    setSource(fileTree('alternative'))
                    classpath = files(rootProject.file('plain.jar'))
                }
                """, StandardOpenOption.APPEND);
        String captured = compilerOptions(fixture, "main");
        assertTrue(captured.contains("plain.jar"), captured);
        assertFalse(captured.contains("shared.jar"), captured);
        assertFalse(captured.contains("Alternative.java"), captured);
    }

    private Path moduleClasses(String name) throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve(name)).resolve("module-info.java");
        Files.writeString(source, "module " + name + " {}\n");
        Path classes = source.getParent().resolve("classes");
        execute(gradle.javaHome().resolve("bin/javac"), "-d", classes.toString(), source.toString());
        return classes;
    }

    private static void moduleJar(Path path, byte[] descriptor, boolean versioned, boolean multiRelease) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().put(Name.MULTI_RELEASE, "true");
        }
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry(versioned ? "META-INF/versions/9/module-info.class" : "module-info.class"));
            output.write(descriptor);
            output.closeEntry();
        }
    }

    private Path processorJar() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("processor source"));
        Path source = sources.resolve("Generator.java");
        Files.writeString(source, """
                package processor;
                import java.io.IOException;
                import java.util.Set;
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                @SupportedAnnotationTypes("*")
                @SupportedOptions("key")
                public class Generator extends AbstractProcessor {
                    private boolean generated;
                    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (!generated && !round.processingOver()) {
                            generated = true;
                            try (java.io.Writer writer = processingEnv.getFiler().createSourceFile("generated.Generated").openWriter()) {
                                writer.write("package generated; public class Generated { public static final String VALUE = \\\""
                                    + processingEnv.getOptions().get("key") + "\\\"; }");
                            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                        }
                        return false;
                    }
                }
                """);
        Path classes = Files.createDirectories(temporaryDirectory.resolve("processor classes"));
        execute(gradle.javaHome().resolve("bin/javac"), "-d", classes.toString(), source.toString());
        Path jar = temporaryDirectory.resolve("processor.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }

    private static String compilerOptions(Fixture fixture, String sourceSet) {
        return run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", sourceSet, "--resolve-compiler-options");
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
        return execute(Path.of(System.getProperty("java.home"), "bin", tool), arguments);
    }

    private static String execute(Path tool, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(tool.toString());
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static String resolve(Fixture fixture, String classpath, String options) {
        return resolve(fixture, "main", classpath, options);
    }

    private static String resolve(Fixture fixture, String sourceSet, String classpath, String options) {
        return run("gradle", "--root-project-dir", fixture.root().toString(), "--project-path", ":app",
                "--source-set", sourceSet, "--classpath", classpath, "-r", options);
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
