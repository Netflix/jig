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

package com.netflix.tools.jig;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Streams a build invocation without retaining its diagnostic output. */
final class BuildProcess {
    @FunctionalInterface
    interface LineConsumer {
        void accept(String line) throws IOException;
    }

    private BuildProcess() {}

    static int run(Path directory, List<String> arguments, LineConsumer output) throws IOException, InterruptedException {
        var process = new ProcessBuilder(arguments).directory(directory.toFile())
                .redirectInput(Redirect.INHERIT).redirectErrorStream(true).start();
        try {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.accept(line);
                }
            }
            return process.waitFor();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static List<String> launcher(Path directory, String command, String wrapper, String windowsCommand, String windowsWrapper) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path script = directory.resolve(windows ? windowsWrapper : wrapper);
        if (windows) {
            return List.of("cmd.exe", "/d", "/c", Files.isRegularFile(script) ? script.toString() : windowsCommand);
        }
        return Files.isRegularFile(script) ? List.of("sh", script.toString()) : List.of(command);
    }
}
