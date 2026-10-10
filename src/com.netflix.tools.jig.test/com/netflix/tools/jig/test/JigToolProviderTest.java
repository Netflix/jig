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

import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.spi.ToolProvider;
import javax.tools.OptionChecker;

import com.netflix.tools.jig.Jig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JigToolProviderTest {
    private static final String COMPILE_OPTIONS = "module-path,processor-module-path,upgrade-module-path,module-source-path," + "module=list,module-version,patch-module,release,enable-preview," + "add-exports";

    private final ToolProvider jig = new Jig();

    @Test
    void describesOptionsAndCompletesArguments() {
        ToolProvider tool = new Jig();
        var checker = assertInstanceOf(OptionChecker.class, tool);
        var out = new StringWriter();
        var err = new StringWriter();

        assertEquals(0, checker.isSupportedOption("__complete"));
        assertEquals(1, checker.isSupportedOption("--module-path"));
        assertEquals(1, checker.isSupportedOption("-m"));
        assertEquals(0, checker.isSupportedOption("--prefer-jmod"));
        assertEquals(0, checker.isSupportedOption("--version"));
        assertEquals(-1, checker.isSupportedOption("--does-not-exist"));

        int result = tool.run(new PrintWriter(out, true), new PrintWriter(err, true), "__complete", "--target-platform",
                "macos-aar");

        assertEquals(0, result, err.toString());
        assertEquals("macos-aarch64\tTarget platform for classified modules\n:0\n", out.toString());
        assertEquals("", err.toString());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void standaloneMainFlushesResolvedArgumentsBeforeExiting(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("sources");
        var values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("project-paths.count", "1");
        values.setProperty("project-paths.0", ":");
        values.setProperty("source-sets.count", "1");
        values.setProperty("source-sets.0", "main");
        values.setProperty("sources.count", "1");
        values.setProperty("sources.0", source.toString());
        var bytes = new ByteArrayOutputStream();
        values.store(bytes, null);
        Files.writeString(directory.resolve("gradlew"), "#!/bin/sh\nprintf '%s\\n' 'jig-gradle:" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "'\n");
        Path diagnostics = directory.resolve("stderr.txt");
        var process = standaloneJig("gradle", "--root-project-dir", directory.toString(),
                "--project-path", ":", "--source-set", "main", "--classpath", "compile", "-r", "source-path")
                .redirectError(diagnostics.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output + Files.readString(diagnostics));
        assertEquals("\"--source-path\"\n\"" + source + "\"\n", output);
    }

    @Test
    void standaloneServePrintsHelp(@TempDir Path directory) throws Exception {
        Path diagnostics = directory.resolve("stderr.txt");
        var process = standaloneJig("serve", "--help")
                .redirectError(diagnostics.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output + Files.readString(diagnostics));
        assertTrue(output.startsWith("Usage: jig serve [--listen <host:port>]"), output);
    }

    private static ProcessBuilder standaloneJig(String... arguments) {
        Module module = Jig.class.getModule();
        var reference = module.getLayer().configuration().findModule(module.getName()).orElseThrow().reference();
        Path implementation = Path.of(reference.location().orElseThrow());
        Path java = Path.of(System.getProperty("java.home"), "bin/java");
        // Upgrade any embedded jig with the resolved base module and retain its active incremental patches.
        var command = new ArrayList<>(List.of(java.toString(), "--upgrade-module-path", implementation.toString()));
        List<String> vmArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int i = 0; i < vmArguments.size(); i++) {
            String argument = vmArguments.get(i);
            if (argument.equals("--patch-module")) {
                String patch = vmArguments.get(++i);
                if (patch.startsWith(module.getName() + "=")) {
                    command.add("--patch-module=" + patch);
                }
            } else if (argument.startsWith("--patch-module=" + module.getName() + "=")) {
                command.add(argument);
            }
        }
        command.addAll(List.of("--module", module.getName() + "/" + Jig.class.getName()));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command);
    }

    @Test
    void completesMavenOperations() {
        ToolProvider tool = new Jig();
        var out = new StringWriter();
        var err = new StringWriter();

        int operation = tool.run(new PrintWriter(out, true), new PrintWriter(err, true), "__complete", "maven",
                "deploy-c");

        assertEquals(0, operation, err.toString());
        assertEquals("deploy-central\tDeploy artifacts to Maven Central\n:0\n", out.toString());

        out.getBuffer().setLength(0);
        int option = tool.run(new PrintWriter(out, true), new PrintWriter(err, true), "__complete", "maven",
                "deploy-central", "--n");

        assertEquals(0, option, err.toString());
        assertEquals("--name\tMaven Central deployment name\n:0\n", out.toString());
    }

    @Test
    void serveIsATopLevelCommand() {
        var out = new StringWriter();
        var err = new StringWriter();

        for (String option : new String[] {"--help", "-h"}) {
            out.getBuffer().setLength(0);
            int status = jig.run(new PrintWriter(out, true), new PrintWriter(err, true), "serve", option);

            assertEquals(0, status, err.toString());
            assertTrue(out.toString().startsWith("Usage: jig serve [--listen <host:port>]"), out.toString());
            assertEquals("", err.toString());
        }
    }

    @Test
    void completesServeAtTopLevel() {
        var out = new StringWriter();
        var err = new StringWriter();

        int command = jig.run(new PrintWriter(out, true), new PrintWriter(err, true), "__complete", "ser");
        assertEquals(0, command, err.toString());
        assertEquals("serve\tStart the module proxy\n:0\n", out.toString());

        out.getBuffer().setLength(0);
        int option = jig.run(new PrintWriter(out, true), new PrintWriter(err, true), "__complete", "serve", "--l");
        assertEquals(0, option, err.toString());
        assertEquals("--listen\tAddress on which to listen\n:0\n", out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void verboseEnablesDiagnosticAndCompilerTracing(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        var out = new StringWriter();
        var err = new StringWriter();
        String previous = System.clearProperty("jig.verbose");
        try {
            int result = jig.run(
                    new PrintWriter(out),
                    new PrintWriter(err),
                    "--verbose",
                    "--recompile",
                    "--module-source-path",
                    directory.resolve("src").toString(),
                    "-m",
                    "com.example.application",
                    "--resolve-options",
                    "module-path");

            assertEquals(0, result, err.toString());
            assertEquals("true", System.getProperty("jig.verbose"));
            assertTrue(err.toString().contains("[root com.example.application"),
                    err.toString());
            assertTrue(err.toString().contains("[parsing started"),
                    err.toString());
        } finally {
            if (previous == null) {
                System.clearProperty("jig.verbose");
            } else {
                System.setProperty("jig.verbose", previous);
            }
        }
    }

    @Test
    void generatesCompilerArgumentsForASourceModule(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example.application",
                "--resolve-options",
                COMPILE_OPTIONS);

        assertEquals(0, result, err.toString());
        assertTrue(out.toString().contains("--module-source-path"),
                out.toString());
        assertTrue(out.toString().contains("com.example.application=" + source),
                out.toString());
        assertTrue(out.toString().contains("--module\ncom.example.application"),
                out.toString());
        assertFalse(Files.exists(source.resolve("module-info.hash")));
    }

    @Test
    void updateDoesNotCreateEmptyModuleInfoHash(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example.application",
                "--update-module-hashes");

        assertEquals(0, result, err.toString());
        assertFalse(Files.exists(source.resolve("module-info.hash")));
    }

    @Test
    void verifiesModuleWithoutHashedRequirements(@TempDir Path directory) throws Exception {
        var source = directory.resolve("src/com.example.application");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}");
        var out = new StringWriter();
        var err = new StringWriter();

        var result = jig.run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example.application",
                "--verify-module-hashes");

        assertEquals(0, result, err.toString());
        assertFalse(Files.exists(source.resolve("module-info.hash")));
    }

    @Test
    void rejectsUpdateAndVerifyModuleHashesTogether() {
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(new PrintWriter(out), new PrintWriter(err), "--update-module-hashes", "--verify-module-hashes");

        assertEquals(2, result);
        assertEquals(
                "jig: --update-module-hashes and --verify-module-hashes " + "are mutually exclusive\n",
                err.toString());
    }

    @Test
    void omitsAnUndeclaredJarMainClass(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example.library");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module com.example.library {}\n");
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example.library",
                "--resolve-options",
                "main-class");

        assertEquals(0, result, err.toString());
        assertEquals("", out.toString());
    }

    @Test
    void declaresAotWarmup() throws Exception {
        String resource = "META-INF/com.netflix.tools/tools/jig.properties";
        try (var input = jig.getClass()
                            .getModule()
                            .getResourceAsStream(resource)) {
            assertTrue(input != null, resource);
            var properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("--aot-warmup", properties.getProperty("warmup"));
        }
    }

    @Test
    void performsAotWarmup() {
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(new PrintWriter(out), new PrintWriter(err), "--aot-warmup");

        assertEquals(0, result, err.toString());
        assertEquals("", out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void printsVersion() {
        ToolProvider tool = new Jig();
        var out = new StringWriter();
        var err = new StringWriter();
        String version = tool.getClass()
                .getModule()
                .getDescriptor()
                .rawVersion()
                .orElse("dev");

        int result = tool.run(new PrintWriter(out), new PrintWriter(err), "--version");

        assertEquals(0, result, err.toString());
        assertEquals("jig " + version + "\n", out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void rejectsAnUnknownOption() {
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(new PrintWriter(out), new PrintWriter(err), "--does-not-exist");

        assertEquals(2, result);
        assertEquals("jig: unknown option: --does-not-exist\n", err.toString());
    }

    @Test
    void hasNoCacheSubcommand() {
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(new PrintWriter(out), new PrintWriter(err), "cache");

        assertEquals(2, result);
        assertEquals(
                "jig: unexpected argument: cache " + "(use -m or --add-modules to specify root modules)\n",
                err.toString());
    }

    @Test
    void printsHelp() {
        for (String option : new String[] {"--help", "-h"}) {
            var out = new StringWriter();
            var err = new StringWriter();

            int result = jig.run(new PrintWriter(out), new PrintWriter(err), option);

            assertEquals(0, result, err.toString());
            assertTrue(out.toString().contains("Usage:"),
                    out.toString());
            assertTrue(out.toString().contains("--module-path"),
                    out.toString());
            assertTrue(out.toString().contains("module-specific"),
                    out.toString());
            assertTrue(out.toString().contains("--lookup-module <url>"),
                    out.toString());
            assertTrue(out.toString().contains("-r, --resolve-options"),
                    out.toString());
            assertTrue(out.toString().contains("--resolve-compiler-options"), out.toString());
            assertTrue(out.toString().contains("-w, --write-argfile"),
                    out.toString());
            assertTrue(out.toString().contains("--compile-time"),
                    out.toString());
            assertTrue(out.toString().contains("--version"),
                    out.toString());
            assertTrue(out.toString().contains("jig serve [--listen <host:port>]"), out.toString());
            assertTrue(out.toString()
                          .contains("""
                    Resolve options:

                      Paths           module-path, processor-module-path, upgrade-module-path,
                                      module-source-path, source-path, patch-module
                      Modules         module=single, module=list, module=main, module=roots,
                                      add-modules, describe-module
                      Compilation     module-version, release, enable-preview
                      Packaging       main-class, multi-release
                      Access          enable-native-access, enable-final-field-mutation,
                                      add-opens, add-exports
                    """),
                            out.toString());
            assertTrue(
                    out.toString().indexOf("Resolve options:") > out.toString().indexOf("-h, --help"),
                    out.toString());
            assertFalse(out.toString().contains("jig cache"),
                    out.toString());
            assertFalse(out.toString().contains("static-requires"),
                    out.toString());
        }
    }

    @Test
    void rejectsLookupCombinedWithResolution() {
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(new PrintWriter(out), new PrintWriter(err), "--lookup-module", "pkg:maven/com.example/example@1.0.0",
                "-m", "com.example.app");

        assertEquals(2, result);
        assertEquals("jig: --lookup-module cannot be combined with resolution options\n", err.toString());
    }

    @Test
    void resolutionFailurePrintsOneOrdinaryStackTrace(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/com.example");
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example {
                    requires com.example.missing;
                }
                """);
        var out = new StringWriter();
        var err = new StringWriter();

        int result = jig.run(
                new PrintWriter(out),
                new PrintWriter(err),
                "--module-source-path",
                directory.resolve("src").toString(),
                "-m",
                "com.example");

        assertEquals(1, result);
        assertTrue(err.toString().startsWith("java.lang.module.FindException: No version declared for module " + "com.example.missing\n"),
                err.toString());
        assertEquals(1, err.toString().split("No version declared for module com.example.missing", -1).length - 1,
                err.toString());
        assertFalse(err.toString().contains("jig:"),
                err.toString());
    }
}
