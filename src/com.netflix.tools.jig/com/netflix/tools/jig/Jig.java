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
import java.io.PrintWriter;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Version;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import javax.tools.Diagnostic.Kind;
import javax.tools.OptionChecker;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.ModuleRuntimeAccessOptions.Builder;
import com.netflix.module.ModuleRuntimeAccessOptions.PackageAccess;
import com.netflix.module.ModuleRuntimeMetadata;
import com.netflix.tools.jig.Jig.Options.ModuleForm;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.Transporter;
import com.netflix.tools.jig.module.ConsumerPomGenerator;
import com.netflix.tools.jig.module.ModuleOrigin;
import com.netflix.tools.jig.module.ModulePathReference;
import com.netflix.tools.jig.module.ModulePomGenerator;
import com.netflix.tools.jig.module.ModuleRepositorySession;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.ModuleResolution.IntegrityMode;
import com.netflix.tools.jig.module.SourceModule;
import com.netflix.tools.jig.module.SourceModuleFinder;
import com.netflix.tools.jig.module.SourceModuleReference;
import com.netflix.tools.jig.module.Trace;
import com.netflix.tools.jig.module.maven.transport.AbstractModuleTransporter.ModuleIdentity;
import com.netflix.tools.jig.module.maven.transport.TransporterHttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * jig — Java module graph resolver and argument assembler.
 *
 * <p>Resolves module dependencies from {@code requires} directives and Maven
 * repositories. Modes of operation:
 * <ul>
 *   <li><strong>Resolve</strong> (no resolve option): resolves and validates
 *       the module graph.</li>
 *   <li><strong>Arguments</strong> ({@code --resolve-options}): assembles the
 *       requested standard options from the resolved module graph.</li>
 *   <li><strong>Generate POMs</strong>: writes Maven build or consumer POMs
 *       for the selected source modules.</li>
 * </ul>
 *
 * <p>Accepts the same {@code --module-source-path}, {@code --module-path},
 * and {@code --module} options as {@code javac}.
 */
public class Jig implements ToolProvider, OptionChecker {
    private static final JigCommandLine COMMAND_LINE = JigCommandLine.instance();
    private static final Set<String> GRAPH_INDEPENDENT_RESOLVE_OPTIONS = Set.of("module-version", "release", "multi-release");
    private static final Set<String> ACCESS_RESOLUTION_OPTIONS = Set.of("release", "multi-release", "enable-preview", "enable-native-access", "enable-final-field-mutation", "add-opens",
            "add-exports");
    private static final Set<String> RESOLVE_OPTIONS = Set.of(
            "module-path",
            "processor-module-path",
            "upgrade-module-path",
            "module-source-path",
            "source-path",
            "module",
            "add-modules",
            "describe-module",
            "main-class",
            "module-version",
            "patch-module",
            "release",
            "multi-release",
            "enable-preview",
            "enable-native-access",
            "enable-final-field-mutation",
            "add-opens",
            "add-exports");

    @Override
    public String name() {
        return "jig";
    }

    @Override
    public int isSupportedOption(String option) {
        return COMMAND_LINE.isSupportedOption(option);
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        var version = COMMAND_LINE.runVersion(out, args);
        if (version.isPresent()) {
            return version.orElseThrow();
        }
        var completion = COMMAND_LINE.runCompletion(out, err, args);
        if (completion.isPresent()) {
            return completion.orElseThrow();
        }
        if (args.length == 1 && args[0].equals("--aot-warmup")) {
            try {
                return AotWarmup.run();
            } catch (Exception e) {
                e.printStackTrace(err);
                return 1;
            }
        }
        return runWithSessions(out, err, () -> ModuleRepositorySession.create(diagnostic -> {
            if (diagnostic.getKind() != Kind.ERROR) {
                err.println(diagnostic);
            }
        }),
                args);
    }

    int runWithSessions(PrintWriter out, PrintWriter err, Supplier<ModuleRepositorySession> sessions,
                        String... args) {
        if (args.length > 0 && args[0].equals("maven")) {
            if (args.length > 1 && args[1].equals("serve")) {
                return serve(out, err, Arrays.copyOfRange(args, 2, args.length));
            }
            return MavenCommands.run(out, err, sessions, Arrays.copyOfRange(args, 1, args.length));
        }
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("jig: " + e.getMessage());
            return 2;
        }

        if (options.help) {
            printHelp(out);
            return 0;
        }

        if (options.consumerPomDirectory != null && options.moduleVersion == null) {
            err.println("jig: --generate-consumer-pom requires --module-version");
            return 2;
        }
        if (options.modulePomRoot != null && options.consumerPomDirectory != null) {
            err.println("jig: --generate-module-poms and --generate-consumer-pom are mutually exclusive");
            return 2;
        }
        if (options.moduleLookup != null && options.listModuleVersions != null) {
            err.println("jig: --lookup-module and --list-module-versions are mutually exclusive");
            return 2;
        }
        if (options.moduleLookup != null || options.listModuleVersions != null) {
            String operation = options.moduleLookup != null ? "--lookup-module" : "--list-module-versions";
            if (options.hasResolutionOptions()) {
                err.println("jig: " + operation + " cannot be combined with resolution options");
                return 2;
            }
        } else if (options.rootNames().isEmpty() && options.moduleForm != ModuleForm.MAIN && options.requiresModuleGraph()) {
            err.println("jig: no root module specified; use --module, --add-modules, or --add-requires");
            return 2;
        }

        if (options.verbose) {
            System.setProperty("jig.verbose", "true");
        }

        try (var session = sessions.get()) {
            if (options.moduleLookup != null) {
                out.println(session.lookupModule(options.moduleLookup));
                return 0;
            }
            if (options.listModuleVersions != null) {
                for (String v : session.listVersions(options.listModuleVersions)) {
                    out.println(v);
                }
                return 0;
            }

            var modulePathFinder = options.modulePath.length > 0 ? ModuleFinder.of(options.modulePath) : ModuleFinder.of();

            var sourceFinders = new ArrayList<SourceModuleFinder>();
            for (var moduleSourcePath : options.moduleSourcePaths) {
                sourceFinders.add(SourceModuleFinder.ofModuleSourcePath(moduleSourcePath));
            }

            ModuleFinder sourceFinder = ModuleFinder.of();
            ModuleFinder fixedModules;
            if (!sourceFinders.isEmpty()) {
                sourceFinder = ModuleFinder.compose(sourceFinders.toArray(ModuleFinder[]::new));
                fixedModules = ModuleFinder.compose(sourceFinder, modulePathFinder);
            } else {
                fixedModules = modulePathFinder;
            }

            if (options.mainCandidates().isEmpty() && options.moduleForm == ModuleForm.MAIN) {
                ModuleFinder candidates = !sourceFinders.isEmpty() ? sourceFinder : modulePathFinder;
                options.roots.add(inferredMainClass(candidates).moduleName());
            }

            boolean includeStatics = shouldIncludeStatics(options);
            var resolution = ModuleResolution.resolve(session, fixedModules, options.resolutionRoots(), options.addedRequires, includeStatics,
                    shouldIncludeSources(options), options.integrityMode);
            var config = resolution.configuration();
            var configurationRoots = resolution.configurationRoots();
            var runtimeRoots = configurationRoots.stream()
                    .filter(root -> !resolution.staticRoots().contains(root))
                    .toList();
            var staticOnly = ModuleResolution.staticOnlyModules(config, runtimeRoots);

            if (options.verbose) {
                traceResolution(err, config, options.rootNames());
            }

            Map<String, Path> jmods = options.preferJmod ? session.resolveJmodPath(resolution.repositoryVersions(), options.jmodTargetClassifier()) : Map.of();
            var repositoryPaths = new RepositoryPaths(session.resolveTargetJarPath(resolution.repositoryVersions(), options.targetClassifier()),
                    jmods);
            var moduleCompiler = session.moduleCompiler();
            var sourceModules = new SourceModulePaths(options, resolution, repositoryPaths, moduleCompiler, err);
            sourceModules.bind();
            var sourcePaths = options.hasResolveOption("source-path") ? resolution.sources() : Map.<String, Path>of();

            boolean generatesArguments = options.resolveOptions != null;
            String generatedArguments = !generatesArguments && !options.validateRuntimeAccess
                    ? null
                    : renderArguments(generatesArguments ? options.resolveOptions : Set.of(), options, resolution,
                            staticOnly, repositoryPaths, sourceModules, sourcePaths);

            if (options.modulePomRoot != null) {
                ModulePomGenerator.generate(resolution, options.modulePomRoot, session.moduleRepositoryPath());
            } else if (options.consumerPomDirectory != null) {
                ConsumerPomGenerator.generate(resolution, options.moduleVersion, options.consumerPomDirectory);
            }
            if (options.argumentFile != null) {
                Files.writeString(options.argumentFile, generatedArguments);
            } else if (generatedArguments != null) {
                out.print(generatedArguments);
                out.flush();
            }
            return 0;
        } catch (Exception e) {
            e.printStackTrace(err);
            return 1;
        }
    }

    record ResolvedAccessOptions(Integer release, boolean enablePreview, List<String> enableNativeAccess,
            List<String> enableFinalFieldMutation, List<String> addOpens, List<String> addExports) {
        private static final ResolvedAccessOptions EMPTY = new ResolvedAccessOptions(null, false, List.of(), List.of(),
                List.of(), List.of());
    }

    public record RepositoryPaths(Map<String, Path> targetJars, Map<String, Path> jmods) {
        public RepositoryPaths {
            targetJars = Map.copyOf(targetJars);
            jmods = Map.copyOf(jmods);
        }
    }

    public static boolean shouldIncludeStatics(Options options) {
        return !options.moduleSourcePaths.isEmpty()
                || options.modulePomRoot != null
                || options.consumerPomDirectory != null;
    }

    public static boolean shouldIncludeSources(Options options) {
        return options.hasResolveOption("source-path") || options.modulePomRoot != null;
    }

    public static String renderArguments(Set<String> resolveOptions, Options options, ModuleResolution resolution,
            Set<String> staticOnly, RepositoryPaths repositoryPaths)
            throws IOException {
        return renderArguments(resolveOptions, options, resolution, staticOnly, repositoryPaths, null,
                resolution.sources());
    }

    private static String renderArguments(
            Set<String> resolveOptions,
            Options options,
            ModuleResolution resolution,
            Set<String> staticOnly,
            RepositoryPaths repositoryPaths,
            SourceModulePaths sourceModules,
            Map<String, Path> sourcePaths)
            throws IOException {
        validateSelectedArtifacts(resolution, repositoryPaths);
        boolean describe = resolveOptions.contains("describe-module");
        Set<String> excludedModules = resolveOptions.contains("module-source-path") ? Set.of() : staticOnly;
        var config = resolution.configuration();
        var configurationRoots = resolution.configurationRoots().stream()
                .filter(name -> !excludedModules.contains(name))
                .toList();
        var authoritativeRoots = options.rootNames().stream()
                .filter(name -> !excludedModules.contains(name))
                .toList();
        var pathSeparator = System.getProperty("path.separator");
        var sourceSystemOverrides = resolution.systemOverrides().stream()
                .filter(resolution.moduleSources()::containsKey)
                .filter(name -> !excludedModules.contains(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var repositorySystemOverrides = resolution.systemOverrides().stream()
                .filter(resolution.repositoryVersions()::containsKey)
                .filter(name -> !excludedModules.contains(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var fixedSystemOverrides = resolution.systemOverrides().stream()
                .filter(name -> !sourceSystemOverrides.contains(name))
                .filter(name -> !repositorySystemOverrides.contains(name))
                .filter(name -> !excludedModules.contains(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var suppliedSystemOverrides = new LinkedHashSet<>(sourceSystemOverrides);
        suppliedSystemOverrides.addAll(fixedSystemOverrides);
        boolean consumesCompiledModules = resolveOptions.contains("module-path") && !resolveOptions.contains("module-source-path");
        if (resolveOptions.contains("module-path") && !resolveOptions.contains("upgrade-module-path")) {
            requireNoSystemOverrides(
                    consumesCompiledModules ? sourceSystemOverrides : Set.of(),
                    fixedSystemOverrides,
                    repositorySystemOverrides);
        }

        String modulePath = "";
        String processorModulePath = "";
        String upgradeModulePath = "";
        Map<String, Path> sourcePatches = Map.of();
        if (resolveOptions.contains("module-path")) {
            var modulePaths = new LinkedHashSet<Path>();
            var upgradeModulePaths = new LinkedHashSet<Path>();
            for (Path path : options.modulePath) {
                boolean containsSystemOverride = suppliedSystemOverrides.stream().anyMatch(name -> ModuleFinder.of(path)
                        .find(name)
                        .isPresent());
                if (containsSystemOverride && resolveOptions.contains("upgrade-module-path")) {
                    upgradeModulePaths.add(path);
                } else {
                    modulePaths.add(path);
                }
            }
            if (resolveOptions.contains("release")) {
                var destination = resolveOptions.contains("upgrade-module-path") ? upgradeModulePaths : modulePaths;
                for (String moduleName : repositorySystemOverrides.stream()
                        .sorted()
                        .toList()) {
                    ModuleReference reference = resolution.observableModules()
                            .find(moduleName)
                            .orElse(null);
                    if (reference == null) {
                        continue;
                    }
                    Path path = modulePath(moduleName, reference, repositoryPaths);
                    if (path != null) {
                        destination.add(path);
                    }
                }
            }
            if (consumesCompiledModules && resolveOptions.contains("upgrade-module-path")) {
                for (String moduleName : repositorySystemOverrides.stream()
                        .sorted()
                        .toList()) {
                    ModuleReference reference = resolution.observableModules()
                            .find(moduleName)
                            .orElse(null);
                    if (reference == null) {
                        continue;
                    }
                    Path path = modulePath(moduleName, reference, repositoryPaths);
                    if (path != null) {
                        upgradeModulePaths.add(path);
                    }
                }
            }
            var selectedModules = config.modules().stream()
                    .filter(candidate -> !excludedModules.contains(candidate.name()))
                    .filter(candidate -> candidate.reference() instanceof SourceModuleReference || ModuleFinder.ofSystem()
                            .find(candidate.name())
                            .isEmpty())
                    .sorted(Comparator.comparing(ResolvedModule::name))
                    .toList();
            if (consumesCompiledModules && sourceModules != null) {
                SourceModuleReference.compileAll(selectedModules.stream()
                        .map(ResolvedModule::reference)
                        .filter(SourceModuleReference.class::isInstance)
                        .map(SourceModuleReference.class::cast)
                        .toList());
            }
            for (var module : selectedModules) {
                Path path;
                if (module.reference() instanceof SourceModuleReference source) {
                    if (!consumesCompiledModules) {
                        path = null;
                    } else {
                        if (sourceModules == null) {
                            throw new IllegalStateException("Source module has no module path: " + module.name());
                        }
                        path = sourceModules.modulePath(module.name(), source, resolveOptions.contains("patch-module"));
                    }
                } else if (Arrays.stream(options.modulePath).anyMatch(candidate -> ModuleFinder.of(candidate)
                        .find(module.name())
                        .isPresent())) {
                    path = null;
                } else {
                    path = modulePath(module.name(), module.reference(), repositoryPaths);
                }
                if (path == null) {
                    continue;
                }
                if (resolution.systemOverrides().contains(module.name()) && resolveOptions.contains("upgrade-module-path")) {
                    upgradeModulePaths.add(path);
                } else {
                    modulePaths.add(path);
                }
            }
            modulePath = modulePaths.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(pathSeparator));
            upgradeModulePath = upgradeModulePaths.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(pathSeparator));
            if (sourceModules != null && resolveOptions.contains("patch-module")) {
                sourcePatches = sourceModules.patches();
            }
        }
        if (resolveOptions.contains("processor-module-path")) {
            processorModulePath = processorModulePath(resolution, authoritativeRoots, repositoryPaths, sourceModules, pathSeparator);
        }

        List<String> moduleSourcePath = resolveOptions.contains("module-source-path") ? resolution.moduleSources().entrySet().stream()
                .filter(entry -> !excludedModules.contains(entry.getKey()))
                .sorted(Entry.comparingByKey())
                .map(entry -> moduleSourcePathArgument(entry.getKey(), entry.getValue()))
                .toList()
                : List.of();
        String sourcePath = resolveOptions.contains("source-path") ? sourcePaths.entrySet().stream()
                .filter(entry -> !excludedModules.contains(entry.getKey()))
                .sorted(Entry.comparingByKey())
                .map(Entry::getValue)
                .map(Path::toString)
                .collect(Collectors.joining(pathSeparator))
                : "";

        boolean needsAccessOptions = options.validateRuntimeAccess || resolveOptions.stream().anyMatch(ACCESS_RESOLUTION_OPTIONS::contains);
        var accessOptions = needsAccessOptions ? collectAccessOptions(
                config,
                excludedModules,
                authoritativeRoots,
                configurationRoots,
                resolution.moduleSources().keySet(),
                resolution.observableModules(),
                resolveOptions.contains("release") || resolveOptions.contains("multi-release"))
                : ResolvedAccessOptions.EMPTY;
        if (options.validateRuntimeAccess) {
            validateRuntimeAccessRequirements(config, excludedModules, accessOptions, options.suppliedArguments);
        }

        var arguments = new StringBuilder();
        if (!modulePath.isEmpty()) {
            appendArgument(arguments, "--module-path");
            appendArgument(arguments, modulePath);
        }
        if (!processorModulePath.isEmpty()) {
            appendArgument(arguments, "--processor-module-path");
            appendArgument(arguments, processorModulePath);
        }
        if (!upgradeModulePath.isEmpty()) {
            appendArgument(arguments, "--upgrade-module-path");
            appendArgument(arguments, upgradeModulePath);
        }
        for (var patch : sourcePatches.entrySet().stream()
                .sorted(Entry.comparingByKey())
                .toList()) {
            appendArgument(arguments, "--patch-module");
            appendArgument(arguments, patch.getKey() + "=" + patch.getValue());
        }
        for (String path : moduleSourcePath) {
            appendArgument(arguments, "--module-source-path");
            appendArgument(arguments, path);
        }
        if (!sourcePath.isEmpty()) {
            appendArgument(arguments, "--source-path");
            appendArgument(arguments, sourcePath);
        }
        appendAccessArguments(arguments, resolveOptions, accessOptions);

        if (resolveOptions.contains("module-version") && options.moduleVersion != null) {
            appendArgument(arguments, "--module-version");
            appendArgument(arguments, options.moduleVersion);
        }

        if (resolveOptions.contains("main-class")) {
            optionalMainClass(options, resolution).ifPresent(mainClass -> {
                appendArgument(arguments, "--main-class");
                appendArgument(arguments, mainClass.className());
            });
        }
        boolean writesRootsWithModule = resolveOptions.contains("module") && options.moduleForm == ModuleForm.ROOTS;
        if (resolveOptions.contains("add-modules")
                && !configurationRoots.isEmpty()
                && (!writesRootsWithModule || configurationRoots.size() != 1)) {
            appendArgument(arguments, "--add-modules");
            appendArgument(arguments, String.join(",", configurationRoots));
        }
        if (resolveOptions.contains("module")) {
            var moduleRoots = resolveOptions.contains("module-source-path") ? configurationRoots.stream()
                    .filter(resolution.moduleSources()::containsKey)
                    .toList()
                    : configurationRoots;
            switch (options.moduleForm) {
                case SINGLE -> {
                    if (moduleRoots.size() != 1) {
                        throw new IllegalArgumentException("module requires exactly one applicable module, found " + moduleRoots.size() + "; use module=list for a module list");
                    }
                    appendArgument(arguments, "--module");
                    appendArgument(arguments, moduleRoots.getFirst());
                }
                case LIST -> {
                    if (!moduleRoots.isEmpty()) {
                        appendArgument(arguments, "--module");
                        appendArgument(arguments, String.join(",", moduleRoots));
                    }
                }
                case MAIN -> {
                    var mainClass = mainClass(options, resolution);
                    appendArgument(arguments, "--module");
                    appendArgument(arguments, mainClass.moduleName() + "/" + mainClass.className());
                }
                case ROOTS -> {
                    if (configurationRoots.size() == 1) {
                        appendArgument(arguments, "--module");
                        appendArgument(arguments, configurationRoots.getFirst());
                    }
                }
            }
        }
        if (resolveOptions.contains("describe-module")) {
            String root = singleRoot(options, "describe");
            appendArgument(arguments, "--describe-module");
            appendArgument(arguments, root);
        }
        return arguments.toString();
    }

    private static void requireNoSystemOverrides(
            Set<String> sourceOverrides, Set<String> fixedOverrides, Set<String> repositoryOverrides) {
        if (!sourceOverrides.isEmpty()) {
            String modules = sourceOverrides.stream()
                    .sorted()
                    .collect(Collectors.joining(", "));
            String subject = sourceOverrides.size() == 1 ? "Source module " + modules + " shadows a system module" : "Source modules " + modules + " shadow system modules";
            throw new IllegalArgumentException(subject + ", but the requested options do not support " + "--upgrade-module-path");
        }
        if (!fixedOverrides.isEmpty()) {
            String modules = fixedOverrides.stream()
                    .sorted()
                    .collect(Collectors.joining(", "));
            String subject = fixedOverrides.size() == 1 ? "Module path module " + modules + " shadows a system module" : "Module path modules " + modules + " shadow system modules";
            throw new IllegalArgumentException(subject + ", but the requested options do not support " + "--upgrade-module-path");
        }
        if (!repositoryOverrides.isEmpty()) {
            String modules = repositoryOverrides.stream()
                    .sorted()
                    .collect(Collectors.joining(", "));
            String subject = repositoryOverrides.size() == 1 ? "Resolved module " + modules + " shadows a system module" : "Resolved modules " + modules + " shadow system modules";
            throw new IllegalArgumentException(subject + ", but the requested options do not support " + "--upgrade-module-path");
        }
    }

    private static void validateSelectedArtifacts(ModuleResolution resolution, RepositoryPaths repositoryPaths) {
        var selectedArtifacts = new LinkedHashMap<>(repositoryPaths.targetJars());
        selectedArtifacts.putAll(repositoryPaths.jmods());
        for (var entry : selectedArtifacts.entrySet()) {
            String moduleName = entry.getKey();
            Path path = entry.getValue();
            var expected = resolution.observableModules()
                    .find(moduleName)
                    .orElseThrow(() -> new IllegalArgumentException("Selected artifact module is not in the resolved graph: " + moduleName))
                    .descriptor();
            ModuleDescriptor actual;
            if (repositoryPaths.jmods().containsKey(moduleName)) {
                actual = ModuleIdentity.parseJmod(path).descriptor();
                if (actual == null) {
                    throw new IllegalArgumentException("Selected JMOD has no explicit module descriptor: " + path);
                }
            } else {
                actual = ModuleFinder.of(path)
                        .find(moduleName)
                        .orElseThrow(() -> new IllegalArgumentException("Selected JAR does not provide module " + moduleName + ": " + path))
                        .descriptor();
            }
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException("Selected artifact descriptor does not match resolved module " + moduleName + ": " + path);
            }
        }
    }

    private static Path modulePath(String moduleName, ModuleReference reference, RepositoryPaths repositoryPaths) throws IOException {
        Path selected = repositoryPaths.jmods().get(moduleName);
        if (selected == null) {
            selected = repositoryPaths.targetJars().get(moduleName);
        }
        if (selected != null) {
            return selected;
        }
        if (reference instanceof ModulePathReference module) {
            return module.modulePath();
        }
        return reference.location()
                        .map(Path::of)
                        .orElse(null);
    }

    private static String processorModulePath(ModuleResolution resolution, List<String> authoritativeRoots, RepositoryPaths repositoryPaths,
            SourceModulePaths sourceModules, String pathSeparator)
            throws IOException {
        var processorRoots = new LinkedHashSet<String>();
        for (String root : authoritativeRoots) {
            resolution.observableModules()
                      .find(root)
                      .filter(SourceModuleReference.class::isInstance)
                      .map(SourceModuleReference.class::cast)
                      .map(SourceModuleReference::sourceModule)
                      .map(SourceModule::processors)
                      .ifPresent(processorRoots::addAll);
        }
        if (processorRoots.isEmpty()) {
            return "";
        }

        for (String processor : processorRoots) {
            var module = resolution.configuration()
                                   .findModule(processor)
                                   .orElseThrow(() -> new IllegalArgumentException("@processWith module is not in the resolved graph: " + processor));
            boolean providesProcessor = module.reference().descriptor().provides().stream()
                    .anyMatch(service -> service.service().equals("javax.annotation.processing.Processor"));
            if (!providesProcessor) {
                throw new IllegalArgumentException("@processWith module does not provide " + "javax.annotation.processing.Processor: " + processor);
            }
        }

        var closure = dependencyClosure(resolution.configuration(), processorRoots).stream()
                .map(ResolvedModule::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        closure.removeAll(authoritativeRoots);

        var paths = new LinkedHashSet<Path>();
        for (String name : closure.stream()
                .filter(candidate -> ModuleFinder.ofSystem()
                        .find(candidate)
                        .isEmpty())
                .sorted()
                .toList()) {
            var path = processorModulePath(name, resolution, repositoryPaths, sourceModules);
            if (path != null) {
                paths.add(path);
            }
        }
        return paths.stream()
                .map(Path::toString)
                .collect(Collectors.joining(pathSeparator));
    }

    private static Path processorModulePath(String moduleName, ModuleResolution resolution, RepositoryPaths repositoryPaths,
            SourceModulePaths sourceModules)
            throws IOException {
        var selected = repositoryPaths.targetJars().get(moduleName);
        if (selected != null) {
            return selected;
        }
        var reference = resolution.observableModules()
                .find(moduleName)
                .orElse(null);
        if (reference == null) {
            return null;
        }
        if (reference instanceof SourceModuleReference source) {
            if (sourceModules == null) {
                throw new IllegalStateException("Source processor has no module path: " + moduleName);
            }
            return sourceModules.modulePath(moduleName, source, false);
        }
        if (reference instanceof ModulePathReference module) {
            return module.modulePath();
        }
        return reference.location()
                        .map(Path::of)
                        .orElseThrow(() -> new IllegalArgumentException("Processor module has no binary location: " + moduleName));
    }

    private static String singleRoot(Options options, String operation) {
        if (options.rootNames().size() != 1) {
            if (options.rootNames().isEmpty()) {
                throw new IllegalArgumentException(operation + " requires exactly one root module, found none");
            }
            var message = new StringBuilder(operation).append(" requires exactly one root module; select one with -m:");
            options.rootNames().stream()
                    .sorted()
                    .forEach(root -> message.append("\n  -m ").append(root));
            throw new IllegalArgumentException(message.toString());
        }
        return options.rootNames()
                      .iterator()
                      .next();
    }

    record ModuleMainClass(String moduleName, String className) {}

    private static ModuleMainClass inferredMainClass(ModuleFinder candidates) {
        List<ModuleMainClass> mainClasses = candidates.findAll().stream()
                .map(ModuleReference::descriptor)
                .filter(descriptor -> descriptor.mainClass().isPresent())
                .map(descriptor -> new ModuleMainClass(descriptor.name(),
                        descriptor.mainClass().orElseThrow()))
                .sorted(Comparator.comparing(ModuleMainClass::moduleName))
                .toList();
        if (mainClasses.size() == 1) {
            return mainClasses.getFirst();
        }
        if (mainClasses.isEmpty()) {
            throw new IllegalArgumentException("No observable module has a main class");
        }
        var message = new StringBuilder("Multiple observable modules declare a main class; select one with -m:");
        mainClasses.forEach(mainClass -> message.append("\n  -m ").append(mainClass.moduleName()));
        throw new IllegalArgumentException(message.toString());
    }

    private static ModuleMainClass mainClass(Options options, ModuleResolution resolution) {
        Optional<ModuleMainClass> mainClass = optionalMainClass(options, resolution);
        if (mainClass.isPresent()) {
            return mainClass.get();
        }
        List<String> candidates = options.mainCandidates();
        if (candidates.size() == 1) {
            throw new IllegalArgumentException("Module " + candidates.getFirst() + " has no main class");
        }
        throw new IllegalArgumentException("No root module has a main class: " + candidates);
    }

    private static Optional<ModuleMainClass> optionalMainClass(Options options, ModuleResolution resolution) {
        List<ModuleMainClass> mainClasses = options.mainCandidates().stream()
                .map(root -> optionalMainClass(root, resolution))
                .flatMap(Optional::stream)
                .toList();
        if (mainClasses.size() > 1) {
            var message = new StringBuilder("Multiple root modules declare a main class; select one with -m:");
            mainClasses.stream()
                    .map(ModuleMainClass::moduleName)
                    .sorted()
                    .forEach(root -> message.append("\n  -m ").append(root));
            throw new IllegalArgumentException(message.toString());
        }
        return mainClasses.stream().findFirst();
    }

    private static Optional<ModuleMainClass> optionalMainClass(String root, ModuleResolution resolution) {
        var reference = resolution.observableModules()
                .find(root)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + root));
        return reference.descriptor()
                        .mainClass()
                        .map(mainClass -> new ModuleMainClass(root, mainClass));
    }

    private static void appendAccessArguments(StringBuilder arguments, Set<String> resolveOptions, ResolvedAccessOptions resolved) {
        if (resolveOptions.contains("release") && resolved.release != null) {
            appendArgument(arguments, "--release");
            appendArgument(arguments, resolved.release.toString());
        }
        if (resolveOptions.contains("multi-release")) {
            appendArgument(arguments, "--multi-release");
            appendArgument(arguments,
                    resolved.release != null ? resolved.release.toString() : Integer.toString(Runtime.version().feature()));
        }
        if (resolveOptions.contains("enable-preview") && resolved.enablePreview) {
            appendArgument(arguments, "--enable-preview");
        }
        if (resolveOptions.contains("enable-native-access") && !resolved.enableNativeAccess.isEmpty()) {
            appendArgument(arguments, "--enable-native-access");
            appendArgument(arguments, String.join(",", resolved.enableNativeAccess));
        }
        if (resolveOptions.contains("enable-final-field-mutation") && !resolved.enableFinalFieldMutation.isEmpty()) {
            appendArgument(arguments, "--enable-final-field-mutation");
            appendArgument(arguments, String.join(",", resolved.enableFinalFieldMutation));
        }
        if (resolveOptions.contains("add-opens")) {
            for (String opens : resolved.addOpens) {
                appendArgument(arguments, "--add-opens");
                appendArgument(arguments, opens);
            }
        }
        if (resolveOptions.contains("add-exports")) {
            for (String exports : resolved.addExports) {
                appendArgument(arguments, "--add-exports");
                appendArgument(arguments, exports);
            }
        }
    }

    private static ResolvedAccessOptions collectAccessOptions(
            Configuration config,
            Set<String> excludedModules,
            List<String> authoritativeRoots,
            List<String> configurationRoots,
            Set<String> sourceModules,
            ModuleFinder finder,
            boolean includeRelease)
            throws IOException {
        Integer release = null;
        if (includeRelease) {
            var releases = sourceModules.stream()
                    .filter(name -> !excludedModules.contains(name))
                    .map(finder::find)
                    .flatMap(Optional::stream)
                    .map(SourceModuleReference.class::cast)
                    .map(SourceModuleReference::sourceModule)
                    .map(SourceModule::release)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (releases.size() > 1) {
                throw new IllegalArgumentException("Selected source modules require different releases: " + releases);
            }
            release = releases.isEmpty() ? null : releases.getFirst();
            if (release == null && configurationRoots.isEmpty()) {
                release = Runtime.version().feature();
            }
        }

        boolean enablePreview = false;
        for (var rm : config.modules().stream()
                .sorted(Comparator.comparing(ResolvedModule::name))
                .toList()) {
            if (excludedModules.contains(rm.name())) {
                continue;
            }
            if (rm.reference() instanceof SourceModuleReference source) {
                if (source.sourceModule().preview()) {
                    enablePreview = true;
                }
            } else {
                var metadata = ModuleRuntimeMetadata.read(rm.reference());
                if (metadata.isPresent() && metadata.get().preview()) {
                    enablePreview = true;
                }
            }
        }
        if (includeRelease && enablePreview && release == null) {
            release = Runtime.version().feature();
        }

        var enableNativeAccess = new LinkedHashSet<String>();
        var enableFinalFieldMutation = new LinkedHashSet<String>();
        var addOpens = new LinkedHashSet<String>();
        var addExports = new LinkedHashSet<String>();
        for (String root : authoritativeRoots.stream()
                .sorted()
                .toList()) {
            var reference = config.findModule(root)
                                  .orElseThrow()
                                  .reference();
            if (!(reference instanceof SourceModuleReference source)) {
                continue;
            }
            var options = source.sourceModule().runtimeAccessOptions();
            validateAccessOptions(config, root, options);
            addAccessOptions(options, enableNativeAccess, enableFinalFieldMutation, addOpens, addExports);
        }
        var systemModules = ModuleFinder.ofSystem();
        for (var module : dependencyClosure(config, configurationRoots).stream()
                .filter(candidate -> !excludedModules.contains(candidate.name()))
                .toList()) {
            var systemReference = systemModules.find(module.name()).orElse(null);
            if (systemReference == null) {
                continue;
            }
            var reference = module.reference();
            if (reference instanceof SourceModuleReference) {
                continue;
            }
            var options = ModuleRuntimeAccess.read(reference).orElse(null);
            if (options == null) {
                continue;
            }
            addAccessOptions(options, enableNativeAccess, enableFinalFieldMutation, addOpens, addExports);
        }
        return new ResolvedAccessOptions(release, enablePreview, List.copyOf(enableNativeAccess), List.copyOf(enableFinalFieldMutation),
                List.copyOf(addOpens), List.copyOf(addExports));
    }

    private static void addAccessOptions(ModuleRuntimeAccessOptions options, Set<String> enableNativeAccess, Set<String> enableFinalFieldMutation,
            Set<String> addOpens, Set<String> addExports) {
        enableNativeAccess.addAll(options.enableNativeAccess());
        enableFinalFieldMutation.addAll(options.enableFinalFieldMutation());
        options.addOpens().forEach(value -> addOpens.add(value.toFlagValue()));
        options.addExports().forEach(value -> addExports.add(value.toFlagValue()));
    }

    private static List<ResolvedModule> dependencyClosure(Configuration config, Collection<String> roots) {
        var resolved = new LinkedHashMap<String, ResolvedModule>();
        var queue = new ArrayDeque<ResolvedModule>();
        roots.stream()
                .sorted()
                .map(config::findModule)
                .flatMap(Optional::stream)
                .forEach(queue::addLast);
        while (!queue.isEmpty()) {
            var module = queue.removeFirst();
            if (resolved.putIfAbsent(module.name(), module) != null) {
                continue;
            }
            module.reads().stream()
                    .sorted(Comparator.comparing(ResolvedModule::name))
                    .forEach(queue::addLast);
        }
        return List.copyOf(resolved.values());
    }

    private static void validateAccessOptions(Configuration config, String authority, ModuleRuntimeAccessOptions options) {
        options.enableNativeAccess().forEach(module -> requireResolvedModule(config, authority, "@enableNativeAccess", module));
        options.enableFinalFieldMutation().forEach(module -> requireResolvedModule(config, authority, "@enableFinalFieldMutation", module));
        options.addExports().forEach(access -> validatePackageAccess(config, authority, "@addExports", access));
        options.addOpens().forEach(access -> validatePackageAccess(config, authority, "@addOpens", access));
    }

    private static void validateRuntimeAccessRequirements(Configuration config, Set<String> excludedModules, ResolvedAccessOptions authorizedSourceOptions,
            List<String> suppliedArguments)
            throws IOException {
        var required = ModuleRuntimeAccessOptions.newBuilder();
        for (var resolved : config.modules().stream()
                .filter(module -> !excludedModules.contains(module.name()))
                .sorted(Comparator.comparing(ResolvedModule::name))
                .toList()) {
            if (resolved.reference() instanceof SourceModuleReference source) {
                ModuleRuntimeAccess.add(required, source.sourceModule()
                        .runtimeAccessOptions());
            } else {
                ModuleRuntimeAccess.read(resolved.reference()).ifPresent(options -> ModuleRuntimeAccess.add(required, options));
            }
        }

        var authorized = ModuleRuntimeAccessOptions.newBuilder();
        authorizedSourceOptions.enableNativeAccess().forEach(authorized::enableNativeAccess);
        authorizedSourceOptions.enableFinalFieldMutation().forEach(authorized::enableFinalFieldMutation);
        addPackageAccess(authorizedSourceOptions.addExports(), authorized, true);
        addPackageAccess(authorizedSourceOptions.addOpens(), authorized, false);
        ModuleRuntimeAccess.add(authorized, ModuleRuntimeAccess.parseArguments(suppliedArguments));
        ModuleRuntimeAccess.checkRequirements(required.build(), authorized.build());
    }

    private static void addPackageAccess(List<String> arguments, Builder builder, boolean exports) {
        var option = exports ? "--add-exports" : "--add-opens";
        for (var argument : arguments) {
            var parsed = ModuleRuntimeAccess.parseArguments(List.of(option, argument));
            ModuleRuntimeAccess.add(builder, parsed);
        }
    }

    private static void validatePackageAccess(Configuration config, String authority, String option,
            PackageAccess access) {
        var source = requireResolvedModule(config, authority, option, access.sourceModule());
        if (!source.reference()
                   .descriptor()
                   .packages()
                   .contains(access.packageName())) {
            throw new IllegalArgumentException(option
                    + " in "
                    + authority
                    + " names package "
                    + access.packageName()
                    + " which is not in module "
                    + access.sourceModule());
        }
        requireResolvedModule(config, authority, option, access.targetModule());
    }

    private static ResolvedModule requireResolvedModule(Configuration config, String authority, String option,
            String module) {
        return config.findModule(module).orElseThrow(() -> new IllegalArgumentException(option + " in " + authority + " names module " + module + " which is not resolved for the runtime invocation"));
    }

    private static void traceResolution(PrintWriter out, Configuration config, Collection<String> rootNames) {
        var bootModules = ModuleLayer.boot().configuration().modules().stream()
                .map(ResolvedModule::name)
                .collect(Collectors.toSet());
        var printed = new HashSet<String>();

        for (String root : rootNames) {
            var rm = config.findModule(root).orElse(null);
            if (rm == null) {
                continue;
            }
            out.println("[root " + nameAndInfo(rm) + "]");
            printed.add(root);
            traceRequires(out, rm, config, bootModules, printed);
        }
        out.flush();
    }

    private static void traceRequires(PrintWriter out, ResolvedModule module, Configuration config,
            Set<String> bootModules, Set<String> printed) {
        var descriptor = module.reference().descriptor();
        for (var req : descriptor.requires()) {
            String name = req.name();
            if (bootModules.contains(name)) {
                continue;
            }
            var dep = config.findModule(name).orElse(null);
            if (dep == null) {
                continue;
            }
            out.println("["
                    + descriptor.name()
                    + " requires "
                    + nameAndInfo(dep)
                    + "]");
            if (printed.add(name)) {
                traceRequires(out, dep, config, bootModules, printed);
            }
        }
    }

    private static String nameAndInfo(ResolvedModule rm) {
        var descriptor = rm.reference().descriptor();
        var sb = new StringBuilder(descriptor.name());
        descriptor.version().ifPresent(v -> sb.append('@').append(v));
        rm.reference()
          .location()
          .ifPresent(uri -> sb.append(' ').append(uri));
        if (descriptor.isAutomatic()) {
            sb.append(" automatic");
        }
        return sb.toString();
    }

    public static String moduleSourcePathArgument(String moduleName, Path sourcePath) {
        return moduleName + "=" + sourcePath.toAbsolutePath().normalize();
    }

    private static void appendArgument(StringBuilder output, String argument) {
        boolean quote = argument.isEmpty() || argument.startsWith("#") || argument.chars().anyMatch(Character::isWhitespace);
        if (!quote) {
            output.append(argument).append('\n');
            return;
        }
        output.append('"');
        for (int i = 0; i < argument.length(); i++) {
            char character = argument.charAt(i);
            if (character == '\\' || character == '"') {
                output.append('\\');
            }
            output.append(character);
        }
        output.append('"').append('\n');
    }

    private static int serve(PrintWriter out, PrintWriter err, String[] args) {
        String listen = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                printServeHelp(out);
                return 0;
            } else if (arg.equals("--listen")) {
                if (++i >= args.length) {
                    err.println("jig: --listen requires an address");
                    return 2;
                }
                listen = args[i];
            } else if (arg.startsWith("--listen=")) {
                listen = arg.substring("--listen=".length());
            } else if (listen == null) {
                listen = arg;
            } else {
                err.println("jig: unexpected serve argument: " + arg);
                return 2;
            }
        }

        InetSocketAddress address;
        try {
            address = parseListenAddress(listen);
        } catch (IllegalArgumentException e) {
            err.println("jig: " + e.getMessage());
            return 2;
        }

        try (var session = ModuleRepositorySession.create();
             var server = startModuleProxy(session, address, err)) {
            out.println(server.uri());
            out.flush();
            var shutdownHook = new Thread(server::close, "jig-proxy-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                server.await();
                return 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 130;
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already running the hook.
                }
            }
        } catch (IOException e) {
            err.println("jig: failed to start module proxy: " + e.getMessage());
            return 1;
        }
    }

    static RunningServer startModuleProxy(ModuleRepositorySession session, InetSocketAddress address) throws IOException {
        return startModuleProxy(session, address, null);
    }

    public static RunningServer startModuleProxy(ModuleRepositorySession session, InetSocketAddress address, PrintWriter traceOutput) throws IOException {
        var transporter = session.newModuleTransporter();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var server = HttpServer.create(address, 0);
        var handler = new TransporterHttpHandler(transporter);
        var requestSequence = new AtomicLong();
        server.createContext("/",
                exchange -> {
                    exchange.getResponseHeaders().set("Server", "jig");
                    if (traceOutput == null) {
                        handler.handle(exchange);
                        return;
                    }
                    long request = requestSequence.incrementAndGet();
                    Trace.withOutput(traceOutput,
                            () -> Trace.withContext("proxy " + request, () -> {
                                Trace.trace("request %s %s", exchange.getRequestMethod(), exchange.getRequestURI());
                                try {
                                    handler.handle(exchange);
                                } catch (IOException | RuntimeException error) {
                                    Trace.trace("request failed: %s", error.getMessage());
                                    throw error;
                                } finally {
                                    int status = exchange.getResponseCode();
                                    if (status != -1) {
                                        Trace.trace("response %d", status);
                                    }
                                }
                            }));
                });
        server.setExecutor(executor);
        server.start();
        return new RunningServer(server, executor, transporter);
    }

    public static InetSocketAddress parseListenAddress(String value) {
        if (value == null || value.isBlank()) {
            return new InetSocketAddress("127.0.0.1", 0);
        }
        String host = "127.0.0.1";
        String portValue = value;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0 || end + 1 >= value.length() || value.charAt(end + 1) != ':') {
                throw new IllegalArgumentException("invalid listen address: " + value);
            }
            host = value.substring(1, end);
            portValue = value.substring(end + 2);
        } else {
            int colon = value.lastIndexOf(':');
            if (colon >= 0) {
                host = colon == 0 ? "0.0.0.0" : value.substring(0, colon);
                portValue = value.substring(colon + 1);
            }
        }
        int port;
        try {
            port = Integer.parseInt(portValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid listen port: " + portValue, e);
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("listen port out of range: " + port);
        }
        return new InetSocketAddress(host, port);
    }

    public static final class RunningServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final Transporter transporter;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        RunningServer(HttpServer server, ExecutorService executor, Transporter transporter) {
            this.server = server;
            this.executor = executor;
            this.transporter = transporter;
        }

        public URI uri() {
            InetSocketAddress address = server.getAddress();
            String host = address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
            try {
                return new URI("http", null, host, address.getPort(), "/",
                        null, null);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid server address", e);
            }
        }

        void await() throws InterruptedException {
            stopped.await();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                server.stop(0);
                transporter.close();
                executor.close();
                stopped.countDown();
            }
        }
    }

    private static void printServeHelp(PrintWriter out) {
        out.println("Usage: jig maven serve [--listen <host:port>]");
        out.println();
        out.println("Starts the module proxy.");
        out.println("Defaults to 127.0.0.1 with a random available port.");
        out.println("Prints the repository URL to standard output and traces proxy");
        out.println("and backend activity to standard error.");
    }

    public static void main(String[] args) {
        var jig = new Jig();
        var out = new PrintWriter(System.out, true);
        var err = new PrintWriter(System.err, true);
        System.exit(jig.run(out, err, args));
    }

    private static void printHelp(PrintWriter out) {
        out.println("Usage: jig [options]");
        out.println("       jig maven <operation> [options]");
        out.println("       jig maven serve [--listen <host:port>]");
        out.println();
        out.println("Resolves a Java module graph from requires directives.");
        out.println("By default, validates the graph and exits.");
        out.println();
        out.println("  -p, --module-path <path>");
        out.println("                  Where to find precompiled modules.");
        out.println("  -m, --module <module>");
        out.println("                  Root module to resolve.");
        out.println("  --add-modules <module>[,<module>...]");
        out.println("                  Additional root modules already available locally.");
        out.println("  --add-requires <module>@<version>");
        out.println("                  Add a versioned external module as a root.");
        out.println("                  May be specified multiple times.");
        out.println("  --module-source-path <path>");
        out.println("                  Where to find module source trees.");
        out.println("                  Supports javac forms:");
        out.println("                    module=path  (module-specific)");
        out.println("                    src/*/java   (module-pattern)");
        out.println("                  May be specified multiple times.");
        out.println("  --module-version <version>");
        out.println("                  Version for compiled modules and generated POMs.");
        out.println("  --target-platform <target-platform|CURRENT>");
        out.println("                  Target platform for modules published with a classifier.");
        out.println("                  CURRENT uses the current JVM's OS and architecture.");
        out.println("  --prefer-jmod   Prefer JMODs on generated module paths, falling back to JARs.");
        out.println("                  JMOD lookup defaults to the current platform.");
        out.println("  --generate-module-poms <root>");
        out.println("                  Generate module-info.pom files and a Maven reactor.");
        out.println("  --generate-consumer-pom <directory>");
        out.println("                  Generate a consumer POM for each selected source module");
        out.println("                  in a new directory.");
        out.println("  --update-module-hashes");
        out.println("                  Write module-info.hash for each selected source module.");
        out.println("  --verify-module-hashes");
        out.println("                  Verify module-info.hash for each selected source module.");
        out.println("  -r, --resolve-options <option-spec>[,<option-spec>...]");
        out.println("                  Resolve the requested standard options to stdout.");
        out.println("  -w, --write-argfile <path>");
        out.println("                  Write generated options as a Java argument file.");
        out.println("                  Requires --resolve-options.");
        out.println("  --recompile     Compile source modules without reusing prior output.");
        out.println("  --no-compile-diagnostics");
        out.println("                  Do not report source compilation diagnostics.");
        out.println("                  Diagnostics are still retained.");
        out.println("  --validate-runtime-access");
        out.println("                  Validate runtime access requirements and authorization.");
        out.println("  --lookup-module <url>");
        out.println("                  Print the locatable module name for a supported URL.");
        out.println("  --list-module-versions <module>");
        out.println("                  List available versions for a module.");
        out.println("  --verbose       Show resolution and compiler tracing.");
        out.println("  -h, --help      Print this help message.");
        out.println("  --version       Print version information.");
        out.println();
        out.println("Resolve options:");
        out.println();
        out.println("  Paths           module-path, processor-module-path, upgrade-module-path,");
        out.println("                  module-source-path, source-path, patch-module");
        out.println("  Modules         module=single, module=list, module=main, module=roots,");
        out.println("                  add-modules, describe-module");
        out.println("  Compilation     module-version, release, enable-preview");
        out.println("  Packaging       main-class, multi-release");
        out.println("  Access          enable-native-access, enable-final-field-mutation,");
        out.println("                  add-opens, add-exports");
        out.println();
        out.println("module=single emits one module, module=list emits a comma-delimited list,");
        out.println("module=main emits module/main-class, and module=roots emits one module");
        out.println("with --module or multiple modules with --add-modules. module is an alias");
        out.println("for module=single.");
    }

    public static class Options {
        public enum ModuleForm {
            SINGLE,
            LIST,
            MAIN,
            ROOTS
        }

        public Path[] modulePath = new Path[0];
        public final List<String> moduleSourcePaths = new ArrayList<>();
        final List<String> roots = new ArrayList<>();
        final List<String> addedModules = new ArrayList<>();
        public final Map<String, String> addedRequires = new LinkedHashMap<>();
        public String moduleVersion;
        String targetPlatform;
        boolean preferJmod;
        public Path modulePomRoot;
        public Path consumerPomDirectory;
        IntegrityMode integrityMode = IntegrityMode.NONE;
        public Set<String> resolveOptions;
        public ModuleForm moduleForm = ModuleForm.SINGLE;
        public Path argumentFile;
        public boolean recompile;
        public boolean emitCompileDiagnostics = true;
        public boolean validateRuntimeAccess;
        public final List<String> suppliedArguments = new ArrayList<>();
        public boolean verbose;
        public ModuleOrigin moduleLookup;
        String listModuleVersions;
        boolean help;

        public Collection<String> rootNames() {
            var names = roots.stream().collect(Collectors.toCollection(LinkedHashSet::new));
            names.addAll(addedModules);
            names.addAll(addedRequires.keySet());
            return names;
        }

        List<String> resolutionRoots() {
            var names = new ArrayList<>(roots);
            names.addAll(addedModules);
            return List.copyOf(names);
        }

        List<String> mainCandidates() {
            var names = new ArrayList<>(roots);
            names.addAll(addedRequires.keySet());
            return List.copyOf(names);
        }

        boolean hasResolveOption(String option) {
            return resolveOptions != null && resolveOptions.contains(option);
        }

        boolean requiresModuleGraph() {
            if (resolveOptions == null || !GRAPH_INDEPENDENT_RESOLVE_OPTIONS.containsAll(resolveOptions)) {
                return true;
            }
            return modulePath.length > 0
                    || !moduleSourcePaths.isEmpty()
                    || targetPlatform != null
                    || preferJmod
                    || modulePomRoot != null
                    || consumerPomDirectory != null
                    || integrityMode != IntegrityMode.NONE
                    || recompile
                    || !emitCompileDiagnostics
                    || validateRuntimeAccess;
        }

        boolean hasResolutionOptions() {
            return modulePath.length > 0
                    || !moduleSourcePaths.isEmpty()
                    || !rootNames().isEmpty()
                    || moduleVersion != null
                    || targetPlatform != null
                    || preferJmod
                    || modulePomRoot != null
                    || consumerPomDirectory != null
                    || integrityMode != IntegrityMode.NONE
                    || resolveOptions != null
                    || recompile
                    || !emitCompileDiagnostics
                    || validateRuntimeAccess;
        }

        public static Options parse(String[] args) {
            return JigCommandLine.instance().parse(args);
        }

        void setIntegrityMode(IntegrityMode mode) {
            if (integrityMode != IntegrityMode.NONE && integrityMode != mode) {
                throw new IllegalArgumentException("--update-module-hashes and --verify-module-hashes " + "are mutually exclusive");
            }
            integrityMode = mode;
        }

        void addRequire(String value) {
            int separator = value.lastIndexOf('@');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("--add-requires requires <module>@<version>");
            }
            String moduleName = value.substring(0, separator);
            try {
                ModuleDescriptor.newModule(moduleName);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid module name: " + moduleName, e);
            }
            String version = parseModuleVersion(value.substring(separator + 1));
            String previous = addedRequires.putIfAbsent(moduleName, version);
            if (previous != null && !previous.equals(version)) {
                throw new IllegalArgumentException("conflicting versions for " + moduleName + ": " + previous + " and " + version);
            }
        }

        void setModuleLookup(String value) {
            if (moduleLookup != null) {
                throw new IllegalArgumentException("--lookup-module may only be specified once");
            }
            moduleLookup = ModuleOrigin.parse(value);
        }

        void setResolveOptions(String value) {
            if (resolveOptions != null) {
                throw new IllegalArgumentException("--resolve-options may only be specified once");
            }
            var parsed = new LinkedHashSet<String>();
            ModuleForm parsedModuleForm = null;
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
                    if (parsedModuleForm != null && parsedModuleForm != form) {
                        throw new IllegalArgumentException("module option has conflicting forms");
                    }
                    parsedModuleForm = form;
                    parsed.add("module");
                } else {
                    parsed.add(option);
                }
            }
            var unknown = parsed.stream()
                    .filter(option -> !RESOLVE_OPTIONS.contains(option))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("unknown resolve options: " + unknown);
            }
            if (parsedModuleForm == ModuleForm.ROOTS && !parsed.contains("add-modules")) {
                throw new IllegalArgumentException("module=roots requires add-modules");
            }
            resolveOptions = Collections.unmodifiableSet(parsed);
            if (parsedModuleForm != null) {
                moduleForm = parsedModuleForm;
            }
        }

        void setArgumentFile(String value) {
            if (argumentFile != null) {
                throw new IllegalArgumentException("--write-argfile may only be specified once");
            }
            argumentFile = Path.of(value);
        }

        static String parseModuleVersion(String value) {
            try {
                Version.parse(value);
                return value;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid module version: " + value, e);
            }
        }

        public String targetClassifier() {
            if (targetPlatform == null) {
                return null;
            }
            return classifierForPlatform(targetPlatform);
        }

        public String jmodTargetClassifier() {
            if (targetPlatform != null) {
                return targetClassifier();
            }
            return classifierForPlatform(currentPlatform(System.getProperty("os.name"), System.getProperty("os.arch")));
        }

        private static String classifierForPlatform(String platform) {
            return switch (platform) {
                case "macos-aarch64" -> "osx-aarch_64";
                case "macos-x86_64", "macos-amd64" -> "osx-x86_64";
                case "linux-aarch64" -> "linux-aarch_64";
                case "linux-x86_64", "linux-amd64" -> "linux-x86_64";
                case "windows-aarch64" -> "windows-aarch_64";
                case "windows-x86_64", "windows-amd64" -> "windows-x86_64";
                default -> throw new IllegalArgumentException("unsupported target platform: " + platform);
            };
        }

        void setTargetPlatform(String value) {
            if (targetPlatform != null) {
                throw new IllegalArgumentException("--target-platform may only be specified once");
            }
            targetPlatform = value.equals("CURRENT") ? currentPlatform(System.getProperty("os.name"), System.getProperty("os.arch")) : value;
            targetClassifier();
        }

        public static String currentPlatform(String osName, String osArch) {
            String normalizedOsName = osName.toLowerCase(Locale.ROOT);
            String os;
            if (normalizedOsName.equals("mac os x") || normalizedOsName.equals("darwin")) {
                os = "macos";
            } else if (normalizedOsName.equals("linux")) {
                os = "linux";
            } else if (normalizedOsName.startsWith("windows")) {
                os = "windows";
            } else {
                os = null;
            }
            String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
                case "aarch64", "arm64" -> "aarch64";
                case "amd64", "x86_64" -> "x86_64";
                default -> null;
            };
            if (os == null || arch == null) {
                throw new IllegalArgumentException("unsupported current platform: os.name=" + osName + ", os.arch=" + osArch);
            }
            return os + "-" + arch;
        }

        static Path[] parsePaths(String pathSpec) {
            return Arrays.stream(pathSpec.split(System.getProperty("path.separator")))
                    .map(Path::of)
                    .toArray(Path[]::new);
        }
    }
}
