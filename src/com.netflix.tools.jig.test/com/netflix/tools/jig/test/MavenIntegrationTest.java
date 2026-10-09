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
import java.util.List;

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
        assertEquals(List.of(root.toString(), root.resolve("app").toString(), root.resolve("extra").toString()), result.output().lines().toList());
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
        assertEquals(List.of(app.toString()), independent.output().lines().toList());
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
        assertTrue(initial.output().contains(root.resolve("extra").toString()), initial.output());
        Files.delete(root.resolve("extra.enabled"));
        var profileDisabled = discover(root);
        assertEquals(0, profileDisabled.status(), profileDisabled.error());
        assertEquals(List.of(root.toString(), root.resolve("app").toString()), profileDisabled.output().lines().toList());
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<module>app</module>", "<module>extra</module>"));
        var changedModel = discover(root);
        assertEquals(0, changedModel.status(), changedModel.error());
        assertEquals(List.of(root.toString(), root.resolve("extra").toString()), changedModel.output().lines().toList());
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
        assertEquals(List.of(root.toString(), root.resolve("app").toString(), root.resolve("extra").toString()), enabled.output().lines().toList());
        Files.writeString(root.resolve(".mvn/maven.config"), "--offline\n");
        var disabled = discover(root);
        assertEquals(0, disabled.status(), disabled.error());
        assertEquals(List.of(root.toString(), root.resolve("app").toString()), disabled.output().lines().toList());
        assertNoBuildOutputs();
    }

    @Test
    void nativeModelFailuresDoNotPublishPartialProjectDirectories() throws Exception {
        Path root = fixture();
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<module>app</module>", "<module>missing</module>"));
        var result = discover(root);
        assertNotEquals(0, result.status(), result.error());
        assertEquals("", result.output());
        assertTrue(result.error().contains("missing"), result.error());
        assertNoBuildOutputs();
    }

    private Path fixture() throws Exception {
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
        return root;
    }

    private void launcher(Path project, String configuration) throws Exception {
        Files.createDirectories(project.resolve(".mvn"));
        Files.writeString(project.resolve(".mvn/maven.config"), configuration);
        Files.writeString(project.resolve("mvnw"), """
                #!/bin/sh
                export JAVA_HOME=%s
                exec sh %s --offline "$@"
                """.formatted(shellQuote(maven.javaHome().toString()), shellQuote(maven.executable().toString())));
    }

    private void assertNoBuildOutputs() throws Exception {
        try (var paths = Files.walk(temporaryDirectory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().equals("target")), "Discovery must not run the build");
        }
    }

    private static String pom(String artifact, String parent, String content) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <groupId>fixture</groupId><artifactId>%s</artifactId><version>1</version>
                  %s
                </project>
                """.formatted(parent, artifact, content);
    }

    private static Result discover(Path project) {
        var output = new StringWriter();
        var error = new StringWriter();
        int status = new Jig().run(new PrintWriter(output, true), new PrintWriter(error, true),
                "maven", "--project-base-dir", project.toString(), "--list-project-dirs");
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
