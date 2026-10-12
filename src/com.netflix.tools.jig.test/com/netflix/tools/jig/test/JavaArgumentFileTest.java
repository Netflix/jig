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
import java.util.spi.ToolProvider;

import com.netflix.tools.jig.Jig;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC})
class JavaArgumentFileTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"double\"quote", "single'quote", "hash#path", "back\\slash", "space and \"quote\""})
    void nativeModuleArgumentsRoundTripThroughJavac(String directory) throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve(directory));
        Files.writeString(sources.resolve("module-info.java"), "module quoting.mod {}\n");
        Path arguments = temporaryDirectory.resolve("compiler.args");
        var output = new StringWriter();
        var error = new StringWriter();
        int status = new Jig().run(new PrintWriter(output, true), new PrintWriter(error, true),
                "--module-source-path", "quoting.mod=" + sources, "-m", "quoting.mod",
                "-r", "module-source-path,module", "-w", arguments.toString());
        assertEquals(0, status, error.toString());
        var diagnostics = new StringWriter();
        assertEquals(0, ToolProvider.findFirst("javac").orElseThrow().run(new PrintWriter(diagnostics), new PrintWriter(diagnostics),
                "@" + arguments, "-d", temporaryDirectory.resolve("classes").toString()), diagnostics.toString());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("classes/quoting.mod/module-info.class")));
    }
}
