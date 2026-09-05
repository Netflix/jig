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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.tools.Diagnostic.Kind;
import javax.tools.StandardLocation;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import com.netflix.module.compile.ModulePathEntry;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;
import com.netflix.module.compile.internal.SourceModuleCompilation.SystemImage;

/** Persistent observations and compilation facts for source modules. */
public final class SourceModuleManifest {
    private static final int MAGIC = 0x4a49474d;
    private static final int VERSION = 1;
    private static final int OBSERVATIONS = 1;
    private static final int COMPILATION = 2;
    private static final int RECONCILIATION = 3;
    private static final int DIAGNOSTICS = 0x44494147;
    private static final int RENDERED_DIAGNOSTICS = 0x44494148;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

    public record FileObservation(long size, long modifiedSeconds, int modifiedNanos,
            String fileKey, ContentHash hash) {
        public FileObservation {
            if (size < 0) {
                throw new IllegalArgumentException("Negative file size");
            }
            if (modifiedNanos < 0 || modifiedNanos > 999_999_999) {
                throw new IllegalArgumentException("Invalid modification nanoseconds: " + modifiedNanos);
            }
            fileKey = Objects.requireNonNull(fileKey, "fileKey");
            hash = Objects.requireNonNull(hash, "hash");
        }

        public boolean sameFile(long currentSize, long currentSeconds, int currentNanos,
                                String currentFileKey) {
            return size == currentSize
                    && modifiedSeconds == currentSeconds
                    && modifiedNanos == currentNanos
                    && fileKey.equals(currentFileKey);
        }
    }

    public record Observations(Path sourceRoot, Map<String, FileObservation> files) {
        public Observations {
            sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot")
                    .toAbsolutePath()
                    .normalize();
            files = Collections.unmodifiableNavigableMap(new TreeMap<>(Objects.requireNonNull(files, "files")));
        }
    }

    /** Last immutable output reconciled with one source path and configuration. */
    public record Reconciliation(String outputIdentity) {
        public Reconciliation {
            outputIdentity = Objects.requireNonNull(outputIdentity, "outputIdentity");
            if (outputIdentity.isEmpty()) {
                throw new IllegalArgumentException("Output identity is empty");
            }
        }
    }

    private SourceModuleManifest() {}

    public static Optional<Observations> readObservations(Path path) throws IOException {
        return read(
                path,
                OBSERVATIONS,
                input -> {
                    var sourceRoot = Path.of(readString(input));
                    var count = readCount(input);
                    var files = new TreeMap<String, FileObservation>();
                    for (var index = 0; index < count; index++) {
                        var name = readString(input);
                        var size = input.readLong();
                        var seconds = input.readLong();
                        var nanos = input.readInt();
                        var fileKey = readString(input);
                        var hash = readHash(input);
                        if (files.put(name, new FileObservation(size, seconds, nanos, fileKey, hash)) != null) {
                            throw new IOException("Duplicate observed file: " + name);
                        }
                    }
                    return new Observations(sourceRoot, files);
                });
    }

    public static void writeObservations(Path path, Observations observations) throws IOException {
        Objects.requireNonNull(observations, "observations");
        write(path, OBSERVATIONS,
                output -> {
                    writeString(output, observations.sourceRoot().toString());
                    output.writeInt(observations.files().size());
                    for (var entry : observations.files().entrySet()) {
                        writeString(output, entry.getKey());
                        var value = entry.getValue();
                        output.writeLong(value.size());
                        output.writeLong(value.modifiedSeconds());
                        output.writeInt(value.modifiedNanos());
                        writeString(output, value.fileKey());
                        value.hash().writeTo(output);
                    }
                });
    }

    public static Optional<Reconciliation> readReconciliation(Path path) throws IOException {
        return read(path, RECONCILIATION, input -> new Reconciliation(readString(input)));
    }

    public static void writeReconciliation(Path path, Reconciliation reconciliation) throws IOException {
        Objects.requireNonNull(reconciliation, "reconciliation");
        write(path, RECONCILIATION, output -> writeString(output, reconciliation.outputIdentity()));
    }

    public static Optional<State> readCompilation(Path path) throws IOException {
        return read(
                path,
                COMPILATION,
                input -> {
                    var sourceCount = readCount(input);
                    var sources = new TreeMap<SourcePath, SourceCompilation>();
                    for (var index = 0; index < sourceCount; index++) {
                        var source = new SourcePath(readString(input));
                        var sourceHash = readHash(input);
                        var declarationHash = readHash(input);
                        var generated = readClassNames(input);
                        var systemClassCount = readCount(input);
                        var systemClasses = new TreeMap<String, ContentHash>();
                        for (var systemClassIndex = 0; systemClassIndex < systemClassCount; systemClassIndex++) {
                            var uri = readString(input);
                            if (systemClasses.put(uri, readHash(input)) != null) {
                                throw new IOException("Duplicate system class: " + uri);
                            }
                        }
                        var compilation = new SourceCompilation(source, sourceHash, declarationHash, generated, systemClasses);
                        if (sources.put(source, compilation) != null) {
                            throw new IOException("Duplicate compiled source: " + source);
                        }
                    }
                    var modulePathEntryCount = readCount(input);
                    var modulePathEntries = new TreeSet<ModulePathEntry>();
                    for (var index = 0; index < modulePathEntryCount; index++) {
                        var location = StandardLocation.valueOf(readString(input));
                        var name = readString(input);
                        var type = Type.valueOf(readString(input));
                        var algorithm = readString(input);
                        var digest = readString(input);
                        if (!modulePathEntries.add(new ModulePathEntry(name, location, new ModuleHash(type, algorithm, digest)))) {
                            throw new IOException("Duplicate module-path entry: " + name);
                        }
                    }
                    var resourceCount = readCount(input);
                    var resources = new TreeMap<String, ContentHash>();
                    for (var index = 0; index < resourceCount; index++) {
                        var name = readString(input);
                        if (resources.put(name, readHash(input)) != null) {
                            throw new IOException("Duplicate resource: " + name);
                        }
                    }
                    var moduleInfoOptionsHash = input.readBoolean() ? readHash(input) : null;
                    SystemImage systemImage = null;
                    if (input.readBoolean()) {
                        systemImage = new SystemImage(Path.of(readString(input)), input.readLong(), input.readLong(),
                                input.readInt(), readString(input));
                    }
                    var diagnostics = readDiagnostics(input, sources.keySet());
                    return CompilationDiagnostic.state(sources, modulePathEntries.stream().toList(),
                            resources, moduleInfoOptionsHash, systemImage, diagnostics);
                });
    }

    public static void writeCompilation(Path path, State state) throws IOException {
        Objects.requireNonNull(state, "state");
        write(
                path,
                COMPILATION,
                output -> {
                    output.writeInt(state.sources().size());
                    for (var compilation : state.sources().values()) {
                        writeString(output, compilation.source().value());
                        compilation.sourceHash().writeTo(output);
                        compilation.declarationHash().writeTo(output);
                        writeClassNames(output, compilation.generatedClasses());
                        output.writeInt(compilation.systemClasses().size());
                        for (var entry : compilation.systemClasses().entrySet()) {
                            writeString(output, entry.getKey());
                            entry.getValue().writeTo(output);
                        }
                    }
                    output.writeInt(state.modulePathEntries().size());
                    for (var entry : state.modulePathEntries()) {
                        writeString(output, entry.location().name());
                        writeString(output, entry.moduleName());
                        writeString(output,
                                entry.hash()
                                     .type()
                                     .name());
                        writeString(output, entry.hash().algorithm());
                        writeString(output, entry.hash().digest());
                    }
                    output.writeInt(state.resources().size());
                    for (var entry : state.resources().entrySet()) {
                        writeString(output, entry.getKey());
                        entry.getValue().writeTo(output);
                    }
                    var moduleInfoOptionsHash = state.moduleInfoOptionsHash();
                    output.writeBoolean(moduleInfoOptionsHash != null);
                    if (moduleInfoOptionsHash != null) {
                        moduleInfoOptionsHash.writeTo(output);
                    }
                    var systemImage = state.systemImage();
                    output.writeBoolean(systemImage != null);
                    if (systemImage != null) {
                        writeString(output, systemImage.path().toString());
                        output.writeLong(systemImage.size());
                        output.writeLong(systemImage.modifiedSeconds());
                        output.writeInt(systemImage.modifiedNanos());
                        writeString(output, systemImage.fileKey());
                    }
                    output.writeInt(RENDERED_DIAGNOSTICS);
                    var diagnostics = CompilationDiagnostic.diagnostics(state);
                    output.writeInt(diagnostics.size());
                    for (var compilationDiagnostic : diagnostics) {
                        var diagnostic = compilationDiagnostic.diagnostic();
                        writeString(output, diagnostic.kind().name());
                        writeString(output, diagnostic.code());
                        output.writeBoolean(diagnostic.source() != null);
                        if (diagnostic.source() != null) {
                            writeString(output, diagnostic.source().value());
                        }
                        output.writeLong(diagnostic.position());
                        output.writeLong(diagnostic.startPosition());
                        output.writeLong(diagnostic.endPosition());
                        output.writeLong(diagnostic.lineNumber());
                        output.writeLong(diagnostic.columnNumber());
                        writeString(output, diagnostic.message());
                        writeString(output, diagnostic.rendering());
                        output.writeInt(compilationDiagnostic.compilationSources().size());
                        for (var source : compilationDiagnostic.compilationSources()) {
                            writeString(output, source.value());
                        }
                    }
                });
    }

    private static List<CompilationDiagnostic> readDiagnostics(DataInputStream input, Set<SourcePath> sources) throws IOException {
        final int markerOrCount;
        try {
            markerOrCount = input.readInt();
        } catch (EOFException oldFormat) {
            return List.of();
        }
        var hasCompilationSources = markerOrCount == DIAGNOSTICS || markerOrCount == RENDERED_DIAGNOSTICS;
        var hasRendering = markerOrCount == RENDERED_DIAGNOSTICS;
        var count = hasCompilationSources ? readCount(input) : requireCount(markerOrCount);
        var diagnostics = new ArrayList<CompilationDiagnostic>(count);
        for (var index = 0; index < count; index++) {
            var kind = Kind.valueOf(readString(input));
            var code = readString(input);
            var source = input.readBoolean() ? new SourcePath(readString(input)) : null;
            var position = input.readLong();
            var startPosition = input.readLong();
            var endPosition = input.readLong();
            var lineNumber = input.readLong();
            var columnNumber = input.readLong();
            var message = readString(input);
            var diagnostic = hasRendering ? new StoredDiagnostic(kind, code, source, position, startPosition, endPosition,
                    lineNumber, columnNumber, message, readString(input))
                    : new StoredDiagnostic(kind, code, source, position, startPosition, endPosition,
                    lineNumber, columnNumber, message, "");
            final Set<SourcePath> compilationSources;
            if (hasCompilationSources) {
                var sourceCount = readCount(input);
                var values = new TreeSet<SourcePath>();
                for (var sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
                    var value = new SourcePath(readString(input));
                    if (!sources.contains(value)) {
                        throw new IOException("Diagnostic source was not compiled: " + value);
                    }
                    if (!values.add(value)) {
                        throw new IOException("Duplicate diagnostic source: " + value);
                    }
                }
                compilationSources = values;
            } else {
                compilationSources = source == null ? sources : Set.of(source);
            }
            diagnostics.add(new CompilationDiagnostic(compilationSources, diagnostic));
        }
        return List.copyOf(diagnostics);
    }

    public static boolean requiresDiagnosticRefresh(State state) {
        return CompilationDiagnostic.diagnostics(state).stream()
                .map(CompilationDiagnostic::diagnostic)
                .anyMatch(diagnostic -> diagnostic.rendering().isEmpty());
    }

    private interface Reader<T> {
        T read(DataInputStream input) throws IOException;
    }

    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    private static <T> Optional<T> read(Path path, int kind, Reader<T> reader) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readInt() != kind) {
                return Optional.empty();
            }
            var value = reader.read(input);
            if (input.read() != -1) {
                throw new IOException("Trailing bytes in manifest: " + path);
            }
            return Optional.of(value);
        } catch (EOFException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static void write(Path path, int kind, Writer writer) throws IOException {
        path = Objects.requireNonNull(path, "path")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(path.getParent());
        var temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(kind);
                writer.write(output);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Set<String> readClassNames(DataInputStream input) throws IOException {
        var count = readCount(input);
        var classes = new TreeSet<String>();
        for (var index = 0; index < count; index++) {
            var name = readString(input);
            if (!classes.add(name)) {
                throw new IOException("Duplicate class: " + name);
            }
        }
        return classes;
    }

    private static void writeClassNames(DataOutputStream output, Set<String> classes) throws IOException {
        output.writeInt(classes.size());
        for (var name : classes) {
            writeString(output, name);
        }
    }

    private static ContentHash readHash(DataInputStream input) throws IOException {
        return new ContentHash(input.readNBytes(ContentHash.LENGTH));
    }

    private static int readCount(DataInputStream input) throws IOException {
        return requireCount(input.readInt());
    }

    private static int requireCount(int count) throws IOException {
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("Invalid manifest entry count: " + count);
        }
        return count;
    }

    private static String readString(DataInputStream input) throws IOException {
        var length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid manifest string length: " + length);
        }
        var bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        var bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
