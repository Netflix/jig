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

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.tools.jig.Jig.Options.ModuleForm;

import static com.netflix.tools.jig.ResolveOptions.Provider.GRADLE;
import static com.netflix.tools.jig.ResolveOptions.Provider.MAVEN;
import static com.netflix.tools.jig.ResolveOptions.Provider.MODULE;

record ResolveOptions(Set<String> options, ModuleForm moduleForm) {
    enum Provider {
        MODULE,
        MAVEN,
        GRADLE
    }

    static final ResolveOptions NONE = new ResolveOptions(Set.of(), ModuleForm.SINGLE);

    private static final Map<String, Set<Provider>> OPTIONS = Map.ofEntries(
            option("module-path", MODULE, MAVEN, GRADLE),
            option("processor-module-path", MODULE, GRADLE),
            option("upgrade-module-path", MODULE, GRADLE),
            option("module-source-path", MODULE, MAVEN, GRADLE),
            option("source-path", MODULE, MAVEN, GRADLE),
            option("module", MODULE, MAVEN, GRADLE),
            option("add-modules", MODULE, MAVEN, GRADLE),
            option("describe-module", MODULE, MAVEN, GRADLE),
            option("main-class", MODULE, GRADLE),
            option("module-version", MODULE, GRADLE),
            option("patch-module", MODULE, GRADLE),
            option("release", MODULE, GRADLE),
            option("multi-release", MODULE, GRADLE),
            option("enable-preview", MODULE, GRADLE),
            option("enable-native-access", MODULE, GRADLE),
            option("enable-final-field-mutation", MODULE, GRADLE),
            option("add-opens", MODULE, GRADLE),
            option("add-exports", MODULE, GRADLE),
            option("class-path", MAVEN, GRADLE),
            option("processor-path", GRADLE),
            option("source", GRADLE),
            option("target", GRADLE),
            option("encoding", GRADLE),
            option("system", GRADLE),
            option("add-reads", GRADLE),
            option("d"));

    ResolveOptions {
        options = Set.copyOf(options);
    }

    static ResolveOptions parse(String value) {
        var parsed = new LinkedHashSet<String>();
        ModuleForm moduleForm = null;
        for (String element : value.split(",", -1)) {
            String option = element.trim();
            if (option.isEmpty()) {
                throw new IllegalArgumentException("resolve options must not be empty");
            }
            ModuleForm form = switch (option) {
                case "module", "module=single" -> ModuleForm.SINGLE;
                case "module=list" -> ModuleForm.LIST;
                case "module=main" -> ModuleForm.MAIN;
                case "module=roots" -> ModuleForm.ROOTS;
                default -> null;
            };
            if (option.startsWith("module=") && form == null) {
                throw new IllegalArgumentException("unknown module form: " + option.substring("module=".length()));
            }
            if (form != null) {
                if (moduleForm != null && moduleForm != form) {
                    throw new IllegalArgumentException("module option has conflicting forms");
                }
                moduleForm = form;
                parsed.add("module");
            } else {
                parsed.add(option);
            }
        }
        var unknown = parsed.stream().filter(option -> !OPTIONS.containsKey(option)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown resolve options: " + unknown);
        }
        if (moduleForm == ModuleForm.ROOTS && !parsed.contains("add-modules")) {
            throw new IllegalArgumentException("module=roots requires add-modules");
        }
        return new ResolveOptions(parsed, moduleForm == null ? ModuleForm.SINGLE : moduleForm);
    }

    Set<String> forProvider(Provider provider) {
        return options.stream().filter(option -> OPTIONS.get(option).contains(provider))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map.Entry<String, Set<Provider>> option(String name, Provider... providers) {
        return Map.entry(name, Set.of(providers));
    }
}
