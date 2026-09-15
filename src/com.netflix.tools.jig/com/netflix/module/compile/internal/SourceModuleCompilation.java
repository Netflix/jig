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

package com.netflix.module.compile.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes.Name;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.ModuleRuntimeAccessOptions.PackageAccess;
import com.netflix.module.compile.ModulePathEntry;
import com.netflix.module.compile.internal.SourceCompilation.SourceContent;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

public final class SourceModuleCompilation {
    public record Paths(List<Path> modulePath, List<Path> upgradeModulePath, Map<String, List<Path>> patchModules,
                        Map<String, List<Path>> moduleSourcePath) {
        public Paths(List<Path> modulePath, List<Path> upgradeModulePath, Map<String, List<Path>> patchModules) {
            this(modulePath, upgradeModulePath, patchModules, Map.of());
        }

        public Paths {
            modulePath = normalizedPaths(modulePath, "modulePath");
            upgradeModulePath = normalizedPaths(upgradeModulePath, "upgradeModulePath");
            patchModules = normalizedPathMap(patchModules, "patch module", "patch module paths");
            moduleSourcePath = normalizedPathMap(moduleSourcePath, "source module", "module source paths");
        }
    }

    @FunctionalInterface
    public interface PathResolver {
        Paths resolve() throws IOException;
    }

    public record Configuration(
            String moduleName,
            Integer release,
            boolean preview,
            String moduleVersion,
            String mainClass,
            ModuleRuntimeAccessOptions runtimeAccess,
            Paths paths,
            List<ModulePathEntry> modulePathEntries,
            PathResolver pathResolver) {
        public Configuration(
                String moduleName,
                Integer release,
                boolean preview,
                String moduleVersion,
                ModuleRuntimeAccessOptions runtimeAccess,
                Paths paths,
                List<ModulePathEntry> modulePathEntries,
                PathResolver pathResolver) {
            this(moduleName, release, preview, moduleVersion, null, runtimeAccess,
                    paths, modulePathEntries, pathResolver);
        }

        public Configuration(
                String moduleName,
                Integer release,
                boolean preview,
                String moduleVersion,
                ModuleRuntimeAccessOptions runtimeAccess,
                List<Path> modulePath,
                List<Path> upgradeModulePath,
                Map<String, List<Path>> patchModules,
                List<ModulePathEntry> modulePathEntries) {
            this(moduleName, release, preview, moduleVersion, null, runtimeAccess,
                    new Paths(modulePath, upgradeModulePath, patchModules), modulePathEntries, null);
        }

        public Configuration {
            moduleName = Objects.requireNonNull(moduleName, "moduleName");
            if (!SourceVersion.isName(moduleName)) {
                throw new IllegalArgumentException("Invalid module name: " + moduleName);
            }
            if (release != null && release <= 0) {
                throw new IllegalArgumentException("Compilation release must be positive: " + release);
            }
            if (mainClass != null && !SourceVersion.isName(mainClass)) {
                throw new IllegalArgumentException("Invalid main class: " + mainClass);
            }
            runtimeAccess = Objects.requireNonNull(runtimeAccess, "runtimeAccess");
            paths = Objects.requireNonNull(paths, "paths");
            modulePathEntries = Objects.requireNonNull(modulePathEntries, "modulePathEntries").stream()
                    .sorted()
                    .toList();
            requireUniqueLocations(modulePathEntries);
        }

        Paths resolvedPaths() throws IOException {
            return pathResolver == null ? paths : pathResolver.resolve();
        }
    }

    private static void requireUniqueLocations(List<ModulePathEntry> entries) {
        var locations = new HashSet<Entry<StandardLocation, String>>();
        for (var entry : entries) {
            var location = Map.entry(entry.location(), entry.moduleName());
            if (!locations.add(location)) {
                throw new IllegalArgumentException("Duplicate module-path entry: " + location);
            }
        }
    }

    private static List<Path> normalizedPaths(List<Path> paths, String name) {
        return Objects.requireNonNull(paths, name).stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    private static Map<String, List<Path>> normalizedPathMap(Map<String, List<Path>> paths, String name,
            String pathsName) {
        var normalized = new TreeMap<String, List<Path>>();
        Objects.requireNonNull(paths, name + "s").forEach((module, values) -> normalized.put(Objects.requireNonNull(module, name + " name"), normalizedPaths(values, pathsName)));
        return Collections.unmodifiableNavigableMap(normalized);
    }

    public record SystemImage(Path path, long size, long modifiedSeconds,
            int modifiedNanos, String fileKey) {
        public SystemImage {
            path = Objects.requireNonNull(path, "path")
                    .toAbsolutePath()
                    .normalize();
            if (size < 0) {
                throw new IllegalArgumentException("Negative system image size");
            }
            if (modifiedNanos < 0 || modifiedNanos > 999_999_999) {
                throw new IllegalArgumentException("Invalid modification nanoseconds: " + modifiedNanos);
            }
            fileKey = Objects.requireNonNull(fileKey, "fileKey");
        }
    }

    public record State(Map<SourcePath, SourceCompilation> sources, List<ModulePathEntry> modulePathEntries, Map<String, ContentHash> resources,
                        ContentHash moduleInfoOptionsHash, SystemImage systemImage, List<CompilationDiagnostic> diagnostics) {
        public State(Map<SourcePath, SourceCompilation> sources, List<ModulePathEntry> modulePathEntries, Map<String, ContentHash> resources,
                     ContentHash moduleInfoOptionsHash, SystemImage systemImage) {
            this(sources, modulePathEntries, resources, moduleInfoOptionsHash, systemImage,
                    List.of());
        }

        public State(Map<SourcePath, SourceCompilation> sources, List<ModulePathEntry> modulePathEntries, Map<String, ContentHash> resources,
                     SystemImage systemImage) {
            this(sources, modulePathEntries, resources, null, systemImage);
        }

        public State(Map<SourcePath, SourceCompilation> sources, List<ModulePathEntry> modulePathEntries, Map<String, ContentHash> resources) {
            this(sources, modulePathEntries, resources, null, null);
        }

        public State {
            sources = Collections.unmodifiableNavigableMap(new TreeMap<>(Objects.requireNonNull(sources, "sources")));
            modulePathEntries = Objects.requireNonNull(modulePathEntries, "modulePathEntries").stream()
                    .sorted()
                    .toList();
            requireUniqueLocations(modulePathEntries);
            resources = Collections.unmodifiableNavigableMap(new TreeMap<>(Objects.requireNonNull(resources, "resources")));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            for (var diagnostic : diagnostics) {
                if (!sources.keySet().containsAll(diagnostic.compilationSources())) {
                    throw new IllegalArgumentException("Diagnostic refers to an unknown source: " + diagnostic.compilationSources());
                }
            }
        }

        public static State empty() {
            return new State(Map.of(), List.of(), Map.of(),
                    null, null, List.of());
        }
    }

    public record Result(Set<SourcePath> compiledSources, int rounds, Map<String, byte[]> classes,
                         Set<String> removedClasses, State state) {
        public Result {
            compiledSources = Collections.unmodifiableNavigableSet(new TreeSet<>(compiledSources));
            classes = Collections.unmodifiableNavigableMap(new TreeMap<>(classes));
            removedClasses = Collections.unmodifiableNavigableSet(new TreeSet<>(removedClasses));
            state = Objects.requireNonNull(state, "state");
        }

        @Override
        public Map<String, byte[]> classes() {
            var copy = new TreeMap<String, byte[]>();
            classes.forEach((path, content) -> copy.put(path, content.clone()));
            return Collections.unmodifiableNavigableMap(copy);
        }

        Map<String, byte[]> classContents() {
            return classes;
        }
    }

    private record Output(String path, SourcePath source, byte[] content) {}

    private record Round(Map<String, Output> outputs, Map<SourcePath, ContentHash> sourceHashes, Map<SourcePath, ContentHash> declarationHashes,
                         Map<String, ContentHash> systemClasses, List<StoredDiagnostic> diagnostics) {}

    private SourceModuleCompilation() {}

    public static Result compile(Path sourceRoot, Path previousOutput, Configuration configuration) throws IOException {
        return compile(sourceRoot, previousOutput, null, previousOutput, configuration, null,
                State.empty());
    }

    public static Result compile(Path sourceRoot, Path previousOutput, Path compilationModule,
            Configuration configuration)
            throws IOException {
        return compile(sourceRoot, previousOutput, null, compilationModule, configuration, null,
                State.empty());
    }

    public static Result compile(Path sourceRoot, Path previousOutput, Path previousPatch,
            Path compilationModule, Configuration configuration)
            throws IOException {
        return compile(sourceRoot, previousOutput, previousPatch, compilationModule, configuration, null,
                State.empty());
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path compilationModule,
            Configuration configuration,
            Map<SourcePath, SourceContent> sourceContents,
            State previousState)
            throws IOException {
        return compile(sourceRoot, previousOutput, previousPatch, compilationModule, configuration, sourceContents,
                previousState, null);
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path compilationModule,
            Configuration configuration,
            Map<SourcePath, SourceContent> sourceContents,
            State previousState,
            DiagnosticListener<? super JavaFileObject> diagnosticListener)
            throws IOException {
        return compile(
                sourceRoot,
                previousOutput,
                previousPatch,
                compilationModule,
                configuration,
                sourceContents,
                previousState,
                diagnosticListener,
                null);
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path compilationModule,
            Configuration configuration,
            Map<SourcePath, SourceContent> sourceContents,
            State previousState,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Writer verboseOutput)
            throws IOException {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot")
                .toAbsolutePath()
                .normalize();
        previousOutput = previousOutput == null ? null : previousOutput.toAbsolutePath().normalize();
        previousPatch = previousPatch == null ? null : previousPatch.toAbsolutePath().normalize();
        compilationModule = compilationModule == null ? null : compilationModule.toAbsolutePath().normalize();
        configuration = Objects.requireNonNull(configuration, "configuration");
        previousState = Objects.requireNonNull(previousState, "previousState");
        var sources = sourceContents == null ? SourceModuleOutput.readSources(sourceRoot) : sourceContents;
        var pending = new TreeSet<SourcePath>();
        for (var entry : sources.entrySet()) {
            var previous = previousState.sources().get(entry.getKey());
            if (previous == null || !previous.sourceHash().equals(entry.getValue()
                    .hash())) {
                pending.add(entry.getKey());
            }
        }
        var currentCompilations = new TreeMap<>(previousState.sources());
        var compiledSources = new TreeSet<SourcePath>();
        var patch = new TreeMap<String, byte[]>();
        var removed = new TreeSet<String>();
        for (var entry : List.copyOf(currentCompilations.entrySet())) {
            if (!sources.containsKey(entry.getKey())) {
                entry.getValue().generatedClasses().stream()
                        .map(SourceModuleCompilation::classPath)
                        .forEach(removed::add);
                currentCompilations.remove(entry.getKey());
            }
        }
        var completeCompilation = previousOutput != null && previousState.sources().isEmpty();
        if (completeCompilation || !removed.isEmpty()) {
            pending.addAll(sources.keySet());
        } else if (systemImageChanged(previousState.systemImage())) {
            var currentSystemClasses = new HashMap<String, ContentHash>();
            for (var compilation : currentCompilations.values()) {
                if (systemClassesChanged(compilation.systemClasses(), currentSystemClasses)) {
                    pending.add(compilation.source());
                }
            }
        }
        var currentDiagnostics = new ArrayList<CompilationDiagnostic>();
        for (var diagnostic : CompilationDiagnostic.diagnostics(previousState)) {
            if (currentCompilations.keySet().containsAll(diagnostic.compilationSources())) {
                currentDiagnostics.add(diagnostic);
            }
        }
        var modulePathEntries = configuration.modulePathEntries();
        var modulePathChanged = !ModulePathCompilation.entries(modulePathEntries).equals(ModulePathCompilation.entries(previousState.modulePathEntries()));
        if (!completeCompilation && modulePathChanged) {
            pending.addAll(sources.keySet());
        }
        var moduleInfoOptionsHash = moduleInfoOptionsHash(configuration);
        if (!completeCompilation && previousOutput != null && !moduleInfoOptionsHash.equals(previousState.moduleInfoOptionsHash())) {
            var moduleInfoSource = new SourcePath("module-info.java");
            if (sources.containsKey(moduleInfoSource)) {
                pending.add(moduleInfoSource);
            }
        }
        if (pending.isEmpty()) {
            var result = new Result(
                    compiledSources,
                    0,
                    patch,
                    removed,
                    state(currentCompilations, modulePathEntries, previousState.resources(), moduleInfoOptionsHash, currentDiagnostics));
            reportDiagnostics(currentDiagnostics, sourceRoot, diagnosticListener);
            return result;
        }

        var work = Files.createTempDirectory("source-module-compilation-");
        var patchDirectory = Files.createDirectories(work.resolve("patch"));
        var compilationBase = compilationModule;
        var compilationPatch = previousPatch;
        if (!completeCompilation
                && previousOutput != null
                && previousOutput.equals(compilationModule)
                && !removed.isEmpty()) {
            compilationBase = materializeModule(previousOutput, previousPatch, patch, removed, work.resolve("module"));
            compilationPatch = null;
        }
        var rounds = 0;
        var replayedDiagnostics = Collections.newSetFromMap(new IdentityHashMap<CompilationDiagnostic, Boolean>());
        try {
            while (!pending.isEmpty()) {
                rounds++;
                var roundSources = Collections.unmodifiableNavigableSet(new TreeSet<>(pending));
                pending.clear();
                var replayCompilations = new TreeMap<>(currentCompilations);
                replayCompilations.keySet().removeAll(roundSources);
                replayCompilations.keySet().removeAll(compiledSources);
                if (diagnosticListener != null) {
                    var replayedThisRound = new ArrayList<CompilationDiagnostic>();
                    for (var diagnostic : currentDiagnostics) {
                        if (intersects(diagnostic.compilationSources(), replayCompilations.keySet()) && replayedDiagnostics.add(diagnostic)) {
                            replayedThisRound.add(diagnostic);
                        }
                    }
                    reportDiagnostics(replayedThisRound, sourceRoot, diagnosticListener);
                }
                var round = compileRound(sourceRoot, sources, compilationBase, compilationPatch, patchDirectory, roundSources,
                        configuration, diagnosticListener, verboseOutput);
                compiledSources.addAll(roundSources);

                var preparedOutputs = new TreeMap<String, Output>();
                for (var output : round.outputs().values()) {
                    var content = output.content();
                    if (output.path().equals("module-info.class")) {
                        content = ModuleRuntimeAccess.write(content,
                                ModuleRuntimeAccess.forModule(configuration.runtimeAccess(), configuration.moduleName()));
                    }
                    preparedOutputs.put(output.path(),
                            new Output(output.path(), output.source(), content));
                }
                var generatedBySource = new TreeMap<SourcePath, Set<String>>();
                preparedOutputs.values().forEach(output -> generatedBySource.computeIfAbsent(output.source(), _ -> new TreeSet<>()).add(binaryName(output.path())));
                var compilationsBySource = new TreeMap<SourcePath, SourceCompilation>();
                var declarationsChanged = false;
                for (var source : roundSources) {
                    var declarationHash = round.declarationHashes().get(source);
                    if (declarationHash == null) {
                        throw new IOException("Compiler did not parse source: " + source);
                    }
                    var compilation = new SourceCompilation(source, round.sourceHashes().get(source),
                            declarationHash, generatedBySource.getOrDefault(source, Set.of()), round.systemClasses());
                    var previousCompilation = currentCompilations.get(source);
                    if (previousCompilation == null || !previousCompilation.declarationHash().equals(compilation.declarationHash())) {
                        declarationsChanged = true;
                    }
                    compilationsBySource.put(source, compilation);
                }

                for (var source : roundSources) {
                    var previousCompilation = currentCompilations.get(source);
                    if (previousCompilation == null) {
                        continue;
                    }
                    var generatedClasses = compilationsBySource.get(source).generatedClasses();
                    previousCompilation.generatedClasses().stream()
                            .filter(name -> !generatedClasses.contains(name))
                            .map(SourceModuleCompilation::classPath)
                            .forEach(removed::add);
                }
                currentCompilations.putAll(compilationsBySource);
                for (var entry : preparedOutputs.entrySet()) {
                    patch.put(entry.getKey(),
                            entry.getValue().content());
                    removed.remove(entry.getKey());
                }

                currentDiagnostics.removeIf(diagnostic -> intersects(diagnostic.compilationSources(), roundSources));
                for (var diagnostic : round.diagnostics()) {
                    currentDiagnostics.add(new CompilationDiagnostic(diagnostic.source() == null ? roundSources : Set.of(diagnostic.source()), diagnostic));
                }

                if (declarationsChanged) {
                    sources.keySet().stream()
                            .filter(source -> !roundSources.contains(source))
                            .forEach(pending::add);
                }
                if (!pending.isEmpty()) {
                    if (!removed.isEmpty() && previousOutput != null && previousOutput.equals(compilationModule)) {
                        compilationBase = materializeModule(previousOutput, previousPatch, patch, removed, work.resolve("module"));
                        compilationPatch = null;
                        deleteTree(patchDirectory);
                        Files.createDirectories(patchDirectory);
                    } else {
                        writeClasses(patchDirectory, patch, removed);
                    }
                }
            }
            var moduleInfo = patch.get("module-info.class");
            if (moduleInfo != null && configuration.mainClass() != null) {
                patch.put("module-info.class", ModuleMainClass.write(moduleInfo, configuration.mainClass()));
            }
            return new Result(
                    compiledSources,
                    rounds,
                    patch,
                    removed,
                    state(currentCompilations, modulePathEntries, previousState.resources(), moduleInfoOptionsHash, currentDiagnostics));
        } finally {
            deleteTree(work);
        }
    }

    public static Path write(Path previousOutput, Result result, Path target) throws IOException {
        return write(previousOutput, null, result, Map.of(),
                Set.of(), target);
    }

    public static Path write(Path previousOutput, Result result, Map<String, Path> resources,
            Set<String> removedResources, Path target)
            throws IOException {
        return write(previousOutput, null, result, resources, removedResources, target);
    }

    public static Path write(Path previousOutput, Path previousPatch, Result result,
            Map<String, Path> resources, Set<String> removedResources, Path target)
            throws IOException {
        previousOutput = previousOutput == null ? null : previousOutput.toAbsolutePath().normalize();
        previousPatch = previousPatch == null ? null : previousPatch.toAbsolutePath().normalize();
        result = Objects.requireNonNull(result, "result");
        resources = Collections.unmodifiableNavigableMap(new TreeMap<>(Objects.requireNonNull(resources, "resources")));
        removedResources = Collections.unmodifiableNavigableSet(new TreeSet<>(Objects.requireNonNull(removedResources, "removedResources")));
        target = Objects.requireNonNull(target, "target")
                .toAbsolutePath()
                .normalize();
        var parent = target.getParent();
        if (parent == null) {
            throw new IOException("Module output has no parent: " + target);
        }
        Files.createDirectories(parent);
        var temporary = Files.createTempDirectory(parent, "." + target.getFileName() + "-");
        try {
            var previousFiles = new TreeMap<String, Path>();
            collectFiles(previousOutput, previousFiles);
            collectFiles(previousPatch, previousFiles);
            for (var entry : previousFiles.entrySet()) {
                var relative = entry.getKey();
                if (result.removedClasses().contains(relative)
                        || removedResources.contains(relative)
                        || result.classContents().containsKey(relative)
                        || resources.containsKey(relative)) {
                    continue;
                }
                var destination = temporary.resolve(relative);
                Files.createDirectories(destination.getParent());
                copy(entry.getValue(), destination);
            }
            for (var entry : result.classContents().entrySet()) {
                if (result.removedClasses().contains(entry.getKey())) {
                    continue;
                }
                var destination = temporary.resolve(entry.getKey());
                Files.createDirectories(destination.getParent());
                Files.write(destination, entry.getValue());
            }
            for (var entry : resources.entrySet()) {
                if (removedResources.contains(entry.getKey())) {
                    continue;
                }
                if (result.classContents().containsKey(entry.getKey())) {
                    throw new IOException("Resource replaces a class: " + entry.getKey());
                }
                var destination = temporary.resolve(entry.getKey());
                Files.createDirectories(destination.getParent());
                copy(entry.getValue(), destination);
            }
            makeReadOnly(temporary);
            if (Files.exists(target)) {
                throw new IOException("Module output already exists: " + target);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
            return target;
        } finally {
            deleteTree(temporary);
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void makeReadOnly(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile)
                                 .sorted(Collections.reverseOrder())
                                 .toList()) {
                try {
                    var permissions = Files.getPosixFilePermissions(path);
                    permissions.remove(PosixFilePermission.OWNER_WRITE);
                    permissions.remove(PosixFilePermission.GROUP_WRITE);
                    permissions.remove(PosixFilePermission.OTHERS_WRITE);
                    Files.setPosixFilePermissions(path, permissions);
                } catch (UnsupportedOperationException e) {
                    path.toFile().setWritable(false, false);
                }
            }
        }
    }

    private static Round compileRound(
            Path sourceRoot,
            Map<SourcePath, SourceContent> sourceContents,
            Path compilationModule,
            Path previousPatch,
            Path patchDirectory,
            Set<SourcePath> sources,
            Configuration configuration,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Writer verboseOutput)
            throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("System Java compiler is not available");
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        DiagnosticListener<JavaFileObject> diagnosticReporter = diagnostic -> {
            diagnostics.report(diagnostic);
            if (diagnosticListener != null) {
                diagnosticListener.report(diagnostic);
            }
        };
        var generated = new TreeMap<String, Output>();
        var sourceHashes = new TreeMap<SourcePath, ContentHash>();
        var declarationHashes = new TreeMap<SourcePath, ContentHash>();
        var systemClasses = new TreeMap<String, ContentHash>();
        var work = Files.createTempDirectory("javac-round-");
        var classes = Files.createDirectories(work.resolve("classes"));
        try (var standard = compiler.getStandardFileManager(diagnosticReporter, null, StandardCharsets.UTF_8)) {
            standard.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            var configuredPaths = configuration.resolvedPaths();
            var sourcePaths = new HashMap<URI, SourcePath>();
            var sourceFiles = new ArrayList<JavaFileObject>();
            var manager = new OutputFileManager(standard, sourceRoot, sourceContents.keySet(), sourcePaths, generated,
                    systemClasses);
            for (var source : sources) {
                if (!sourceContents.containsKey(source)) {
                    throw new IOException("Source content is missing: " + source);
                }
                var path = sourceRoot.resolve(source.value());
                var delegates = standard.getJavaFileObjectsFromPaths(List.of(path));
                var delegate = delegates.iterator().next();
                var file = new ForwardingJavaFileObject<JavaFileObject>(delegate) {
                    private byte[] bytes;

                    private byte[] bytes() throws IOException {
                        if (bytes == null) {
                            try (var input = super.openInputStream()) {
                                bytes = input.readAllBytes();
                            }
                            sourceHashes.put(source, ContentHash.sha256(bytes));
                        }
                        return bytes;
                    }

                    @Override
                    public long getLastModified() {
                        // Explicit units always win over retained classes.
                        return Long.MAX_VALUE;
                    }

                    @Override
                    public InputStream openInputStream() throws IOException {
                        return new ByteArrayInputStream(bytes());
                    }

                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                        return new String(bytes(), StandardCharsets.UTF_8);
                    }
                };
                manager.register(file, delegate);
                sourceFiles.add(file);
                sourcePaths.put(file.toUri(), source);
            }
            var options = new ArrayList<String>();
            options.add("-proc:none");
            options.add("-implicit:none");
            options.add("-Xlint:all");
            if (verboseOutput != null) {
                options.add("-verbose");
            }
            if (configuration.release() != null) {
                options.add("--release");
                options.add(configuration.release()
                        .toString());
            }
            if (configuration.preview()) {
                if (configuration.release() == null) {
                    options.add("--source");
                    options.add(Integer.toString(Runtime.version()
                            .feature()));
                }
                options.add("--enable-preview");
            }
            if (configuration.moduleVersion() != null) {
                options.add("--module-version");
                options.add(configuration.moduleVersion());
            }
            var sourceModuleName = configuration.moduleName();
            var modulePath = new ArrayList<Path>();
            if (compilationModule != null) {
                modulePath.add(compilationModule);
            }
            for (var moduleName : configuredPaths.moduleSourcePath().keySet()) {
                modulePath.add(observableModule(work, moduleName));
            }
            modulePath.addAll(configuredPaths.modulePath());
            addPathOption(options, "--module-path", modulePath);
            addPathOption(options, "--upgrade-module-path", configuredPaths.upgradeModulePath());
            var patchModules = new TreeMap<String, List<Path>>();
            configuredPaths.patchModules().forEach((name, paths) -> patchModules.put(name, new ArrayList<>(paths)));
            if (compilationModule != null) {
                var currentPatch = patchModules.computeIfAbsent(sourceModuleName, _ -> new ArrayList<>());
                currentPatch.add(sourceRoot);
                currentPatch.add(patchDirectory);
                if (previousPatch != null) {
                    currentPatch.add(previousPatch);
                }
                currentPatch.add(compilationModule);
            }
            patchModules.forEach((name, paths) -> {
                options.add("--patch-module");
                options.add(name + "=" + String.join(File.pathSeparator,
                        paths.stream()
                                .map(Path::toString)
                                .toList()));
            });
            configuration.runtimeAccess().addExports().stream()
                    .filter(access -> access.targetModule().equals(configuration.moduleName()))
                    .forEach(access -> {
                        options.add("--add-exports");
                        options.add(access.toFlagValue());
                    });
            var task = (JavacTask) compiler.getTask(verboseOutput, manager, diagnosticReporter, options, null, sourceFiles);
            task.addTaskListener(new DeclarationListener(sourcePaths, declarationHashes));
            final boolean successful;
            try {
                successful = task.call();
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            if (!successful) {
                var message = new StringBuilder("Compilation failed:");
                diagnostics.getDiagnostics().forEach(diagnostic -> message.append(System.lineSeparator()).append(diagnostic));
                throw new IOException(message.toString());
            }
            return new Round(
                    Map.copyOf(generated),
                    Map.copyOf(sourceHashes),
                    Map.copyOf(declarationHashes),
                    Map.copyOf(systemClasses),
                    diagnostics.getDiagnostics().stream()
                            .map(diagnostic -> StoredDiagnostic.from(diagnostic, sourcePaths))
                            .toList());
        } finally {
            deleteTree(work);
        }
    }

    private static Path observableModule(Path work, String moduleName) throws IOException {
        var directory = Files.createDirectories(work.resolve("observable-modules"));
        var archive = directory.resolve(moduleName + ".jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
            // The module is observable only so javac can validate qualified
            // exports and opens. Its source remains on the project source path.
        }
        return archive;
    }

    private static void addPathOption(List<String> options, String option, List<Path> paths) {
        if (paths.isEmpty()) {
            return;
        }
        options.add(option);
        options.add(String.join(File.pathSeparator,
                paths.stream()
                        .map(Path::toString)
                        .toList()));
    }

    private static ContentHash moduleInfoOptionsHash(Configuration configuration) {
        var options = ModuleRuntimeAccess.forModule(configuration.runtimeAccess(), configuration.moduleName());
        var values = new ArrayList<String>();
        values.add("version=" + Objects.toString(configuration.moduleVersion(), ""));
        values.add("main=" + Objects.toString(configuration.mainClass(), ""));
        options.enableNativeAccess().stream()
                .sorted()
                .map(value -> "native=" + value)
                .forEach(values::add);
        options.enableFinalFieldMutation().stream()
                .sorted()
                .map(value -> "final=" + value)
                .forEach(values::add);
        options.addExports().stream()
                .map(PackageAccess::toFlagValue)
                .sorted()
                .map(value -> "exports=" + value)
                .forEach(values::add);
        options.addOpens().stream()
                .map(PackageAccess::toFlagValue)
                .sorted()
                .map(value -> "opens=" + value)
                .forEach(values::add);
        return ContentHash.sha256(String.join("\n", values)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean systemImageChanged(SystemImage previous) throws IOException {
        return previous != null && !previous.equals(systemImage());
    }

    private static boolean systemClassesChanged(Map<String, ContentHash> expected, Map<String, ContentHash> current) {
        for (var entry : expected.entrySet()) {
            var hash = current.get(entry.getKey());
            if (hash == null) {
                try {
                    hash = ContentHash.sha256(Files.readAllBytes(Path.of(URI.create(entry.getKey()))));
                    current.put(entry.getKey(), hash);
                } catch (IOException | IllegalArgumentException e) {
                    return true;
                }
            }
            if (!hash.equals(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    public static SystemImage systemImage() throws IOException {
        var path = Path.of(System.getProperty("java.home"), "lib", "modules")
                .toAbsolutePath()
                .normalize();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        var modified = attributes.lastModifiedTime().toInstant();
        return new SystemImage(path, attributes.size(), modified.getEpochSecond(),
                modified.getNano(), String.valueOf(attributes.fileKey()));
    }

    private static boolean intersects(Set<SourcePath> first, Set<SourcePath> second) {
        for (var source : first) {
            if (second.contains(source)) {
                return true;
            }
        }
        return false;
    }

    private static void reportDiagnostics(List<CompilationDiagnostic> diagnostics, Path sourceRoot, DiagnosticListener<? super JavaFileObject> listener) {
        if (listener == null) {
            return;
        }
        for (var index = 0; index < diagnostics.size(); index++) {
            listener.report(diagnostics.get(index)
                    .diagnostic()
                    .at(sourceRoot, index == 0));
        }
    }

    private static State state(Map<SourcePath, SourceCompilation> compilations, List<ModulePathEntry> modulePathEntries, Map<String, ContentHash> resources,
            ContentHash moduleInfoOptionsHash, List<CompilationDiagnostic> diagnostics)
            throws IOException {
        var usesSystemImage = compilations.values().stream()
                .anyMatch(compilation -> !compilation.systemClasses().isEmpty());
        return CompilationDiagnostic.state(compilations, modulePathEntries, resources, moduleInfoOptionsHash,
                usesSystemImage ? systemImage() : null, diagnostics);
    }

    private static String classPath(String binaryName) {
        return binaryName.replace('.', '/') + JavaFileObject.Kind.CLASS.extension;
    }

    private static String binaryName(String path) {
        if (!path.endsWith(JavaFileObject.Kind.CLASS.extension)) {
            throw new IllegalArgumentException("Not a class output: " + path);
        }
        return path.substring(0, path.length() - JavaFileObject.Kind.CLASS.extension.length()).replace('/', '.');
    }

    private static void writeClasses(Path target, Map<String, byte[]> classes, Set<String> removed) throws IOException {
        deleteTree(target);
        Files.createDirectories(target);
        for (var entry : classes.entrySet()) {
            if (removed.contains(entry.getKey())) {
                continue;
            }
            var destination = target.resolve(entry.getKey());
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
    }

    private static Path materializeModule(Path base, Path previousPatch, Map<String, byte[]> patch,
            Set<String> removed, Path target)
            throws IOException {
        deleteTree(target);
        Files.createDirectories(target);
        var files = new TreeMap<String, Path>();
        collectFiles(base, files);
        collectFiles(previousPatch, files);
        for (var entry : files.entrySet()) {
            var relative = entry.getKey();
            if (removed.contains(relative) || patch.containsKey(relative)) {
                continue;
            }
            var destination = target.resolve(relative);
            Files.createDirectories(destination.getParent());
            copy(entry.getValue(), destination);
        }
        for (var entry : patch.entrySet()) {
            if (removed.contains(entry.getKey())) {
                continue;
            }
            var destination = target.resolve(entry.getKey());
            Files.createDirectories(destination.getParent());
            Files.write(destination, entry.getValue());
        }
        return target;
    }

    private static Path effectiveFile(Path base, Path patch, String relative) {
        if (patch != null) {
            var patched = patch.resolve(relative);
            if (Files.isRegularFile(patched)) {
                return patched;
            }
        }
        if (base != null) {
            var original = base.resolve(relative);
            if (Files.isRegularFile(original)) {
                return original;
            }
        }
        return null;
    }

    private static void collectFiles(Path root, Map<String, Path> files) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var file : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(file)
                              .toString()
                              .replace(File.separatorChar, '/'),
                        file);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.walk(root)) {
            for (var file : files.sorted(Collections.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private static final class OutputFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Path sourceRoot;
        private final Set<URI> visibleSources;
        private final Map<URI, SourcePath> sourcePaths;
        private final Map<String, Output> outputs;
        private final Map<String, ContentHash> systemClasses;
        private final Map<JavaFileObject, JavaFileObject> inputs = new HashMap<>();
        private final Map<URI, JavaFileObject> selectedInputs = new HashMap<>();
        private final Map<URI, JavaFileObject> compilerInputs = new HashMap<>();

        private OutputFileManager(StandardJavaFileManager delegate, Path sourceRoot, Set<SourcePath> sources,
                Map<URI, SourcePath> sourcePaths, Map<String, Output> outputs, Map<String, ContentHash> systemClasses) {
            super(delegate);
            this.sourceRoot = sourceRoot;
            visibleSources = sources.stream()
                    .map(source -> sourceRoot.resolve(source.value()).toUri())
                    .collect(Collectors.toUnmodifiableSet());
            this.sourcePaths = sourcePaths;
            this.outputs = outputs;
            this.systemClasses = systemClasses;
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
            var file = super.getJavaFileForInput(location, className, kind);
            return visible(file) ? compilerInput(selectedInput(file)) : null;
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                boolean recurse)
                throws IOException {
            var files = new ArrayList<JavaFileObject>();
            for (var file : super.list(location, packageName, kinds, recurse)) {
                if (visible(file)) {
                    files.add(compilerInput(selectedInput(file)));
                }
            }
            return files;
        }

        private boolean visible(JavaFileObject file) {
            if (file == null || file.getKind() != JavaFileObject.Kind.SOURCE || !"file".equalsIgnoreCase(file.toUri()
                    .getScheme())) {
                return true;
            }
            var path = Path.of(file.toUri())
                    .toAbsolutePath()
                    .normalize();
            return !path.startsWith(sourceRoot) || visibleSources.contains(file.toUri());
        }

        private JavaFileObject selectedInput(JavaFileObject file) {
            return file == null ? null : selectedInputs.getOrDefault(file.toUri(), file);
        }

        private JavaFileObject compilerInput(JavaFileObject file) {
            if (file == null || file.getKind() != JavaFileObject.Kind.CLASS) {
                return file;
            }
            return compilerInputs.computeIfAbsent(
                    file.toUri(),
                    uri -> {
                        var system = "jrt".equalsIgnoreCase(uri.getScheme());
                        var input = new ForwardingJavaFileObject<JavaFileObject>(file) {
                            private byte[] bytes;

                            @Override
                            public long getLastModified() {
                                // Retained and prior-round classes replace unselected
                                // source files during javac's normal newer-file lookup.
                                return Long.MAX_VALUE - 1;
                            }

                            @Override
                            public InputStream openInputStream() throws IOException {
                                if (!system) {
                                    return super.openInputStream();
                                }
                                if (bytes == null) {
                                    try (var stream = super.openInputStream()) {
                                        bytes = stream.readAllBytes();
                                    }
                                    systemClasses.put(uri.toString(), ContentHash.sha256(bytes));
                                }
                                return new ByteArrayInputStream(bytes);
                            }
                        };
                        register(input, file);
                        return input;
                    });
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            return super.inferBinaryName(location, input(file));
        }

        @Override
        public boolean isSameFile(FileObject first, FileObject second) {
            return super.isSameFile(input(first), input(second));
        }

        @Override
        public boolean contains(Location location, FileObject file) throws IOException {
            return super.contains(location, input(file));
        }

        @Override
        public Location getLocationForModule(Location location, JavaFileObject file) throws IOException {
            return super.getLocationForModule(location, input(file));
        }

        private void register(JavaFileObject source, JavaFileObject input) {
            inputs.put(source, input);
            if (source.getKind() == JavaFileObject.Kind.SOURCE) {
                selectedInputs.put(source.toUri(), source);
            }
        }

        private JavaFileObject input(JavaFileObject file) {
            return inputs.getOrDefault(file, file);
        }

        private FileObject input(FileObject file) {
            return file instanceof JavaFileObject javaFile ? input(javaFile) : file;
        }

        @Override
        public JavaFileObject getJavaFileForOutputForOriginatingFiles(Location location, String className, JavaFileObject.Kind kind,
                FileObject... originatingFiles)
                throws IOException {
            return getJavaFileForOutput(location, className, kind,
                    originatingFiles.length == 0 ? null : originatingFiles[0]);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                FileObject sibling)
                throws IOException {
            var source = sibling == null ? null : sourcePaths.get(sibling.toUri());
            if (source == null) {
                throw new IOException("Compiler output has no source: " + className);
            }
            var path = className.replace('.', '/') + kind.extension;
            return new SimpleJavaFileObject(URI.create("source-compilation:///" + path), kind) {
                @Override
                public OutputStream openOutputStream() {
                    return new ByteArrayOutputStream() {
                        @Override
                        public void close() throws IOException {
                            super.close();
                            var output = new Output(path, source, toByteArray());
                            if (outputs.putIfAbsent(path, output) != null) {
                                throw new IOException("Duplicate compiler output: " + path);
                            }
                        }
                    };
                }
            };
        }
    }

    private static final class DeclarationListener implements TaskListener {
        private final Map<URI, SourcePath> sourcePaths;
        private final Map<SourcePath, ContentHash> declarationHashes;

        private DeclarationListener(Map<URI, SourcePath> sourcePaths, Map<SourcePath, ContentHash> declarationHashes) {
            this.sourcePaths = sourcePaths;
            this.declarationHashes = declarationHashes;
        }

        @Override
        public void finished(TaskEvent event) {
            if (event.getKind() != TaskEvent.Kind.PARSE) {
                return;
            }
            var unit = event.getCompilationUnit();
            var source = sourcePaths.get(unit.getSourceFile()
                    .toUri());
            if (source != null) {
                declarationHashes.put(source, SourceDeclarationHash.of(unit));
            }
        }
    }
}
