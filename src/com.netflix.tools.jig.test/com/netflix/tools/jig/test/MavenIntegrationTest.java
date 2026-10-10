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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native project discovery across Maven's class- and interface-based lifecycle APIs. */
@EnabledOnOs({OS.LINUX, OS.MAC})
@EnabledIf("availableJdks")
@ParameterizedClass(name = "Maven {0}")
@MethodSource("mavenVersions")
class MavenIntegrationTest {
    @Parameter
    MavenDistribution maven;

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversActiveModulesWithoutExecutingBoundLifecycleGoals() throws Exception {
        Path root = fixture();
        var result = discover(root);
        assertEquals(0, result.status(), result.error());
        assertEquals(List.of("fixture:app", "fixture:extra", "fixture:root"), result.output().lines().toList());
        assertFalse(result.output().contains("[INFO]"), result.output());
        assertFalse(result.output().contains("jig-maven:"), result.output());
        assertNoBuildOutputs();
    }

    @Test
    void standaloneProjectsDoNotIncludeTheirParentOrSiblingModules() throws Exception {
        Path root = fixture();
        Path app = root.resolve("app");
        launcher(app, "--offline\n");
        var independent = discover(app);
        assertEquals(0, independent.status(), independent.error());
        assertEquals(List.of("fixture:app"), independent.output().lines().toList());
        // Parent inheritance does not make the parent a member of this build.
        Files.writeString(app.resolve("pom.xml"), pom("app", """
                <parent>
                  <groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version>
                  <relativePath>../../parent/pom.xml</relativePath>
                </parent>
                """, ""));
        var inherited = discover(app);
        assertEquals(0, inherited.status(), inherited.error());
        assertEquals(independent.output(), inherited.output());
        assertNoBuildOutputs();
    }

    @Test
    void everyRequestReflectsCurrentProfilesAndProjectModels() throws Exception {
        Path root = fixture();
        var initial = discover(root);
        assertEquals(0, initial.status(), initial.error());
        assertTrue(initial.output().contains("fixture:extra"), initial.output());
        Files.delete(root.resolve("extra.enabled"));
        var profileDisabled = discover(root);
        assertEquals(0, profileDisabled.status(), profileDisabled.error());
        assertEquals(List.of("fixture:app", "fixture:root"), profileDisabled.output().lines().toList());
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<module>app</module>", "<module>extra</module>"));
        var changedModel = discover(root);
        assertEquals(0, changedModel.status(), changedModel.error());
        assertEquals(List.of("fixture:extra", "fixture:root"), changedModel.output().lines().toList());
        assertNoBuildOutputs();
    }

    @Test
    @EnabledIf("supportsMavenConfig")
    void readsCurrentMavenConfigWhenSupportedByTheNativeVersion() throws Exception {
        Path root = fixture();
        Files.delete(root.resolve("extra.enabled"));
        Files.writeString(root.resolve(".mvn/maven.config"), "--offline\n-Pextra\n");
        var enabled = discover(root);
        assertEquals(0, enabled.status(), enabled.error());
        assertEquals(List.of("fixture:app", "fixture:extra", "fixture:root"), enabled.output().lines().toList());
        Files.writeString(root.resolve(".mvn/maven.config"), "--offline\n");
        var disabled = discover(root);
        assertEquals(0, disabled.status(), disabled.error());
        assertEquals(List.of("fixture:app", "fixture:root"), disabled.output().lines().toList());
        assertNoBuildOutputs();
    }

    @Test
    void nativeProjectSelectionAcceptsQualifiedAndAbbreviatedIdentifiers() throws Exception {
        Path root = fixture();
        Path pom = root.resolve("app/pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<artifactId>app</artifactId>", "<artifactId>application</artifactId>"));
        for (String selector : List.of("fixture:application", ":application")) {
            var result = discover(root, "--project", selector);
            assertEquals(0, result.status(), selector + ": " + result.error());
            assertEquals(List.of("fixture:application"), result.output().lines().toList());
        }
        assertNoBuildOutputs();
    }

    @Test
    void unknownProjectSelectorsFailWithoutPartialOutput() throws Exception {
        Path root = fixture();
        var result = discover(root, "--project", "fixture:missing");
        assertNotEquals(0, result.status(), result.error());
        assertEquals("", result.output());
        assertTrue(result.error().contains("missing"), result.error());
        assertNoBuildOutputs();
    }

    @Test
    void nativeModelFailuresDoNotPublishPartialProjectIdentifiers() throws Exception {
        Path root = fixture();
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<module>app</module>", "<module>missing</module>"));
        var result = discover(root);
        assertNotEquals(0, result.status(), result.error());
        assertEquals("", result.output());
        assertTrue(result.error().contains("missing"), result.error());
        assertNoBuildOutputs();
    }

    @Test
    void resolvesMavensNativeCompileRuntimeAndTestClasspaths() throws Exception {
        Path root = fixture(true);
        Path app = root.resolve("app");
        Path repository = root.resolve("repository");
        for (String artifact : List.of("compile", "provided", "runtime", "test", "transitive")) {
            artifact(repository, artifact, artifact.equals("compile") ? dependency("transitive", "compile") : "");
        }
        String dependencies = dependency("compile", "compile") + dependency("provided", "provided")
                + dependency("runtime", "runtime") + dependency("test", "test");
        Files.writeString(app.resolve("pom.xml"), pom("app", "", "<dependencies>" + dependencies + "</dependencies>" + customLayout()));
        Path main = Files.createDirectories(app.resolve("sources/java/app")).resolve("Main.java");
        Files.writeString(main, "package app; public class Main {}\n");
        Path test = Files.createDirectories(app.resolve("checks/java/app")).resolve("Check.java");
        Files.writeString(test, "package app; public class Check { Main main; }\n");
        for (String scope : List.of("compile", "runtime", "test")) {
            var result = discover(root, "--project", "fixture:app", "--scope", scope, "-r", "class-path");
            assertEquals(0, result.status(), result.error());
            assertTrue(result.output().startsWith("\"--class-path\"\n"), result.output());
            for (String included : List.of("compile", "transitive")) {
                assertTrue(result.output().contains(repository.resolve("fixture/dependencies/" + included + "/1/" + included + "-1.jar").toString()), result.output());
            }
            assertEquals(!scope.equals("runtime"), result.output().contains("provided-1.jar"), result.output());
            assertEquals(!scope.equals("compile"), result.output().contains("runtime-1.jar"), result.output());
            assertEquals(scope.equals("test"), result.output().contains("test-1.jar"), result.output());
            assertTrue(result.output().contains(app.resolve("out/main").toString()), result.output());
            assertEquals(scope.equals("test"), result.output().contains(app.resolve("out/test").toString()), result.output());
            assertTrue(Files.isRegularFile(app.resolve("out/main/app/Main.class")), scope);
            assertEquals(scope.equals("test"), Files.isRegularFile(app.resolve("out/test/app/Check.class")), scope);
        }
        assertFalse(Files.exists(app.resolve("target/surefire-reports")));
    }

    @Test
    void binaryPathsPrepareReactorOutputsAndSourcesLeaveSelectedCompilationToTheCaller() throws Exception {
        Path root = fixture(true);
        Path app = root.resolve("app");
        Path library = Files.createDirectories(root.resolve("library"));
        Files.writeString(root.resolve("pom.xml"), pom("root", "", "<packaging>pom</packaging><modules><module>library</module><module>app</module></modules>"));
        Files.writeString(library.resolve("pom.xml"), pom("library", "", ""));
        String model = pom("app", "", "<dependencies><dependency><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>" + customLayout());
        model = model.replace("<artifactId>maven-compiler-plugin</artifactId><version>3.1</version>", """
                <artifactId>maven-compiler-plugin</artifactId><version>3.1</version>
                <executions><execution><id>generate</id><phase>generate-sources</phase><goals><goal>generate</goal></goals></execution></executions>
                """);
        Files.writeString(app.resolve("pom.xml"), model);
        Path librarySource = Files.createDirectories(library.resolve("src/main/java/lib")).resolve("Library.java");
        Files.writeString(librarySource, "package lib; public class Library { public static String message() { return \"native\"; } }\n");
        Path main = Files.createDirectories(app.resolve("sources/java/app")).resolve("Main.java");
        Files.writeString(main, "package app; public class Main { public static void main(String[] args) { System.out.println(lib.Library.message() + \":\" + Generated.message()); } }\n");
        Path check = Files.createDirectories(app.resolve("checks/java/app")).resolve("Check.java");
        Files.writeString(check, "package app; public class Check { Main main; }\n");
        var sources = discover(root, "--project", ":app", "--scope", "compile", "-r", "class-path,source-path");
        assertEquals(0, sources.status(), sources.error());
        assertTrue(Files.isRegularFile(library.resolve("target/classes/lib/Library.class")));
        assertFalse(Files.exists(app.resolve("out/main/app/Main.class")));
        assertFalse(sources.output().contains(app.resolve("out/main").toString()), sources.output());
        assertTrue(sources.output().contains(app.resolve("target/generated-fixture").toString()), sources.output());
        var testSources = discover(root, "--project", ":app", "--scope", "test", "-r", "class-path,source-path");
        assertEquals(0, testSources.status(), testSources.error());
        assertTrue(Files.isRegularFile(app.resolve("out/main/app/Main.class")));
        assertFalse(Files.exists(app.resolve("out/test/app/Check.class")));
        Files.delete(app.resolve("out/main/app/Main.class"));
        Path arguments = root.resolve("runtime.args");
        var binaries = discover(root, "--project", ":app", "--scope", "runtime", "-r", "class-path,module-source-path", "-w", arguments.toString());
        assertEquals(0, binaries.status(), binaries.error());
        assertTrue(Files.isRegularFile(app.resolve("out/main/app/Main.class")));
        assertFalse(Files.exists(app.resolve("out/test/app/Check.class")));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "@" + arguments, "app.Main")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertEquals("native:generated", output.strip());
        var tests = discover(root, "--project", ":app", "--scope", "test", "-r", "class-path");
        assertEquals(0, tests.status(), tests.error());
        assertTrue(Files.isRegularFile(app.resolve("out/test/app/Check.class")));
        assertFalse(Files.exists(app.resolve("target/surefire-reports")));
        Files.writeString(main, "not Java\n");
        Files.writeString(arguments, "original\n");
        var failed = discover(root, "--project", ":app", "--scope", "runtime", "-r", "class-path", "-w", arguments.toString());
        assertNotEquals(0, failed.status());
        assertEquals("", failed.output());
        assertEquals("original\n", Files.readString(arguments));
    }

    @Test
    void sourceOnlyResolutionUsesConfiguredRootsWithoutResolvingDependencies() throws Exception {
        Path root = fixture();
        Path app = root.resolve("app");
        Files.writeString(app.resolve("pom.xml"), pom("app", "", "<dependencies>" + dependency("missing", "compile") + "</dependencies>" + customLayout()));
        for (String scope : List.of("compile", "runtime", "test")) {
            var result = discover(root, "--project", ":app", "--scope", scope, "-r", "source-path");
            assertEquals(0, result.status(), result.error());
            Path source = app.resolve(scope.equals("test") ? "checks/java" : "sources/java");
            assertEquals("\"--source-path\"\n\"" + source + "\"\n", result.output());
        }
        assertFalse(Files.exists(app.resolve("out")));
        assertNoBuildOutputs();
    }

    @Test
    void failedDependencyResolutionDoesNotPublishPartialArguments() throws Exception {
        Path root = fixture(true);
        Path app = root.resolve("app");
        Files.writeString(app.resolve("pom.xml"), pom("app", "", "<dependencies>" + dependency("missing", "compile") + "</dependencies>" + customLayout()));
        Path argumentFile = root.resolve("tool.args");
        Files.writeString(argumentFile, "original\n");
        var result = discover(root, "--project", "fixture:app", "--scope", "compile", "-r", "class-path,source-path", "-w", argumentFile.toString());
        assertNotEquals(0, result.status(), result.error());
        assertTrue(result.error().contains("missing"), result.error());
        assertEquals("", result.output());
        assertEquals("original\n", Files.readString(argumentFile));
    }

    @Test
    void selectedAggregatorSourceRootsDoNotIncludeItsChildren() throws Exception {
        Path root = fixture();
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<build>", "<build><sourceDirectory>root-sources</sourceDirectory>"));
        var result = discover(root, "--project", "fixture:root", "--scope", "compile", "-r", "source-path");
        assertEquals(0, result.status(), result.error());
        assertEquals("\"--source-path\"\n\"" + root.resolve("root-sources") + "\"\n", result.output());
        assertNoBuildOutputs();
    }

    @Test
    void resolvedOptionsComposeWithStandaloneJavacAndJava() throws Exception {
        Path root = fixture(true);
        Path app = root.resolve("app");
        Path repository = root.resolve("repository");
        artifact(repository, "compile", "");
        Path dependencySource = temporaryDirectory.resolve("Dependency.java");
        Files.writeString(dependencySource, "package fixture.dependencies; public class Dependency { public static String message() { return \"hello\"; } }\n");
        Path dependencyClasses = temporaryDirectory.resolve("dependency classes");
        javac("--release", "8", "-Xlint:-options", "-d", dependencyClasses.toString(), dependencySource.toString());
        try (var jar = new JarOutputStream(Files.newOutputStream(repository.resolve("fixture/dependencies/compile/1/compile-1.jar")))) {
            jar.putNextEntry(new JarEntry("fixture/dependencies/Dependency.class"));
            Files.copy(dependencyClasses.resolve("fixture/dependencies/Dependency.class"), jar);
            jar.closeEntry();
        }
        Files.writeString(app.resolve("pom.xml"), pom("app", "", "<dependencies>" + dependency("compile", "compile") + "</dependencies>" + customLayout()));
        Path sources = Files.createDirectories(app.resolve("sources/java/app"));
        Path main = sources.resolve("Main.java");
        Files.writeString(main, "package app; public class Main { public static void main(String[] args) { System.out.println(Helper.message()); } }\n");
        Files.writeString(sources.resolve("Helper.java"), "package app; class Helper { static String message() { return fixture.dependencies.Dependency.message(); } }\n");
        Path compileArguments = root.resolve("compile.args");
        var compile = discover(root, "--project", "fixture:app", "--scope", "compile", "-r", "class-path,source-path", "-w", compileArguments.toString());
        assertEquals(0, compile.status(), compile.error());
        assertEquals("", compile.output());
        assertFalse(Files.exists(app.resolve("out")));
        javac("@" + compileArguments, "-d", app.resolve("out/main").toString(), main.toString());
        Path runtimeArguments = root.resolve("runtime.args");
        var runtime = discover(root, "--project", ":app", "--scope", "runtime", "-r", "class-path", "-w", runtimeArguments.toString());
        assertEquals(0, runtime.status(), runtime.error());
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "@" + runtimeArguments, "app.Main")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertEquals("hello", output.strip());
    }

    @Test
    void moduleArgumentsComposeWithStandaloneJavacAndJava() throws Exception {
        Path root = fixture(true);
        Path app = root.resolve("app");
        Path repository = root.resolve("repository");
        artifact(repository, "compile", "");
        artifact(repository, "plain", "");
        Path dependencySource = Files.createDirectories(temporaryDirectory.resolve("module dependency"));
        Files.writeString(dependencySource.resolve("module-info.java"), "module fixture.dependencies { exports fixture.dependencies; }\n");
        Path dependencyClass = dependencySource.resolve("Dependency.java");
        Files.writeString(dependencyClass, "package fixture.dependencies; public class Dependency { public static String message() { return \"modular\"; } }\n");
        Path dependencyClasses = temporaryDirectory.resolve("module classes");
        javac("--release", "9", "-Xlint:-options", "-d", dependencyClasses.toString(), dependencySource.resolve("module-info.java").toString(), dependencyClass.toString());
        try (var jar = new JarOutputStream(Files.newOutputStream(repository.resolve("fixture/dependencies/compile/1/compile-1.jar")))) {
            for (String name : List.of("module-info.class", "fixture/dependencies/Dependency.class")) {
                jar.putNextEntry(new JarEntry(name));
                Files.copy(dependencyClasses.resolve(name), jar);
                jar.closeEntry();
            }
        }
        String layout = customLayout().replace("<outputDirectory>out/main</outputDirectory>", "<outputDirectory>out/main/app.mod</outputDirectory>");
        Files.writeString(app.resolve("pom.xml"), pom("app", "", "<dependencies>" + dependency("compile", "compile") + dependency("plain", "compile") + "</dependencies>" + layout));
        Path sources = Files.createDirectories(app.resolve("sources/java"));
        Files.writeString(sources.resolve("module-info.java"), "/** @mainClass app.Main */ module app.mod { requires fixture.dependencies; }\n");
        Path main = Files.createDirectories(sources.resolve("app")).resolve("Main.java");
        Files.writeString(main, "package app; public class Main { public static void main(String[] args) { System.out.println(fixture.dependencies.Dependency.message()); } }\n");
        Path compileArguments = root.resolve("module compile.args");
        var compile = discover(root, "--project", "fixture:app", "--scope", "compile", "-r", "class-path,module-path,module-source-path,module", "-w", compileArguments.toString());
        assertEquals(0, compile.status(), compile.error());
        String captured = Files.readString(compileArguments);
        assertTrue(captured.contains("\"--module-path\""), captured);
        assertTrue(captured.contains("app.mod=" + sources), captured);
        assertFalse(captured.contains(app.resolve("out/main/app.mod").toString()), captured);
        javac("@" + compileArguments, "-d", app.resolve("out/main").toString());
        Path runtimeArguments = root.resolve("module runtime.args");
        var runtime = discover(root, "--project", ":app", "--scope", "runtime", "-r", "class-path,module-path,module=main", "-w", runtimeArguments.toString());
        assertEquals(0, runtime.status(), runtime.error());
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "@" + runtimeArguments)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertEquals("modular", output.strip());
        // Once sources are unavailable, an existing binary module still supplies identity.
        Files.delete(sources.resolve("module-info.java"));
        var binary = discover(root, "--project", "fixture:app", "--scope", "runtime", "-r", "module-path,module,describe-module");
        assertEquals(0, binary.status(), binary.error());
        assertTrue(binary.output().contains("\"--module\"\n\"app.mod\""), binary.output());
        assertTrue(binary.output().contains("\"--describe-module\"\n\"app.mod\""), binary.output());
    }

    @Test
    void moduleSourceArgumentsDoNotRequireArtifactResolution() throws Exception {
        Path root = fixture();
        Path app = root.resolve("app");
        Files.writeString(app.resolve("pom.xml"), pom("app", "", "<dependencies>" + dependency("missing", "compile") + "</dependencies>" + customLayout()));
        Path sources = Files.createDirectories(app.resolve("sources/java"));
        Files.writeString(sources.resolve("module-info.java"), "module app.mod { requires missing.dep; }\n");
        var result = discover(root, "--project", "fixture:app", "--scope", "compile", "-r", "module-source-path,module");
        assertEquals(0, result.status(), result.error());
        assertEquals("\"--module-source-path\"\n\"app.mod=" + sources + "\"\n\"--module\"\n\"app.mod\"\n", result.output());
        assertNoBuildOutputs();
    }

    private static void javac(String... arguments) {
        var diagnostics = new StringWriter();
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(new PrintWriter(diagnostics), new PrintWriter(diagnostics), arguments), diagnostics.toString());
    }

    private static String customLayout() {
        return """
                <build>
                  <sourceDirectory>sources/java</sourceDirectory>
                  <testSourceDirectory>checks/java</testSourceDirectory>
                  <outputDirectory>out/main</outputDirectory>
                  <testOutputDirectory>out/test</testOutputDirectory>
                </build>
                """;
    }

    private static String dependency(String artifact, String scope) {
        return """
                <dependency>
                  <groupId>fixture.dependencies</groupId><artifactId>%s</artifactId><version>1</version><scope>%s</scope>
                </dependency>
                """.formatted(artifact, scope);
    }

    private static void artifact(Path repository, String artifact, String dependencies) throws Exception {
        Path directory = Files.createDirectories(repository.resolve("fixture/dependencies/" + artifact + "/1"));
        Files.writeString(directory.resolve(artifact + "-1.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture.dependencies</groupId><artifactId>%s</artifactId><version>1</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(artifact, dependencies));
        try (var jar = new JarOutputStream(Files.newOutputStream(directory.resolve(artifact + "-1.jar")))) {
            jar.finish();
        }
    }

    private Path fixture() throws Exception {
        return fixture(false);
    }

    private Path fixture(boolean compilation) throws Exception {
        Path parent = Files.createDirectories(temporaryDirectory.resolve("parent"));
        Files.writeString(parent.resolve("pom.xml"), pom("parent", "", "<packaging>pom</packaging>"));
        Path root = Files.createDirectories(temporaryDirectory.resolve("project with spaces")).toRealPath();
        Files.writeString(root.resolve("pom.xml"), pom("root", """
                <parent>
                  <groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version>
                  <relativePath>../parent/pom.xml</relativePath>
                </parent>
                """, """
                <packaging>pom</packaging>
                <modules><module>app</module></modules>
                <profiles>
                  <profile>
                    <id>extra</id><activation><file><exists>extra.enabled</exists></file></activation>
                    <modules><module>extra</module></modules>
                  </profile>
                </profiles>
                <build><plugins><plugin>
                  <groupId>fixture.nonexistent</groupId><artifactId>must-not-execute</artifactId><version>1</version>
                  <executions><execution><phase>validate</phase><goals><goal>fail</goal></goals></execution></executions>
                </plugin></plugins></build>
                """));
        for (String module : List.of("app", "extra")) {
            Path directory = Files.createDirectories(root.resolve(module));
            Files.writeString(directory.resolve("pom.xml"), pom(module, "", ""));
        }
        Files.writeString(root.resolve("extra.enabled"), "");
        launcher(root, "--offline\n");
        if (compilation) {
            MavenCompilationFixture.install(maven, root.resolve("repository"));
        }
        return root;
    }

    private void launcher(Path project, String configuration) throws Exception {
        Files.createDirectories(project.resolve(".mvn"));
        Files.writeString(project.resolve(".mvn/maven.config"), configuration);
        Files.writeString(project.resolve("mvnw"), """
                #!/bin/sh
                export JAVA_HOME=%s
                exec sh %s --offline %s "$@"
                """.formatted(shellQuote(maven.javaHome().toString()), shellQuote(maven.executable().toString()), shellQuote("-Dmaven.repo.local=" + project.resolve("repository"))));
    }

    private void assertNoBuildOutputs() throws Exception {
        try (var paths = Files.walk(temporaryDirectory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().equals("target")), "Discovery must not run the build");
        }
    }

    private static String pom(String artifact, String parent, String content) {
        String plugins = MavenCompilationFixture.plugins();
        if (content.contains("<plugins>")) {
            content = content.replace("<plugins>", plugins.replace("</plugins>", ""));
        } else if (content.contains("<build>")) {
            content = content.replace("<build>", "<build>" + plugins);
        } else {
            content += "<build>" + plugins + "</build>";
        }
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <groupId>fixture</groupId><artifactId>%s</artifactId><version>1</version>
                  %s
                </project>
                """.formatted(parent, artifact, content);
    }

    private static Result discover(Path project, String... options) {
        var output = new StringWriter();
        var error = new StringWriter();
        var arguments = new ArrayList<>(List.of("maven", "--project-base-dir", project.toString()));
        arguments.addAll(List.of(options));
        if (!arguments.contains("-r")) {
            arguments.add("--list-projects");
        }
        int status = new Jig().run(new PrintWriter(output, true), new PrintWriter(error, true), arguments.toArray(String[]::new));
        return new Result(status, output.toString(), error.toString());
    }

    private static List<MavenDistribution> mavenVersions() {
        return List.of(new MavenDistribution("3.0.3", 8), new MavenDistribution("3.0.5", 8),
                new MavenDistribution("3.1.1", 8), new MavenDistribution("3.2.5", 8),
                new MavenDistribution("3.3.9", 8), new MavenDistribution("3.6.3", 8),
                new MavenDistribution("3.9.16", 8), new MavenDistribution("3.10.0", 17),
                new MavenDistribution("4.0.0-rc-5", 17));
    }

    private boolean supportsMavenConfig() {
        return List.of("3.0.", "3.1.", "3.2.").stream().noneMatch(maven.version()::startsWith);
    }

    private static boolean availableJdks() {
        return List.of(8, 17).stream().allMatch(version -> System.getenv("JIG_TEST_MAVEN_JAVA_" + version + "_HOME") != null);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private record Result(int status, String output, String error) {}
}
