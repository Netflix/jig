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

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.netflix.tools.jig.Jig;

final class JigProcess {
    private JigProcess() {}

    static ProcessBuilder standaloneJig(String... arguments) {
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
}
