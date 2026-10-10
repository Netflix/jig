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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Small local lifecycle plugins keep native build tests offline on every Maven version. */
final class MavenCompilationFixture {
    private MavenCompilationFixture() {}

    static String plugins() {
        return """
                <plugins>
                  <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>2.6</version></plugin>
                  <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.1</version></plugin>
                </plugins>
                """;
    }

    static void install(MavenDistribution maven, Path repository) throws Exception {
        Path staging = Files.createDirectories(repository.resolve("fixture-plugin"));
        Path source = staging.resolve("Compile.java");
        Files.writeString(source, """
                package fixture;
                import java.io.File;
                import java.util.ArrayList;
                import java.util.List;
                import javax.tools.ToolProvider;
                import org.apache.maven.plugin.AbstractMojo;
                import org.apache.maven.plugin.MojoExecutionException;
                import org.apache.maven.project.MavenProject;
                public final class Compile extends AbstractMojo {
                    private MavenProject project;
                    private boolean test;
                    private boolean noop;
                    private boolean generate;
                    public void execute() throws MojoExecutionException {
                        if (noop) { return; }
                        try {
                            if (generate) {
                                File root = new File(project.getBuild().getDirectory(), "generated-fixture");
                                File generated = new File(root, "app/Generated.java");
                                generated.getParentFile().mkdirs();
                                java.nio.file.Files.write(generated.toPath(), "package app; public class Generated { public static String message() { return \\\"generated\\\"; } }\\n".getBytes("UTF-8"));
                                project.addCompileSourceRoot(root.getPath());
                                return;
                            }
                            File output = new File(test ? project.getBuild().getTestOutputDirectory() : project.getBuild().getOutputDirectory());
                            // A precompiled modular fixture uses the host JDK; the old
                            // Maven/JDK compatibility cases must not recompile it on 8.
                            if (new File(output, "module-info.class").isFile()) { return; }
                            List<String> arguments = new ArrayList<String>();
                            arguments.add("-source"); arguments.add("8");
                            arguments.add("-target"); arguments.add("8");
                            arguments.add("-Xlint:-options");
                            arguments.add("-d"); arguments.add(output.getPath());
                            List<String> paths = test ? project.getTestClasspathElements() : project.getCompileClasspathElements();
                            arguments.add("-classpath"); arguments.add(String.join(File.pathSeparator, paths));
                            int options = arguments.size();
                            for (String root : test ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots()) {
                                sources(new File(root), arguments);
                            }
                            if (arguments.size() != options) {
                                output.mkdirs();
                                int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(new String[0]));
                                if (status != 0) { throw new MojoExecutionException("Fixture compilation failed: " + status); }
                            }
                            if (!test && output.isDirectory()) { project.getArtifact().setFile(output); }
                        } catch (MojoExecutionException failure) { throw failure; }
                        catch (Exception failure) { throw new MojoExecutionException("Cannot compile fixture", failure); }
                    }
                    private static void sources(File root, List<String> arguments) {
                        if (root.isFile() && root.getName().endsWith(".java")) { arguments.add(root.getPath()); }
                        File[] children = root.listFiles();
                        if (children != null) { for (File child : children) { sources(child, arguments); } }
                    }
                }
                """);
        Path classes = staging.resolve("classes");
        Path home = maven.executable().getParent().getParent();
        String classpath;
        try (var files = Files.list(home.resolve("lib"))) {
            classpath = String.join(System.getProperty("path.separator"), files.filter(path -> path.toString().endsWith(".jar")).map(Path::toString).toList());
        }
        var diagnostics = new StringWriter();
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(new PrintWriter(diagnostics), new PrintWriter(diagnostics),
                "--release", "8", "-Xlint:-options", "-proc:none", "-classpath", classpath,
                "-d", classes.toString(), source.toString()), diagnostics.toString());
        install(repository, classes, "maven-compiler-plugin", "3.1", List.of(goal("compile", "compile", false, false), goal("testCompile", "test", true, false), goal("generate", "", false, false)));
        install(repository, classes, "maven-resources-plugin", "2.6", List.of(goal("resources", "", false, true), goal("testResources", "", true, true)));
        // Older Maven versions inject this legacy plugin dependency even when the
        // plugin does not use it. An empty fixture artifact keeps resolution offline.
        Path plexus = Files.createDirectories(repository.resolve("org/codehaus/plexus/plexus-utils/1.1"));
        Files.writeString(plexus.resolve("plexus-utils-1.1.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.codehaus.plexus</groupId><artifactId>plexus-utils</artifactId><version>1.1</version></project>\n");
        try (var jar = new JarOutputStream(Files.newOutputStream(plexus.resolve("plexus-utils-1.1.jar")))) {
            jar.finish();
        }
    }

    private static String goal(String name, String scope, boolean test, boolean noop) {
        return """
                <mojo>
                  <goal>%s</goal><requiresProject>true</requiresProject>
                  %s
                  <implementation>fixture.Compile</implementation><language>java</language>
                  <instantiationStrategy>per-lookup</instantiationStrategy><threadSafe>true</threadSafe>
                  <parameters>
                    <parameter><name>project</name><type>org.apache.maven.project.MavenProject</type><required>true</required><editable>false</editable></parameter>
                    <parameter><name>test</name><type>boolean</type><editable>false</editable></parameter>
                    <parameter><name>noop</name><type>boolean</type><editable>false</editable></parameter>
                    <parameter><name>generate</name><type>boolean</type><editable>false</editable></parameter>
                  </parameters>
                  <configuration>
                    <project implementation="org.apache.maven.project.MavenProject">${project}</project>
                    <test implementation="boolean" default-value="%s"/>
                    <noop implementation="boolean" default-value="%s"/>
                    <generate implementation="boolean" default-value="%s"/>
                  </configuration>
                </mojo>
                """.formatted(name, scope.isEmpty() ? "" : "<requiresDependencyResolution>" + scope + "</requiresDependencyResolution>", test, noop, name.equals("generate"));
    }

    private static void install(Path repository, Path classes, String artifact, String version, List<String> goals) throws Exception {
        Path directory = Files.createDirectories(repository.resolve("org/apache/maven/plugins/" + artifact + "/" + version));
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.apache.maven.plugins</groupId><artifactId>%s</artifactId><version>%s</version><packaging>maven-plugin</packaging></project>
                """.formatted(artifact, version));
        try (var jar = new JarOutputStream(Files.newOutputStream(directory.resolve(artifact + "-" + version + ".jar"))); var files = Files.walk(classes)) {
            jar.putNextEntry(new JarEntry("META-INF/maven/plugin.xml"));
            jar.write(("<plugin><name>Fixture</name><groupId>org.apache.maven.plugins</groupId><artifactId>" + artifact
                    + "</artifactId><version>" + version + "</version><goalPrefix>fixture</goalPrefix><mojos>"
                    + String.join("", goals) + "</mojos></plugin>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
    }
}
