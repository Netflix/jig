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

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import com.netflix.tools.jig.CommandLine.Cardinality;
import com.netflix.tools.jig.CommandLine.Completion;
import com.netflix.tools.jig.CommandLine.CompletionRequest;
import com.netflix.tools.jig.CommandLine.ParsedArguments;
import com.netflix.tools.jig.CommandLine.ToolInvocation;
import com.netflix.tools.jig.CommandLine.ToolOption;
import com.netflix.tools.jig.Jig.Options;
import com.netflix.tools.jig.module.ModuleResolution.IntegrityMode;

final class JigCommandLine {
    private static final JigCommandLine INSTANCE = new JigCommandLine();

    private final ToolOption modulePath = option("--module-path", "PATH", "Where to find precompiled modules", "-p");
    private final ToolOption module = option("--module", "MODULE", "Root module to resolve", "-m");
    private final ToolOption addModules = option("--add-modules", "MODULE[,MODULE...]", "Additional local root modules");
    private final ToolOption addRequires = option("--add-requires", "MODULE@VERSION", "Add a versioned external root module");
    private final ToolOption moduleSourcePath = option("--module-source-path", "PATH", "Where to find module source trees");
    private final ToolOption moduleVersion = option("--module-version", "VERSION", "Version for compiled modules and generated POMs");
    private final ToolOption targetPlatform = ToolOption.builder("--target-platform")
            .argument("PLATFORM")
            .choices(
                    "CURRENT",
                    "linux-aarch64",
                    "linux-amd64",
                    "linux-x86_64",
                    "macos-aarch64",
                    "macos-amd64",
                    "macos-x86_64",
                    "windows-aarch64",
                    "windows-amd64",
                    "windows-x86_64")
            .description("Target platform for classified modules")
            .build();
    private final ToolOption preferJmod = flag("--prefer-jmod", "Prefer JMODs on generated module paths");
    private final ToolOption generateModulePoms = option("--generate-module-poms", "ROOT", "Generate module-info.pom files and a Maven reactor");
    private final ToolOption generateConsumerPom = option("--generate-consumer-pom", "DIRECTORY", "Generate Maven consumer POMs");
    private final ToolOption updateModuleHashes = flag("--update-module-hashes", "Write module-content hashes");
    private final ToolOption verifyModuleHashes = flag("--verify-module-hashes", "Verify module-content hashes");
    private final ToolOption resolveOptions = option("--resolve-options", "OPTION[,OPTION...]", "Resolve standard options to stdout", "-r");
    private final ToolOption writeArgfile = option("--write-argfile", "PATH", "Write generated options as a Java argument file", "-w");
    private final ToolOption compileTime = flag("--compile-time", "Include requires static dependencies");
    private final ToolOption recompile = flag("--recompile", "Compile source modules without reusing prior output");
    private final ToolOption noCompileDiagnostics = flag("--no-compile-diagnostics", "Do not report source compilation diagnostics");
    private final ToolOption validateRuntimeAccess = flag("--validate-runtime-access", "Validate runtime access requirements");
    private final ToolOption lookupModule = option("--lookup-module", "URL", "Print the locatable module name for a URL");
    private final ToolOption listModuleVersions = option("--list-module-versions", "MODULE", "List available versions for a module");
    private final ToolOption verbose = flag("--verbose", "Show resolution and compiler tracing");
    private final ToolOption help = flag("--help", "Print this help message", "-h");
    private final CommandLine commandLine = CommandLine.builder()
            .description("Resolve a Java module graph from requires directives")
            .options(
                    modulePath,
                    module,
                    addModules,
                    addRequires,
                    moduleSourcePath,
                    moduleVersion,
                    targetPlatform,
                    preferJmod,
                    generateModulePoms,
                    generateConsumerPom,
                    updateModuleHashes,
                    verifyModuleHashes,
                    resolveOptions,
                    writeArgfile,
                    compileTime,
                    recompile,
                    noCompileDiagnostics,
                    validateRuntimeAccess,
                    lookupModule,
                    listModuleVersions,
                    verbose,
                    help)
            .version(Jig.class.getModule())
            .completion()
            .build();
    private final ToolOption listen = option("--listen", "HOST:PORT", "Address on which to listen");
    private final CommandLine serveCommandLine = CommandLine.builder()
            .description("Start the module proxy")
            .option(listen)
            .operand("HOST:PORT", "Address on which to listen", Cardinality.ZERO_OR_ONE)
            .build();
    private final ToolOption mergeConsumerPom = option("--merge-consumer-pom", "PATH", "Merge metadata into consumer POMs");
    private final ToolOption repository = option("--repository", "ID=URI|PATH", "Maven deployment repository");
    private final ToolOption sign = flag("--sign", "Create detached OpenPGP signatures");
    private final ToolOption deploymentName = option("--name", "NAME", "Maven Central deployment name");
    private final ToolOption manualApproval = flag("--manual", "Wait for manual approval after validation");
    private final CommandLine mavenInstallCommandLine = CommandLine.builder()
            .description("Install flat module artifacts in the local Maven repository")
            .operand("ARTIFACT-DIRECTORY", "Directory of flat, module-named artifacts", Cardinality.EXACTLY_ONE)
            .build();
    private final CommandLine mavenDeployCommandLine = CommandLine.builder()
            .description("Deploy flat module artifacts to a Maven repository")
            .options(mergeConsumerPom, repository, sign)
            .operand("ARTIFACT-DIRECTORY", "Directory of flat, module-named artifacts", Cardinality.EXACTLY_ONE)
            .build();
    private final CommandLine mavenDeployCentralCommandLine = CommandLine.builder()
            .description("Deploy flat module artifacts to Maven Central")
            .options(mergeConsumerPom, deploymentName, manualApproval)
            .operand("ARTIFACT-DIRECTORY", "Directory of flat, module-named artifacts", Cardinality.EXACTLY_ONE)
            .build();
    private final CommandLine mavenCommandLine = CommandLine.builder()
            .description("Maven repository operations")
            .command("install", "Install artifacts in the local repository", mavenInstallCommandLine)
            .command("deploy", "Deploy artifacts to a repository", mavenDeployCommandLine)
            .command("deploy-central", "Deploy artifacts to Maven Central", mavenDeployCentralCommandLine)
            .command("serve", "Start the module proxy", serveCommandLine)
            .build();

    private JigCommandLine() {}

    static JigCommandLine instance() {
        return INSTANCE;
    }

    int isSupportedOption(String option) {
        return commandLine.isSupportedOption(option);
    }

    OptionalInt runVersion(PrintWriter out, String... arguments) {
        return commandLine.runVersion("jig", out, arguments);
    }

    OptionalInt runCompletion(PrintWriter out, PrintWriter err, String... arguments) {
        return commandLine.runCompletion(out, err, this::complete, new ToolInvocation(Arrays.asList(arguments)));
    }

    private List<Completion> complete(CompletionRequest request) {
        List<String> arguments = request.invocation().arguments();
        if (!arguments.isEmpty() && arguments.getFirst().equals("maven")) {
            return mavenCommandLine.complete(
                    new CompletionRequest(
                            new ToolInvocation(request.invocation().workingDirectory(),
                                    arguments.subList(1, arguments.size())),
                            request.current()));
        }
        var completions = new ArrayList<>(commandLine.complete(request));
        if (arguments.isEmpty() && !request.current().startsWith("-")) {
            if ("maven".startsWith(request.current())) {
                completions.add(new Completion("maven", "Maven repository operations"));
            }
        }
        completions.sort(Comparator.comparing(Completion::value));
        return List.copyOf(completions);
    }

    Options parse(String... arguments) {
        int separator = separator(arguments);
        String[] optionArguments = separator < 0 ? arguments : Arrays.copyOf(arguments, separator);
        ParsedArguments parsed;
        try {
            parsed = commandLine.parse(optionArguments);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(parseMessage(e.getMessage()), e);
        }

        var options = new Options();
        var indexes = new IdentityHashMap<ToolOption, Integer>();
        for (ToolOption occurrence : parsed.optionOccurrences()) {
            String value = nextValue(parsed, occurrence, indexes);
            if (occurrence == modulePath) {
                options.modulePath = Options.parsePaths(value);
            } else if (occurrence == module) {
                options.roots.add(value);
            } else if (occurrence == addModules) {
                addModules(options, value);
            } else if (occurrence == addRequires) {
                options.addRequire(value);
            } else if (occurrence == moduleSourcePath) {
                options.moduleSourcePaths.add(value);
            } else if (occurrence == moduleVersion) {
                options.moduleVersion = Options.parseModuleVersion(value);
            } else if (occurrence == targetPlatform) {
                options.setTargetPlatform(value);
            } else if (occurrence == preferJmod) {
                options.preferJmod = true;
            } else if (occurrence == generateModulePoms) {
                options.modulePomRoot = Path.of(value);
            } else if (occurrence == generateConsumerPom) {
                options.consumerPomDirectory = Path.of(value);
            } else if (occurrence == updateModuleHashes) {
                options.setIntegrityMode(IntegrityMode.UPDATE);
            } else if (occurrence == verifyModuleHashes) {
                options.setIntegrityMode(IntegrityMode.VERIFY);
            } else if (occurrence == resolveOptions) {
                options.setResolveOptions(value);
            } else if (occurrence == writeArgfile) {
                options.setArgumentFile(value);
            } else if (occurrence == compileTime) {
                options.compileTime = true;
            } else if (occurrence == recompile) {
                options.recompile = true;
            } else if (occurrence == noCompileDiagnostics) {
                options.emitCompileDiagnostics = false;
            } else if (occurrence == validateRuntimeAccess) {
                options.validateRuntimeAccess = true;
            } else if (occurrence == lookupModule) {
                options.setModuleLookup(value);
            } else if (occurrence == listModuleVersions) {
                options.listModuleVersions = value;
            } else if (occurrence == verbose) {
                options.verbose = true;
            } else if (occurrence == help) {
                options.help = true;
            }
        }
        if (separator >= 0) {
            options.suppliedArguments.addAll(Arrays.asList(arguments)
                    .subList(separator + 1, arguments.length));
        }
        if (options.argumentFile != null && options.resolveOptions == null) {
            throw new IllegalArgumentException("--write-argfile requires --resolve-options");
        }
        return options;
    }

    private int separator(String[] arguments) {
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            if (argument.equals("--")) {
                return i;
            }
            int equals = argument.indexOf('=');
            String name = equals < 0 ? argument : argument.substring(0, equals);
            if (equals < 0 && commandLine.isSupportedOption(name) > 0) {
                i++;
            }
        }
        return -1;
    }

    private static String nextValue(ParsedArguments parsed, ToolOption option, IdentityHashMap<ToolOption, Integer> indexes) {
        if (option.argument().isEmpty()) {
            return null;
        }
        int index = indexes.getOrDefault(option, 0);
        indexes.put(option, index + 1);
        return parsed.values(option).get(index);
    }

    private static void addModules(Options options, String value) {
        for (String module : value.split(",")) {
            String trimmed = module.trim();
            if (!trimmed.isEmpty()) {
                options.addedModules.add(trimmed);
            }
        }
    }

    private static String parseMessage(String message) {
        if (message.startsWith("Unknown option: ")) {
            return "unknown option: " + message.substring("Unknown option: ".length());
        }
        if (message.startsWith("Unexpected argument: ")) {
            return "unexpected argument: " + message.substring("Unexpected argument: ".length()) + " (use -m or --add-modules to specify root modules)";
        }
        int requires = message.indexOf(" requires ");
        if (requires >= 0) {
            return message.substring(0, requires) + " requires an argument";
        }
        return message.substring(0, 1).toLowerCase(Locale.ROOT) + message.substring(1);
    }

    private static ToolOption flag(String name, String description, String... aliases) {
        return ToolOption.flag(name, description, aliases);
    }

    private static ToolOption option(String name, String argument, String description,
            String... aliases) {
        return ToolOption.option(name, argument, description, aliases);
    }
}
