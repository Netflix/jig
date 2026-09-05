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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

/** Compiles a module source directory into an immutable filesystem directory. */
public final class SourceModuleOutput {
    public record Inputs(
            Map<SourcePath, SourceCompilation.SourceContent> sources,
            Map<Integer, Map<SourcePath, SourceCompilation.SourceContent>> versionSources,
            List<Path> rootFiles,
            Map<Integer, List<Path>> versionFiles,
            boolean completeRoot,
            boolean multiRelease,
            ContentHash hash,
            SourceModuleManifest.Observations observations) {
        public Inputs {
            sources = Collections.unmodifiableNavigableMap(
                    new TreeMap<>(Objects.requireNonNull(sources, "sources")));
            var versionSourceContents = new TreeMap<Integer,
                    Map<SourcePath, SourceCompilation.SourceContent>>();
            Objects.requireNonNull(versionSources, "versionSources")
                    .forEach((version, contents) ->
                            versionSourceContents.put(version,
                                    Collections.unmodifiableNavigableMap(
                                            new TreeMap<>(contents))));
            versionSources = Collections.unmodifiableNavigableMap(
                    versionSourceContents);
            rootFiles = List.copyOf(rootFiles);
            var versions = new TreeMap<Integer, List<Path>>();
            versionFiles.forEach((version, files) ->
                    versions.put(version, List.copyOf(files)));
            versionFiles = Collections.unmodifiableNavigableMap(versions);
            hash = Objects.requireNonNull(hash, "hash");
            observations = Objects.requireNonNull(observations, "observations");
        }
    }

    public record Result(
            Path output,
            Path patch,
            int compilations,
            int parsedSources,
            SourceModuleCompilation.State state,
            ContentHash inputHash) {
        public Result(
                Path output,
                Path patch,
                int compilations,
                int parsedSources,
                SourceModuleCompilation.State state) {
            this(output, patch, compilations, parsedSources, state, null);
        }

        public Result {
            output = Objects.requireNonNull(output, "output")
                    .toAbsolutePath().normalize();
            patch = patch == null ? null : patch.toAbsolutePath().normalize();
            state = Objects.requireNonNull(state, "state");
        }
    }

    private record SourceFiles(
            List<Path> root,
            Map<Integer, List<Path>> versions,
            boolean completeRoot) {
    }

    private record ObservedFile(
            SourceModuleManifest.FileObservation observation) {
    }

    private SourceModuleOutput() {
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path target,
            int targetRelease,
            SourceModuleCompilation.Configuration configuration)
            throws IOException {
        return compile(sourceRoot, previousOutput, previousPatch, target,
                targetRelease, configuration, null, null,
                SourceModuleCompilation.State.empty(), null, null);
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path target,
            int targetRelease,
            SourceModuleCompilation.Configuration configuration,
            Map<SourcePath, SourceCompilation.SourceContent> sourceContents)
            throws IOException {
        return compile(sourceRoot, previousOutput, previousPatch, target,
                targetRelease, configuration, null, sourceContents,
                SourceModuleCompilation.State.empty(), null, null);
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path target,
            int targetRelease,
            SourceModuleCompilation.Configuration configuration,
            Inputs inputs,
            SourceModuleCompilation.State previousState) throws IOException {
        return compile(sourceRoot, previousOutput, previousPatch, target,
                targetRelease, configuration, inputs, previousState, null);
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path target,
            int targetRelease,
            SourceModuleCompilation.Configuration configuration,
            Inputs inputs,
            SourceModuleCompilation.State previousState,
            DiagnosticListener<? super JavaFileObject> diagnosticListener)
            throws IOException {
        return compile(sourceRoot, previousOutput, previousPatch, target,
                targetRelease, configuration, inputs, previousState,
                diagnosticListener, null);
    }

    public static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path target,
            int targetRelease,
            SourceModuleCompilation.Configuration configuration,
            Inputs inputs,
            SourceModuleCompilation.State previousState,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Writer verboseOutput)
            throws IOException {
        return compile(sourceRoot, previousOutput, previousPatch, target,
                targetRelease, configuration, inputs, inputs.sources(),
                previousState, diagnosticListener, verboseOutput);
    }

    private static Result compile(
            Path sourceRoot,
            Path previousOutput,
            Path previousPatch,
            Path target,
            int targetRelease,
            SourceModuleCompilation.Configuration configuration,
            Inputs inputs,
            Map<SourcePath, SourceCompilation.SourceContent> sourceContents,
            SourceModuleCompilation.State previousState,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            Writer verboseOutput)
            throws IOException {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot")
                .toAbsolutePath().normalize();
        previousOutput = previousOutput == null
                ? null : previousOutput.toAbsolutePath().normalize();
        previousPatch = previousPatch == null
                ? null : previousPatch.toAbsolutePath().normalize();
        target = Objects.requireNonNull(target, "target")
                .toAbsolutePath().normalize();
        configuration = Objects.requireNonNull(configuration, "configuration");
        if (targetRelease <= 0) {
            throw new IllegalArgumentException(
                    "Target release must be positive: " + targetRelease);
        }
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("Module source is not a directory: " + sourceRoot);
        }

        var files = inputs == null
                ? discover(sourceRoot)
                : new SourceFiles(
                        inputs.rootFiles(), inputs.versionFiles(),
                        inputs.completeRoot());
        if (sourceContents == null) {
            sourceContents = readSources(sourceRoot, files);
        }
        var multiRelease = inputs == null
                ? isMultiRelease(sourceRoot.resolve("META-INF/MANIFEST.MF"))
                : inputs.multiRelease();
        var selectedVersions = multiRelease
                ? files.versions().keySet().stream()
                        .filter(version -> version <= targetRelease)
                        .sorted()
                        .toList()
                : List.<Integer>of();
        var work = Files.createTempDirectory(
                target.getParent(), ".source-module-output-");
        var compilations = 0;
        var parsedSources = 0;
        try {
            var root = sourceRoot;
            var rootResources = resources(sourceRoot, files.root());
            var rootResourceHashes = resourceHashes(rootResources);
            var rootPrevious = multiRelease ? null : previousOutput;
            var rootPreviousPatch = multiRelease ? null : previousPatch;
            var rootCompilation = SourceModuleCompilation.compile(
                    root, rootPrevious, rootPreviousPatch,
                    rootPrevious, configuration, sourceContents,
                    multiRelease
                            ? SourceModuleCompilation.State.empty()
                            : previousState,
                    diagnosticListener,
                    verboseOutput);
            compilations += rootCompilation.rounds();
            parsedSources += rootCompilation.compiledSources().size();
            var rootState = CompilationDiagnostic.state(
                    rootCompilation.state().sources(),
                    rootCompilation.state().modulePathEntries(),
                    rootResourceHashes,
                    rootCompilation.state().moduleInfoOptionsHash(),
                    rootCompilation.state().systemImage(),
                    CompilationDiagnostic.diagnostics(rootCompilation.state()));
            var consumedInputs = new TreeMap<String, ContentHash>();
            rootState.sources().forEach((source, compilation) ->
                    consumedInputs.put(source.value(), compilation.sourceHash()));
            consumedInputs.putAll(rootResourceHashes);
            var removedResources = removedResources(
                    previousState.resources().keySet(), rootResources.keySet());
            if (selectedVersions.isEmpty()
                    && rootPrevious != null
                    && rootCompilation.classContents().isEmpty()
                    && rootCompilation.removedClasses().isEmpty()
                    && !resourcesChanged(
                            previousState.resources(),
                            rootResourceHashes,
                            removedResources)) {
                return new Result(
                        rootPrevious,
                        rootPreviousPatch,
                        compilations,
                        parsedSources,
                        rootState,
                        inputHash(consumedInputs));
            }
            if (selectedVersions.isEmpty()
                    && rootPrevious != null
                    && canPatch(rootPrevious, rootCompilation, removedResources)) {
                writePatch(
                        rootPrevious,
                        rootPreviousPatch,
                        rootCompilation,
                        rootResources,
                        removedResources,
                        target);
                if (isEmpty(target)) {
                    deleteTree(target);
                    return new Result(
                            rootPrevious,
                            null,
                            compilations,
                            parsedSources,
                            rootState,
                            inputHash(consumedInputs));
                }
                return new Result(
                        rootPrevious,
                        target,
                        compilations,
                        parsedSources,
                        rootState,
                        inputHash(consumedInputs));
            }
            var rootTarget = selectedVersions.isEmpty()
                    ? target : work.resolve("root-output");
            var current = SourceModuleCompilation.write(
                    rootPrevious,
                    rootPreviousPatch,
                    rootCompilation,
                    rootResources,
                    removedResources,
                    rootTarget);
            if (!selectedVersions.isEmpty()) {
                var versionSections = new TreeMap<Integer, Path>();
                for (var version : selectedVersions) {
                    var versionRoot = sourceRoot.resolve(
                            "META-INF/versions/" + version);
                    var versionConfiguration = forRelease(configuration, version);
                    var versionSources = inputs == null
                            ? null : inputs.versionSources()
                                    .getOrDefault(version, Map.of());
                    var compilation = SourceModuleCompilation.compile(
                            versionRoot, null, null, current,
                            versionConfiguration, versionSources,
                            SourceModuleCompilation.State.empty(),
                            diagnosticListener,
                            verboseOutput);
                    compilations += compilation.rounds();
                    parsedSources += compilation.compiledSources().size();
                    var versionPrefix = "META-INF/versions/" + version + "/";
                    compilation.state().sources().forEach((source, sourceCompilation) ->
                            consumedInputs.put(
                                    versionPrefix + source.value(),
                                    sourceCompilation.sourceHash()));
                    var versionResources = resources(
                            versionRoot,
                            files.versions().getOrDefault(version, List.of()));
                    resourceHashes(versionResources).forEach((name, hash) ->
                            consumedInputs.put(versionPrefix + name, hash));
                    var versionSection = SourceModuleCompilation.write(
                            null,
                            compilation,
                            versionResources,
                            Set.of(),
                            work.resolve("release-" + version));
                    versionSections.put(version, versionSection);

                    current = SourceModuleCompilation.write(
                            current,
                            compilation,
                            versionResources,
                            Set.of(),
                            work.resolve("version-" + version));
                }
                validateMultiRelease(
                        rootTarget, versionSections, work.resolve("validation.jar"));
                if (Files.exists(target)) {
                    throw new IOException("Module output already exists: " + target);
                }
                try {
                    Files.move(current, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(current, target);
                }
            }
            if (!Files.isRegularFile(target.resolve("module-info.class"))) {
                throw new IOException("Compiler did not produce module-info.class: "
                        + configuration.moduleName());
            }
            return new Result(
                    target,
                    null,
                    compilations,
                    parsedSources,
                    rootState,
                    inputHash(consumedInputs));
        } finally {
            deleteTree(work);
        }
    }

    private static void validateMultiRelease(
            Path root,
            Map<Integer, Path> versions,
            Path archive) throws IOException {
        var arguments = new ArrayList<String>();
        arguments.add("--create");
        arguments.add("-0");
        arguments.add("--file");
        arguments.add(archive.toString());
        arguments.add("-C");
        arguments.add(root.toString());
        arguments.add(".");
        for (var version : versions.entrySet()) {
            arguments.add("--release");
            arguments.add(version.getKey().toString());
            arguments.add("-C");
            arguments.add(version.getValue().toString());
            arguments.add(".");
        }

        var jar = java.util.spi.ToolProvider.findFirst("jar")
                .orElseThrow(() -> new IllegalStateException("The JDK jar tool is not installed"));
        var messages = new ByteArrayOutputStream();
        int result;
        try (var output = new PrintStream(messages)) {
            result = jar.run(output, output, arguments.toArray(String[]::new));
        }
        if (result != 0) {
            var message = messages.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
            throw new IOException(message.isEmpty()
                    ? "Invalid multi-release module"
                    : message);
        }
    }

    private static SourceModuleCompilation.Configuration forRelease(
            SourceModuleCompilation.Configuration configuration,
            int release) {
        return new SourceModuleCompilation.Configuration(
                configuration.moduleName(),
                release,
                false,
                configuration.moduleVersion(),
                configuration.runtimeAccess(),
                configuration.paths(),
                configuration.modulePathEntries(),
                configuration.pathResolver());
    }

    public static Map<SourcePath, SourceCompilation.SourceContent> readSources(
            Path sourceRoot) throws IOException {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("Module source is not a directory: " + sourceRoot);
        }
        return readSources(sourceRoot, discover(sourceRoot));
    }

    public static Inputs readInputs(Path sourceRoot, int targetRelease)
            throws IOException {
        return readInputs(sourceRoot, targetRelease, null);
    }

    public static Inputs readInputs(
            Path sourceRoot,
            int targetRelease,
            SourceModuleManifest.Observations previousObservations)
            throws IOException {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("Module source is not a directory: " + sourceRoot);
        }
        var files = discover(sourceRoot);
        var multiRelease = isMultiRelease(
                sourceRoot.resolve("META-INF/MANIFEST.MF"));
        var selected = new TreeSet<Path>(files.root());
        if (multiRelease) {
            files.versions().entrySet().stream()
                    .filter(entry -> entry.getKey() <= targetRelease)
                    .forEach(entry -> selected.addAll(entry.getValue()));
        }
        var observedFiles = new TreeSet<Path>(files.root());
        files.versions().values().forEach(observedFiles::addAll);

        var previous = previousObservations == null
                ? Map.<String, SourceModuleManifest.FileObservation>of()
                : previousObservations.files();
        var trustPreviousFileFacts = previousObservations != null
                && previousObservations.sourceRoot().equals(sourceRoot);
        var rootFiles = Set.copyOf(files.root());
        var sources = new TreeMap<SourcePath, SourceCompilation.SourceContent>();
        var versionSources = new TreeMap<Integer,
                Map<SourcePath, SourceCompilation.SourceContent>>();
        var observations = new TreeMap<String,
                SourceModuleManifest.FileObservation>();
        var digest = newDigest();
        for (var file : observedFiles) {
            var relative = relativePath(sourceRoot, file);
            if (file.getFileName().toString().equals("module-info.hash")) {
                continue;
            }
            var observed = observe(
                    file, previous.get(relative), trustPreviousFileFacts);
            observations.put(relative, observed.observation());
            var hash = observed.observation().hash();
            if (selected.contains(file)) {
                update(digest, relative);
                hash.update(digest);
            }
            if (selected.contains(file) && relative.endsWith(".java")) {
                var content = new SourceCompilation.SourceContent(hash);
                if (rootFiles.contains(file)) {
                    sources.put(new SourcePath(relative), content);
                } else {
                    for (var version : files.versions().entrySet()) {
                        var versionRoot = sourceRoot.resolve(
                                "META-INF/versions/" + version.getKey());
                        if (file.startsWith(versionRoot)) {
                            versionSources.computeIfAbsent(
                                            version.getKey(), _ -> new TreeMap<>())
                                    .put(new SourcePath(relativePath(
                                            versionRoot, file)), content);
                            break;
                        }
                    }
                }
            }
        }
        return new Inputs(
                sources,
                versionSources,
                files.root(),
                files.versions(),
                files.completeRoot(),
                multiRelease,
                ContentHash.takeOwnership(digest.digest()),
                new SourceModuleManifest.Observations(
                        sourceRoot, observations));
    }

    private static Map<SourcePath, SourceCompilation.SourceContent> readSources(
            Path sourceRoot,
            SourceFiles files) throws IOException {
        var sources = new TreeMap<SourcePath, SourceCompilation.SourceContent>();
        for (var file : files.root()) {
            if (!file.getFileName().toString().endsWith(".java")) continue;
            var bytes = Files.readAllBytes(file);
            sources.put(
                    new SourcePath(relativePath(sourceRoot, file)),
                    new SourceCompilation.SourceContent(ContentHash.sha256(bytes)));
        }
        return Collections.unmodifiableNavigableMap(sources);
    }

    private static ObservedFile observe(
            Path file,
            SourceModuleManifest.FileObservation previous,
            boolean trustPreviousFileFacts) throws IOException {
        for (var attempt = 0; attempt < 3; attempt++) {
            var before = Files.readAttributes(file, BasicFileAttributes.class);
            var previousMatches = trustPreviousFileFacts
                    && previous != null && previous.sameFile(
                    before.size(),
                    before.lastModifiedTime().toInstant().getEpochSecond(),
                    before.lastModifiedTime().toInstant().getNano(),
                    fileKey(before));
            if (previousMatches) return new ObservedFile(previous);

            var bytes = Files.readAllBytes(file);
            var after = Files.readAttributes(file, BasicFileAttributes.class);
            if (sameObservation(before, after)) {
                return new ObservedFile(
                        new SourceModuleManifest.FileObservation(
                                after.size(),
                                after.lastModifiedTime().toInstant().getEpochSecond(),
                                after.lastModifiedTime().toInstant().getNano(),
                                fileKey(after),
                                ContentHash.sha256(bytes)));
            }
        }
        throw new IOException("Source file changed while being observed: " + file);
    }

    private static boolean sameObservation(
            BasicFileAttributes first,
            BasicFileAttributes second) {
        return first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && fileKey(first).equals(fileKey(second));
    }

    private static String fileKey(BasicFileAttributes attributes) {
        return attributes.fileKey() == null
                ? "" : attributes.fileKey().toString();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        var bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static SourceFiles discover(Path sourceRoot) throws IOException {
        var versionRoot = sourceRoot.resolve("META-INF/versions");
        var versions = new TreeMap<Integer, Path>();
        if (Files.exists(versionRoot)) {
            if (!Files.isDirectory(versionRoot)) {
                throw new IOException(
                        "Multi-release versions path is not a directory: "
                                + versionRoot);
            }
            try (var entries = Files.list(versionRoot)) {
                for (var entry : entries.sorted().toList()) {
                    if (!Files.isDirectory(entry)) {
                        throw new IOException("Invalid multi-release entry: " + entry);
                    }
                    final int version;
                    try {
                        version = Integer.parseInt(entry.getFileName().toString());
                    } catch (NumberFormatException e) {
                        throw new IOException(
                                "Invalid multi-release version: " + entry, e);
                    }
                    if (version < 9) {
                        throw new IOException(
                                "Invalid multi-release version: " + version);
                    }
                    versions.put(version, entry);
                }
            }
        }

        final List<Path> regularFiles;
        try (var paths = Files.walk(sourceRoot)) {
            regularFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }
        var nestedModules = regularFiles.stream()
                .filter(path -> path.getFileName().toString()
                        .equals("module-info.java"))
                .map(Path::getParent)
                .filter(path -> !path.equals(sourceRoot))
                .filter(path -> versions.values().stream()
                        .noneMatch(path::startsWith))
                .toList();
        var root = regularFiles.stream()
                .filter(path -> versions.values().stream()
                        .noneMatch(path::startsWith))
                .filter(path -> nestedModules.stream()
                        .noneMatch(path::startsWith))
                .toList();
        var versionFiles = new TreeMap<Integer, List<Path>>();
        versions.forEach((version, directory) -> versionFiles.put(
                version,
                regularFiles.stream()
                        .filter(path -> path.startsWith(directory))
                        .toList()));
        return new SourceFiles(
                List.copyOf(root),
                Collections.unmodifiableNavigableMap(versionFiles),
                root.size() == regularFiles.size());
    }

    private static Map<String, Path> resources(
            Path sourceRoot,
            List<Path> files) {
        var resources = new TreeMap<String, Path>();
        for (var file : files) {
            var relative = relativePath(sourceRoot, file);
            if (relative.endsWith(".java")
                    || relative.equals("module-info.hash")) {
                continue;
            }
            resources.put(relative, file);
        }
        return Collections.unmodifiableNavigableMap(resources);
    }

    private static boolean canPatch(
            Path base,
            SourceModuleCompilation.Result compilation,
            Set<String> removedResources) throws IOException {
        for (var removed : compilation.removedClasses()) {
            if (Files.isRegularFile(base.resolve(removed))) return false;
        }
        for (var removed : removedResources) {
            if (Files.isRegularFile(base.resolve(removed))) return false;
        }
        var moduleInfo = compilation.classContents().get("module-info.class");
        return moduleInfo == null
                || java.util.Arrays.equals(
                        Files.readAllBytes(base.resolve("module-info.class")),
                        moduleInfo);
    }

    private static void writePatch(
            Path base,
            Path previousPatch,
            SourceModuleCompilation.Result compilation,
            Map<String, Path> resources,
            Set<String> removedResources,
            Path target) throws IOException {
        var parent = target.getParent();
        if (parent == null) throw new IOException("Module patch has no parent: " + target);
        Files.createDirectories(parent);
        var temporary = Files.createTempDirectory(parent, ".patch-");
        try {
            var previous = new TreeMap<String, Path>();
            collectFiles(previousPatch, previous);
            for (var entry : previous.entrySet()) {
                var name = entry.getKey();
                if (compilation.removedClasses().contains(name)
                        || removedResources.contains(name)
                        || compilation.classContents().containsKey(name)
                        || resources.containsKey(name)) {
                    continue;
                }
                copyIfDifferent(base, name, entry.getValue(), temporary);
            }
            for (var entry : compilation.classContents().entrySet()) {
                if (compilation.removedClasses().contains(entry.getKey())) continue;
                var baseFile = base.resolve(entry.getKey());
                if (Files.isRegularFile(baseFile)
                        && java.util.Arrays.equals(
                                Files.readAllBytes(baseFile), entry.getValue())) {
                    continue;
                }
                var destination = temporary.resolve(entry.getKey());
                Files.createDirectories(destination.getParent());
                Files.write(destination, entry.getValue());
            }
            for (var entry : resources.entrySet()) {
                if (removedResources.contains(entry.getKey())) continue;
                if (compilation.classContents().containsKey(entry.getKey())) {
                    throw new IOException("Resource replaces a class: " + entry.getKey());
                }
                copyIfDifferent(base, entry.getKey(), entry.getValue(), temporary);
            }
            makeReadOnly(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
        } finally {
            deleteTree(temporary);
        }
    }

    private static void copyIfDifferent(
            Path base,
            String name,
            Path source,
            Path patch) throws IOException {
        var baseFile = base.resolve(name);
        if (Files.isRegularFile(baseFile)
                && Files.mismatch(baseFile, source) == -1) {
            return;
        }
        var destination = patch.resolve(name);
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void makeReadOnly(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                try {
                    var permissions = new TreeSet<>(Files.getPosixFilePermissions(path));
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

    private static boolean resourcesChanged(
            Map<String, ContentHash> previous,
            Map<String, ContentHash> current,
            Set<String> removedResources) {
        return !removedResources.isEmpty() || !previous.equals(current);
    }

    private static Map<String, ContentHash> resourceHashes(
            Map<String, Path> resources) throws IOException {
        var hashes = new TreeMap<String, ContentHash>();
        for (var entry : resources.entrySet()) {
            hashes.put(entry.getKey(),
                    ContentHash.sha256(Files.readAllBytes(entry.getValue())));
        }
        return Collections.unmodifiableNavigableMap(hashes);
    }

    private static ContentHash inputHash(
            Map<String, ContentHash> inputs) {
        var digest = newDigest();
        inputs.forEach((path, hash) -> {
            update(digest, path);
            hash.update(digest);
        });
        return ContentHash.takeOwnership(digest.digest());
    }

    private static Set<String> removedResources(
            Set<String> previousResources,
            Set<String> resources) {
        var removed = new TreeSet<>(previousResources);
        removed.removeAll(resources);
        return Collections.unmodifiableNavigableSet(removed);
    }

    private static Path effectiveFile(
            Path base,
            Path patch,
            String relative) {
        if (patch != null) {
            var path = patch.resolve(relative);
            if (Files.isRegularFile(path)) return path;
        }
        if (base != null) {
            var path = base.resolve(relative);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    private static void collectFiles(
            Path root,
            Map<String, Path> files) throws IOException {
        if (root == null || !Files.isDirectory(root)) return;
        try (var paths = Files.walk(root)) {
            for (var file : paths.filter(Files::isRegularFile).toList()) {
                files.put(relativePath(root, file), file);
            }
        }
    }

    private static boolean isMultiRelease(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) return false;
        try (InputStream input = Files.newInputStream(manifest)) {
            return "true".equalsIgnoreCase(new Manifest(input)
                    .getMainAttributes()
                    .getValue(Attributes.Name.MULTI_RELEASE));
        }
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path).toString()
                .replace(File.separatorChar, '/');
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.noneMatch(Files::isRegularFile);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
