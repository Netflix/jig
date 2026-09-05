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

package com.netflix.module.compile;

import java.io.IOException;
import java.io.Writer;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.module.compile.internal.ContentHash;
import com.netflix.module.compile.internal.ModulePathCompilation;
import com.netflix.module.compile.internal.SourceModuleCompilation;
import com.netflix.module.compile.internal.SourceModuleManifest;
import com.netflix.module.compile.internal.SourceModuleOutput;
import com.netflix.module.compile.internal.SourceModuleOutputs;

/** Compiles Java source modules to immutable filesystem directories. */
public final class ModuleCompiler implements AutoCloseable {
    /**
     * Controls reuse, diagnostic emission, and verbose javac output for one
     * compilation. Diagnostics are always retained in compilation state, even
     * when emission is disabled.
     *
     * @param forceCompilation compile without reusing a previous output
     * @param emitDiagnostics emit current and retained diagnostics to the listener
     * @param verboseOutput the recipient for javac verbose output, or {@code null}
     */
    public record Options(boolean forceCompilation,
                          boolean emitDiagnostics,
                          Writer verboseOutput) {
        /** Creates options without javac verbose output. */
        public Options(boolean forceCompilation, boolean emitDiagnostics) {
            this(forceCompilation, emitDiagnostics, null);
        }

        /** Reuses output and emits diagnostics. */
        public static final Options DEFAULT = new Options(false, true);

        /** Reuses output without emitting retained or current diagnostics. */
        public static final Options SILENT = new Options(false, false);
    }

    /**
     * Filesystem paths presenting a compiled module.
     *
     * @param modulePath the complete or reusable base module
     * @param patchModulePath changed content applied to the base, or {@code null}
     */
    public record ModulePath(Path modulePath, Path patchModulePath) {
        /**
         * Tests whether the module requires a {@code --patch-module} path.
         *
         * @return {@code true} if a patch path is present
         */
        public boolean hasPatchModule() {
            return patchModulePath != null;
        }
    }

    /** Resolves the filesystem path for a declared module-path entry. */
    @FunctionalInterface
    public interface ModulePathResolver {
        /**
         * Resolves an entry without changing its declared identity.
         *
         * @param entry the module-path entry
         * @return the corresponding filesystem path
         * @throws IOException if the path cannot be resolved
         */
        Path resolve(ModulePathEntry entry) throws IOException;
    }

    /**
     * Immutable inputs affecting compilation and output reuse.
     *
     * @param moduleName the source module name
     * @param release the source and target Java release, or empty for the
     *     toolchain default
     * @param preview whether preview features are enabled
     * @param moduleVersion the version recorded in the module descriptor
     * @param mainClass the main class recorded in the module descriptor
     * @param runtimeAccess runtime access requirements recorded in the module
     *     descriptor
     * @param moduleSources observable source modules keyed by module name
     * @param modulePathEntries content identities on the compilation module
     *     paths
     * @param paths the resolver for module-path entry content
     */
    public record Configuration(String moduleName,
                                OptionalInt release,
                                boolean preview,
                                Optional<ModuleDescriptor.Version> moduleVersion,
                                Optional<String> mainClass,
                                ModuleRuntimeAccessOptions runtimeAccess,
                                Map<String, Path> moduleSources,
                                List<ModulePathEntry> modulePathEntries,
                                ModulePathResolver paths) {
        /**
         * Creates a configuration without additional observable source modules.
         *
         * @param moduleName the source module name
         * @param release the source and target Java release
         * @param preview whether preview features are enabled
         * @param moduleVersion the version recorded in the module descriptor
         * @param mainClass the main class recorded in the module descriptor
         * @param runtimeAccess runtime access requirements to record
         * @param modulePathEntries content identities on the compilation module
         *     paths
         * @param paths the resolver for module-path entry content
         */
        public Configuration(String moduleName,
                             OptionalInt release,
                             boolean preview,
                             Optional<ModuleDescriptor.Version> moduleVersion,
                             Optional<String> mainClass,
                             ModuleRuntimeAccessOptions runtimeAccess,
                             List<ModulePathEntry> modulePathEntries,
                             ModulePathResolver paths) {
            this(moduleName,
                 release,
                 preview,
                 moduleVersion,
                 mainClass,
                 runtimeAccess,
                 Map.of(),
                 modulePathEntries,
                 paths);
        }

        /**
         * Creates a configuration without a main class or additional observable
         * sources.
         *
         * @param moduleName the source module name
         * @param release the source and target Java release
         * @param preview whether preview features are enabled
         * @param moduleVersion the version recorded in the module descriptor
         * @param runtimeAccess runtime access requirements to record
         * @param modulePathEntries content identities on the compilation module
         *     paths
         * @param paths the resolver for module-path entry content
         */
        public Configuration(String moduleName,
                             OptionalInt release,
                             boolean preview,
                             Optional<ModuleDescriptor.Version> moduleVersion,
                             ModuleRuntimeAccessOptions runtimeAccess,
                             List<ModulePathEntry> modulePathEntries,
                             ModulePathResolver paths) {
            this(moduleName,
                 release,
                 preview,
                 moduleVersion,
                 Optional.empty(),
                 runtimeAccess,
                 Map.of(),
                 modulePathEntries,
                 paths);
        }

        /** Validates, normalizes, and creates a compilation configuration. */
        public Configuration {
            moduleName = Objects.requireNonNull(moduleName, "moduleName");
            if (!SourceVersion.isName(moduleName)) {
                throw new IllegalArgumentException("Invalid module name: " + moduleName);
            }
            release = Objects.requireNonNull(release, "release");
            if (release.isPresent() && release.getAsInt() <= 0) {
                throw new IllegalArgumentException("Compilation release must be positive: " + release.getAsInt());
            }
            moduleVersion = Objects.requireNonNull(moduleVersion, "moduleVersion");
            mainClass = Objects.requireNonNull(mainClass, "mainClass");
            mainClass.ifPresent(value -> {
                if (!SourceVersion.isName(value)) {
                    throw new IllegalArgumentException("Invalid main class: " + value);
                }
            });
            runtimeAccess = Objects.requireNonNull(runtimeAccess, "runtimeAccess");
            var normalizedSources = new TreeMap<String, Path>();
            Objects.requireNonNull(moduleSources, "moduleSources")
                    .forEach((name, path) ->
                            normalizedSources.put(Objects.requireNonNull(name, "source module name"),
                                            Objects.requireNonNull(path, "module source path")
                                                    .toAbsolutePath()
                                                    .normalize()));
            moduleSources = Collections.unmodifiableNavigableMap(normalizedSources);
            modulePathEntries =
                    Objects.requireNonNull(modulePathEntries, "modulePathEntries").stream()
                            .sorted()
                            .toList();
            var locations = new HashSet<Map.Entry<StandardLocation, String>>();
            for (var entry : modulePathEntries) {
                var location = Map.entry(entry.location(), entry.moduleName());
                if (!locations.add(location)) {
                    throw new IllegalArgumentException("Duplicate module-path entry: " + location);
                }
            }
            paths = Objects.requireNonNull(paths, "paths");
        }
    }

    private record State(Path base, Path patch) {
        Path outputPath() {
            return patch == null ? base : patch;
        }
    }

    private record LatestReconciliation(String sourcePathIdentity, String outputIdentity) {}

    private static final SourceModuleCompilation.Result NO_CHANGES =
            new SourceModuleCompilation.Result(Set.of(),
                    0,
                    Map.of(),
                    Set.of(),
                    SourceModuleCompilation.State.empty());

    private final SourceModuleOutputs sourceModules;
    private final DiagnosticListener<? super JavaFileObject> diagnosticListener;
    private long compilations;
    private long parsedSources;

    /**
     * Creates a compiler which does not emit diagnostics.
     *
     * @param outputRoot the root for managed immutable compilation output
     * @throws IOException if the output store cannot be initialized
     */
    public ModuleCompiler(Path outputRoot) throws IOException {
        this(outputRoot, null);
    }

    /**
     * Creates a compiler that reports current and retained javac diagnostics to
     * {@code diagnosticListener}. Individual compilations can suppress emission
     * with {@link Options#SILENT} without suppressing diagnostic retention.
     *
     * @param outputRoot the root for managed immutable compilation output
     * @param diagnosticListener the diagnostic recipient, or {@code null}
     * @throws IOException if the output store cannot be initialized
     */
    public ModuleCompiler(Path outputRoot, DiagnosticListener<? super JavaFileObject> diagnosticListener) throws IOException {
        sourceModules = new SourceModuleOutputs(outputRoot);
        this.diagnosticListener = diagnosticListener;
    }

    /**
     * Compiles or reuses a source module with the default options.
     *
     * @param sourceRoot the module's source root
     * @param configuration the compilation configuration
     * @param targetRelease the feature release of the compiling toolchain
     * @return immutable managed compilation output
     * @throws IOException if compilation or output management fails
     */
    public CompiledModule compile(Path sourceRoot, Configuration configuration, int targetRelease) throws IOException {
        return compile(sourceRoot, configuration, targetRelease, Options.DEFAULT);
    }

    /**
     * Compiles a source module with explicit reuse and diagnostic options.
     * Forced compilations are still published as managed output.
     *
     * @param sourceRoot the module's source root
     * @param configuration the compilation configuration
     * @param targetRelease the feature release of the compiling toolchain
     * @param options reuse and diagnostic options
     * @return immutable managed compilation output
     * @throws IOException if compilation or output management fails
     */
    public CompiledModule compile(Path sourceRoot, Configuration configuration, int targetRelease, Options options) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(options, "options");
        var moduleRoot = sourceModules.moduleDirectory(configuration.moduleName());
        var configurationIdentity = configurationIdentity(configuration);
        var outputRoot = Files.createDirectories(moduleRoot.resolve("configuration").resolve(configurationIdentity));
        var bases = Files.createDirectories(outputRoot.resolve("base"));
        var observationPath = observationPath(moduleRoot, sourceRoot);
        var reconciliationPath = reconciliationPath(outputRoot, sourceRoot);
        var ownObservations = SourceModuleManifest.readObservations(observationPath).orElse(null);
        var ownReconciliation = SourceModuleManifest.readReconciliation(reconciliationPath).orElse(null);
        var latestReconciliation = ownReconciliation == null ? readLatestReconciliation(outputRoot).orElse(null) : null;
        var observations = options.forceCompilation() ? null : ownObservations;
        if (!options.forceCompilation() && observations == null && latestReconciliation != null) {
            observations =
                    SourceModuleManifest.readObservations(moduleRoot.resolve("observations").resolve(latestReconciliation.sourcePathIdentity()))
                            .orElse(null);
        }
        if (!options.forceCompilation() && observations == null) {
            observations = readLatestObservations(moduleRoot).orElse(null);
        }
        var inputs = SourceModuleOutput.readInputs(sourceRoot, targetRelease, observations);
        var systemImage = configuration.release().isEmpty()
                ? SourceModuleCompilation.systemImage()
                : null;
        var identity = compilationIdentity(inputs.hash(), configurationIdentity, configuration, systemImage);
        var exact = options.forceCompilation() ? null : exactOutput(outputRoot, identity);
        var refreshDiagnostics = false;
        if (exact != null) {
            var exactState = SourceModuleManifest.readCompilation(statePath(outputRoot, stateIdentity(exact))).orElse(SourceModuleCompilation.State.empty());
            refreshDiagnostics = SourceModuleManifest.requiresDiagnosticRefresh(exactState);
            if (!refreshDiagnostics) {
                if (options.emitDiagnostics() && diagnosticListener != null) {
                    emitDiagnostics(exactState, sourceRoot.toAbsolutePath().normalize());
                }
                touch(exact);
                writeSourceState(moduleRoot,
                                 outputRoot,
                                 observationPath,
                                 reconciliationPath,
                                 ownObservations,
                                 inputs.observations(),
                                 identity);
                return compiled(exact.base(),
                                exact.patch(),
                                emptyMeasurements(exact, exactState),
                                configurationIdentity);
            }
        }

        var previousIdentity = options.forceCompilation() || refreshDiagnostics ? null
                : ownReconciliation != null ? ownReconciliation.outputIdentity()
                : latestReconciliation == null ? null
                : latestReconciliation.outputIdentity();
        var previous = previousIdentity == null ? null : exactOutput(outputRoot, previousIdentity);
        var previousState = previous == null
                ? SourceModuleCompilation.State.empty()
                : SourceModuleManifest.readCompilation(statePath(outputRoot, stateIdentity(previous))).orElse(SourceModuleCompilation.State.empty());
        if (SourceModuleManifest.requiresDiagnosticRefresh(previousState)) {
            refreshDiagnostics = true;
            previous = null;
            previousState = SourceModuleCompilation.State.empty();
        }
        if (previous != null)
            touch(previous);
        var resolved = resolve(configuration);
        var target = outputRoot.resolve(".compile-" + java.util.UUID.randomUUID());
        final SourceModuleOutput.Result output;
        try {
            output =
                    SourceModuleOutput.compile(sourceRoot,
                                               previous == null ? null : previous.base(),
                                               previous == null ? null : previous.patch(),
                                               target,
                                               targetRelease,
                                               resolved,
                                               inputs,
                                               previousState,
                    options.emitDiagnostics() ? diagnosticListener : null,
                    options.verboseOutput());
        } catch (IOException | RuntimeException | Error failure) {
            deleteTree(target);
            throw failure;
        }
        recordMeasurements(output);
        identity = compilationIdentity(inputHash(output), configurationIdentity, configuration, systemImage);

        exact = exactOutput(outputRoot, identity);
        if (exact != null) {
            deleteTree(target);
            touch(exact);
            if (refreshDiagnostics) {
                SourceModuleManifest.writeCompilation(statePath(outputRoot, stateIdentity(exact)), output.state());
            }
            return finish(moduleRoot,
                          outputRoot,
                          observationPath,
                          reconciliationPath,
                          ownObservations,
                          identity,
                          inputs,
                          output.state(),
                          exact,
                          output);
        }

        var producedPatch = target.equals(output.patch());
        if (!output.output().equals(target) && !producedPatch) {
            var alias = publishPatchState(outputRoot, previous, identity, output.patch());
            touch(alias);
            return finish(moduleRoot,
                          outputRoot,
                          observationPath,
                          reconciliationPath,
                          ownObservations,
                          identity,
                          inputs,
                          output.state(),
                          alias,
                          output);
        }

        var exactBase = bases.resolve(identity);
        if (producedPatch) {
            if (usePatch(target, output.state())) {
                var state =
                        new State(output.output(),
                                  outputRoot.resolve("patch")
                                          .resolve(identity)
                                          .resolve(output.output()
                                                           .getFileName()
                                                           .toString()));
                publishDirectory(target, state.patch());
                touch(state);
                return finish(moduleRoot,
                              outputRoot,
                              observationPath,
                              reconciliationPath,
                              ownObservations,
                              identity,
                              inputs,
                              output.state(),
                              state,
                              output);
            }
            var full = outputRoot.resolve(".base-" + java.util.UUID.randomUUID());
            try {
                SourceModuleCompilation.write(output.output(),
                                              target,
                                              NO_CHANGES,
                                              Map.of(),
                                              Set.of(),
                                              full);
                publishDirectory(full, exactBase);
            } finally {
                deleteTree(full);
                deleteTree(target);
            }
            touch(exactBase);
            return finish(moduleRoot,
                          outputRoot,
                          observationPath,
                          reconciliationPath,
                          ownObservations,
                          identity,
                          inputs,
                          output.state(),
                          new State(exactBase, null),
                          output);
        }

        publishDirectory(target, exactBase);
        touch(exactBase);
        return finish(moduleRoot,
                      outputRoot,
                      observationPath,
                      reconciliationPath,
                      ownObservations,
                      identity,
                      inputs,
                      output.state(),
                      new State(exactBase, null),
                      output);
    }

    /* Bootstrap against the preceding compilation state shape. */
    @SuppressWarnings("unchecked")
    private void emitDiagnostics(SourceModuleCompilation.State state, Path sourceRoot) {
        final List<Object> diagnostics;
        try {
            diagnostics = (List<Object>) SourceModuleCompilation.State.class.getMethod("diagnostics").invoke(state);
        } catch (NoSuchMethodException bootstrap) {
            return;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read compilation diagnostics", failure);
        }
        try {
            for (var index = 0; index < diagnostics.size(); index++) {
                var stored =
                        diagnostics.get(index)
                                .getClass()
                                .getMethod("diagnostic")
                                .invoke(diagnostics.get(index));
                Diagnostic<? extends JavaFileObject> diagnostic;
                try {
                    diagnostic =
                            (Diagnostic<? extends JavaFileObject>) stored.getClass()
                                            .getMethod("at", Path.class, boolean.class)
                                            .invoke(stored, sourceRoot, index == diagnostics.size() - 1);
                } catch (NoSuchMethodException bootstrap) {
                    diagnostic =
                            (Diagnostic<? extends JavaFileObject>) stored.getClass()
                                            .getMethod("at", Path.class)
                                            .invoke(stored, sourceRoot);
                }
                diagnosticListener.report(diagnostic);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot emit compilation diagnostics", failure);
        }
    }

    private synchronized void recordMeasurements(SourceModuleOutput.Result output) {
        compilations += output.compilations();
        parsedSources += output.parsedSources();
    }

    /**
     * Materializes a compiled module as one complete directory.
     *
     * @param compilation the managed compilation output
     * @param target the destination directory
     * @return the normalized destination path
     * @throws IOException if the output cannot be read or written
     */
    public Path write(CompiledModule compilation, Path target) throws IOException {
        Objects.requireNonNull(compilation, "compilation");
        target =
                Objects.requireNonNull(target, "target")
                        .toAbsolutePath()
                        .normalize();
        if (compilation.patch() == null
                && target.equals(compilation.output())) {
            touch(compilation.output());
            return target;
        }
        var replacement = target.resolveSibling("." + target.getFileName() + "-" + java.util.UUID.randomUUID());
        SourceModuleCompilation.write(compilation.output(),
                                      compilation.patch(),
                                      NO_CHANGES,
                                      Map.of(),
                                      Set.of(),
                                      replacement);
        if (Files.exists(target))
            deleteTree(target);
        move(replacement, target);
        touch(compilation.output());
        if (compilation.patch() != null)
            touch(compilation.patch());
        return target;
    }

    /**
     * Returns a reusable module path, retaining a patch when one is available.
     *
     * @param moduleName the compiled module name
     * @param compilation the managed compilation output
     * @return paths presenting the compiled module
     * @throws IOException if the output cannot be read or touched
     */
    public ModulePath modulePath(String moduleName, CompiledModule compilation) throws IOException {
        return modulePath(moduleName, compilation, true);
    }

    /**
     * Returns paths presenting a compiled module, optionally materializing a
     * complete module rather than returning a patch.
     *
     * @param moduleName the compiled module name
     * @param compilation the managed compilation output
     * @param allowPatch whether a separate patch path may be returned
     * @return paths presenting the compiled module
     * @throws IOException if the output cannot be read, written, or touched
     */
    public ModulePath modulePath(String moduleName, CompiledModule compilation, boolean allowPatch) throws IOException {
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(compilation, "compilation");
        if (compilation.patch() == null || allowPatch) {
            touch(compilation.output());
            if (compilation.patch() != null)
                touch(compilation.patch());
            return new ModulePath(compilation.output(), compilation.patch());
        }

        var base =
                sourceModules.moduleDirectory(moduleName)
                        .resolve("complete")
                        .resolve(compilation.hash().digest());
        if (!Files.isDirectory(base)) {
            var temporary = base.resolveSibling(".complete-" + java.util.UUID.randomUUID());
            try {
                SourceModuleCompilation.write(compilation.output(),
                                              compilation.patch(),
                                              NO_CHANGES,
                                              Map.of(),
                                              Set.of(),
                                              temporary);
                publishDirectory(temporary, base);
            } finally {
                deleteTree(temporary);
            }
        }
        touch(base);
        return new ModulePath(base, null);
    }

    @Override
    public void close() throws IOException {
        sourceModules.close();
    }

    private static SourceModuleOutput.Result emptyMeasurements(
            State state, SourceModuleCompilation.State compilationState) {
        return new SourceModuleOutput.Result(state.base(),
                state.patch(),
                0,
                0,
                compilationState);
    }

    private static CompiledModule compiled(Path base, Path patch,
            SourceModuleOutput.Result measurements, String configurationIdentity) {
        return new CompiledModule(new SourceModuleOutput.Result(base,
                                                                patch,
                                                                measurements.compilations(),
                                                                measurements.parsedSources(),
                                                                measurements.state()),
                                  configurationIdentity);
    }

    private static CompiledModule finish(Path moduleRoot,
            Path outputRoot,
            Path observationPath,
            Path reconciliationPath,
            SourceModuleManifest.Observations previousObservations,
            String identity,
            SourceModuleOutput.Inputs inputs,
            SourceModuleCompilation.State state,
            State output,
            SourceModuleOutput.Result measurements) throws IOException {
        var statePath = statePath(outputRoot, identity);
        if (!Files.isRegularFile(statePath)) {
            SourceModuleManifest.writeCompilation(statePath, state);
        }
        writeSourceState(moduleRoot,
                         outputRoot,
                         observationPath,
                         reconciliationPath,
                         previousObservations,
                         inputs.observations(),
                         identity);
        return compiled(output.base(), output.patch(), measurements,
                outputRoot.getFileName().toString());
    }

    private static void writeSourceState(Path moduleRoot,
            Path outputRoot,
            Path observationPath,
            Path reconciliationPath,
            SourceModuleManifest.Observations previousObservations,
            SourceModuleManifest.Observations currentObservations,
            String outputIdentity) throws IOException {
        if (!currentObservations.equals(previousObservations)) {
            SourceModuleManifest.writeObservations(observationPath, currentObservations);
        } else if (Files.isRegularFile(observationPath)) {
            touch(observationPath);
        }
        var reconciliation = new SourceModuleManifest.Reconciliation(outputIdentity);
        var previousReconciliation = SourceModuleManifest.readReconciliation(reconciliationPath).orElse(null);
        if (!reconciliation.equals(previousReconciliation)) {
            SourceModuleManifest.writeReconciliation(reconciliationPath, reconciliation);
        } else if (Files.isRegularFile(reconciliationPath)) {
            touch(reconciliationPath);
        }
        writeLatestReference(moduleRoot.resolve("observations"), observationPath);
        writeLatestReference(outputRoot.resolve("reconciliations"), reconciliationPath);
    }

    private static Path statePath(Path outputRoot, String identity) {
        return outputRoot.resolve("state").resolve(identity);
    }

    private static Path observationPath(Path moduleRoot, Path sourceRoot) {
        return moduleRoot.resolve("observations").resolve(sourcePathIdentity(sourceRoot));
    }

    private static Path reconciliationPath(Path outputRoot, Path sourceRoot) {
        return outputRoot.resolve("reconciliations").resolve(sourcePathIdentity(sourceRoot));
    }

    private static String sourcePathIdentity(Path sourceRoot) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
        update(digest,
               sourceRoot.toAbsolutePath()
                       .normalize()
                       .toString());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Optional<SourceModuleManifest.Observations> readLatestObservations(Path moduleRoot) throws IOException {
        var directory = moduleRoot.resolve("observations");
        var name = readLatestReference(directory);
        return name.isEmpty()
                ? Optional.empty()
                : SourceModuleManifest.readObservations(directory.resolve(name.orElseThrow()));
    }

    private static Optional<LatestReconciliation> readLatestReconciliation(Path outputRoot) throws IOException {
        var directory = outputRoot.resolve("reconciliations");
        var name = readLatestReference(directory);
        if (name.isEmpty())
            return Optional.empty();
        var reconciliation = SourceModuleManifest.readReconciliation(directory.resolve(name.orElseThrow()));
        return reconciliation.map(value ->
                        new LatestReconciliation(name.orElseThrow(), value.outputIdentity()));
    }

    private static Optional<String> readLatestReference(Path directory) throws IOException {
        var latest = directory.resolve("latest");
        if (!Files.isRegularFile(latest))
            return Optional.empty();
        var name = Files.readString(latest, StandardCharsets.US_ASCII).strip();
        return isObservationName(name) ? Optional.of(name) : Optional.empty();
    }

    private static void writeLatestReference(Path directory, Path referencedPath) throws IOException {
        var latest = directory.resolve("latest");
        var name = referencedPath.getFileName().toString();
        if (Files.isRegularFile(latest)
                && Files.readString(latest, StandardCharsets.US_ASCII)
                        .strip()
                        .equals(name)) {
            touch(latest);
            return;
        }
        Files.createDirectories(latest.getParent());
        var temporary = Files.createTempFile(latest.getParent(), ".latest-", ".tmp");
        try {
            Files.writeString(temporary, name + "\n", StandardCharsets.US_ASCII);
            replace(temporary, latest);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isObservationName(String name) {
        if (name.length() != 64)
            return false;
        for (var index = 0; index < name.length(); index++) {
            var character = name.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String stateIdentity(State state) {
        return state.patch() == null
                ? state.base()
                        .getFileName()
                        .toString()
                : state.patch()
                        .getParent()
                        .getFileName()
                        .toString();
    }

    /* Bootstrap against the preceding Result record shape. */
    private static ContentHash inputHash(SourceModuleOutput.Result result) {
        try {
            return (ContentHash) SourceModuleOutput.Result.class.getMethod("inputHash").invoke(result);
        } catch (NoSuchMethodException bootstrap) {
            throw new IllegalStateException("Compiler output does not report its input hash", bootstrap);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read compiler output input hash", failure);
        }
    }

    private static String configurationIdentity(Configuration configuration) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
        update(digest, "source-module-configuration-1");
        update(digest,
               configuration.release().isPresent()
                       ? Integer.toString(configuration.release().getAsInt())
                       : null);
        update(digest, configuration.preview() ? 1 : 0);
        update(digest, configuration.moduleSources().size());
        configuration.moduleSources()
                .keySet()
                .forEach(name -> update(digest, name));
        var exports =
                configuration.runtimeAccess().addExports().stream()
                        .filter(access ->
                                access.targetModule().equals(configuration.moduleName()))
                        .map(ModuleRuntimeAccessOptions.PackageAccess::toFlagValue)
                        .sorted()
                        .toList();
        update(digest, exports.size());
        exports.forEach(value -> update(digest, value));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String compilationIdentity(ContentHash inputHash,
            String configurationIdentity,
            Configuration configuration,
            SourceModuleCompilation.SystemImage systemImage) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
        update(digest, "source-module-input-1");
        Objects.requireNonNull(inputHash, "inputHash").update(digest);
        update(digest, configurationIdentity);
        update(digest,
               configuration.moduleVersion()
                       .map(Object::toString)
                       .orElse(null));
        update(digest, configuration.mainClass().orElse(null));
        update(digest, ModuleRuntimeAccess.forModule(configuration.runtimeAccess(), configuration.moduleName()));
        var modulePath = ModulePathCompilation.entries(
                configuration.modulePathEntries());
        update(digest, modulePath.size());
        for (var entry : modulePath) {
            update(digest, entry.location().name());
            update(digest, entry.moduleName());
            update(digest, entry.hash().toString());
        }
        if (systemImage == null) {
            update(digest, 0);
        } else {
            update(digest, 1);
            update(digest, systemImage.path().toString());
            update(digest, Long.toString(systemImage.size()));
            update(digest, Long.toString(systemImage.modifiedSeconds()));
            update(digest, systemImage.modifiedNanos());
            update(digest, systemImage.fileKey());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, ModuleRuntimeAccessOptions access) {
        var nativeAccess =
                access.enableNativeAccess().stream()
                        .sorted()
                        .toList();
        update(digest, nativeAccess.size());
        nativeAccess.forEach(value -> update(digest, value));
        var finalFieldMutation =
                access.enableFinalFieldMutation().stream()
                        .sorted()
                        .toList();
        update(digest, finalFieldMutation.size());
        finalFieldMutation.forEach(value -> update(digest, value));
        var exports =
                access.addExports().stream()
                        .map(ModuleRuntimeAccessOptions.PackageAccess::toFlagValue)
                        .sorted()
                        .toList();
        update(digest, exports.size());
        exports.forEach(value -> update(digest, value));
        var opens =
                access.addOpens().stream()
                        .map(ModuleRuntimeAccessOptions.PackageAccess::toFlagValue)
                        .sorted()
                        .toList();
        update(digest, opens.size());
        opens.forEach(value -> update(digest, value));
    }

    private static void update(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            update(digest, -1);
            return;
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static SourceModuleCompilation.Configuration resolve(Configuration configuration) {
        var resolver = new SourceModuleCompilation.PathResolver() {
            private SourceModuleCompilation.Paths resolved;

            @Override
            public synchronized SourceModuleCompilation.Paths resolve() throws IOException {
                if (resolved == null)
                    resolved = resolvePaths(configuration);
                return resolved;
            }
        };
        return new SourceModuleCompilation.Configuration(configuration.moduleName(),
                configuration.release().isPresent()
                        ? configuration.release().getAsInt()
                        : null,
                configuration.preview(),
                configuration.moduleVersion()
                        .map(Object::toString)
                        .orElse(null),
                configuration.mainClass().orElse(null),
                configuration.runtimeAccess(),
                paths(List.of(),
                      List.of(),
                      Map.of(),
                      moduleSourcePaths(configuration)),
                configuration.modulePathEntries(),
                resolver);
    }

    private static SourceModuleCompilation.Paths resolvePaths(Configuration configuration) throws IOException {
        var modulePath = new ArrayList<Path>();
        var upgradeModulePath = new ArrayList<Path>();
        var patches = new TreeMap<String, List<Path>>();
        for (var entry : configuration.modulePathEntries()) {
            var path =
                    configuration.paths()
                            .resolve(entry)
                            .toAbsolutePath()
                            .normalize();
            switch (entry.location()) {
                case MODULE_PATH -> modulePath.add(path);
                case UPGRADE_MODULE_PATH -> upgradeModulePath.add(path);
                case PATCH_MODULE_PATH -> patches.computeIfAbsent(entry.moduleName(), _ -> new ArrayList<>()).add(path);
                default -> throw new AssertionError(entry.location());
            }
            if (entry.location() != StandardLocation.PATCH_MODULE_PATH
                    && ModuleFinder.of(path)
                            .find(entry.moduleName())
                            .isEmpty()) {
                throw new IOException("Module-path entry does not contain " + entry.moduleName() + ": " + path);
            }
        }
        return paths(modulePath, upgradeModulePath, patches, moduleSourcePaths(configuration));
    }

    /*
     * Keep this source bootstrap-compatible with the immediately preceding
     * three-component Paths record. A clean/current build always takes the
     * four-component branch.
     */
    private static SourceModuleCompilation.Paths paths(List<Path> modulePath,
            List<Path> upgradeModulePath,
            Map<String, List<Path>> patches,
            Map<String, List<Path>> moduleSources) {
        try {
            var constructor = SourceModuleCompilation.Paths.class.getConstructor(List.class, List.class, Map.class, Map.class);
            return constructor.newInstance(modulePath, upgradeModulePath, patches, moduleSources);
        } catch (NoSuchMethodException bootstrap) {
            return new SourceModuleCompilation.Paths(modulePath, upgradeModulePath, patches);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct compilation paths", failure);
        }
    }

    private static Map<String, List<Path>> moduleSourcePaths(Configuration configuration) {
        var paths = new TreeMap<String, List<Path>>();
        configuration.moduleSources()
                .forEach((name, path) -> paths.put(name, List.of(path)));
        return paths;
    }

    private static State exactOutput(Path moduleRoot, String identity) throws IOException {
        var bases = moduleRoot.resolve("base");
        var base = bases.resolve(identity);
        if (Files.isDirectory(base))
            return new State(base, null);

        var patches = moduleRoot.resolve("patch").resolve(identity);
        if (!Files.isDirectory(patches))
            return null;
        try (var baseNames = Files.list(patches)) {
            for (var baseName :
                    baseNames.filter(Files::isDirectory)
                            .sorted()
                            .toList()) {
                var patchBase = bases.resolve(baseName.getFileName().toString());
                if (Files.isDirectory(patchBase)) {
                    return new State(patchBase, baseName);
                }
            }
        }
        return null;
    }

    private static State publishPatchState(Path moduleRoot, State previous, String identity, Path previousPatch) throws IOException {
        if (previous == null) {
            throw new IOException("Compilation reused output without a previous state");
        }
        var patch =
                moduleRoot.resolve("patch")
                        .resolve(identity)
                        .resolve(previous.base()
                                         .getFileName()
                                         .toString());
        var temporary = Files.createTempDirectory(Files.createDirectories(patch.getParent()), ".patch-");
        try {
            if (previousPatch != null)
                copyDirectory(previousPatch, temporary);
            publishDirectory(temporary, patch);
        } finally {
            deleteTree(temporary);
        }
        return new State(previous.base(), patch);
    }

    private static boolean usePatch(Path patch, SourceModuleCompilation.State state) throws IOException {
        final long patchFiles;
        try (var paths = Files.walk(patch)) {
            patchFiles = paths.filter(Files::isRegularFile).count();
        }
        var outputFiles =
                state.resources().size()
                        + state.sources().values().stream()
                                .mapToLong(compilation -> compilation.generatedClasses().size())
                                .sum();
        return patchFiles > 0 && patchFiles * 2 < outputFiles;
    }

    private static void touch(State state) throws IOException {
        touch(state.base());
        if (state.patch() != null)
            touch(state.patch());
    }

    private static void touch(Path path) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (var path : paths.sorted().toList()) {
                var relative = source.relativize(path);
                var destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void publishDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        makeReadOnly(source, false);
        try {
            move(source, target);
        } catch (IOException failure) {
            if (!Files.isDirectory(target))
                throw failure;
            deleteTree(source);
        }
        makeReadOnly(target, true);
    }

    private static void makeReadOnly(Path root, boolean includeRoot) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths
                    .filter(path -> includeRoot || !path.equals(root))
                    .sorted(Collections.reverseOrder())
                    .toList()) {
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

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root))
            return;
        makeDirectoriesWritable(root);
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void makeDirectoriesWritable(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isDirectory).toList()) {
                try {
                    var permissions = new TreeSet<>(Files.getPosixFilePermissions(path));
                    permissions.add(PosixFilePermission.OWNER_WRITE);
                    Files.setPosixFilePermissions(path, permissions);
                } catch (UnsupportedOperationException e) {
                    path.toFile().setWritable(true, true);
                }
            }
        }
    }
}
