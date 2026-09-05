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

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

/** A diagnostic retained with successful compilation state. */
public record StoredDiagnostic(
        Diagnostic.Kind kind,
        String code,
        SourcePath source,
        long position,
        long startPosition,
        long endPosition,
        long lineNumber,
        long columnNumber,
        String message,
        String rendering) {
    public StoredDiagnostic(
            Diagnostic.Kind kind,
            String code,
            SourcePath source,
            long position,
            long startPosition,
            long endPosition,
            long lineNumber,
            long columnNumber,
            String message) {
        this(kind, code, source, position, startPosition, endPosition,
                lineNumber, columnNumber, message, fallbackRendering(kind, source, lineNumber, message));
    }

    public StoredDiagnostic {
        kind = Objects.requireNonNull(kind, "kind");
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        rendering = Objects.requireNonNull(rendering, "rendering");
    }

    static StoredDiagnostic from(Diagnostic<? extends JavaFileObject> diagnostic, Map<URI, SourcePath> sourcePaths) {
        var diagnosticSource = diagnostic.getSource();
        var source = diagnosticSource == null ? null : sourcePaths.get(diagnosticSource.toUri());
        var code = diagnostic.getCode();
        return new StoredDiagnostic(
                diagnostic.getKind(),
                code == null ? "" : code,
                source,
                diagnostic.getPosition(),
                diagnostic.getStartPosition(),
                diagnostic.getEndPosition(),
                diagnostic.getLineNumber(),
                diagnostic.getColumnNumber(),
                diagnostic.getMessage(null),
                rendering(diagnostic, source));
    }

    private static String rendering(Diagnostic<? extends JavaFileObject> diagnostic, SourcePath source) {
        var rendered = diagnostic.toString();
        var diagnosticSource = diagnostic.getSource();
        if (source == null || diagnosticSource == null) {
            return rendered;
        }
        var sourceName = diagnosticSource.getName();
        return rendered.startsWith(sourceName) ? rendered.substring(sourceName.length()) : fallbackRendering(diagnostic.getKind(), source, diagnostic.getLineNumber(),
                diagnostic.getMessage(null));
    }

    private static String fallbackRendering(Diagnostic.Kind kind, SourcePath source, long lineNumber,
            String message) {
        var label = switch (kind) {
            case ERROR -> "error";
            case WARNING, MANDATORY_WARNING -> "warning";
            case NOTE -> "note";
            case OTHER -> "other";
        };
        var location = source == null || lineNumber < 0
                ? ""
                : ":" + lineNumber;
        return location
                + (source == null ? "" : ": ")
                + label
                + ": "
                + message;
    }

    public Diagnostic<JavaFileObject> at(Path sourceRoot) {
        return at(sourceRoot, true);
    }

    public Diagnostic<JavaFileObject> at(Path sourceRoot, boolean includePreamble) {
        var diagnosticSource = source == null ? null : new SimpleJavaFileObject(sourceRoot.resolve(source.value())
                .toUri(),
                        JavaFileObject.Kind.SOURCE) {};
        return new ReplayedDiagnostic(this, diagnosticSource, includePreamble);
    }

    private record ReplayedDiagnostic(StoredDiagnostic stored, JavaFileObject source, boolean includePreamble) implements Diagnostic<JavaFileObject> {
        @Override
        public Kind getKind() {
            return stored.kind();
        }

        @Override
        public JavaFileObject getSource() {
            return source;
        }

        @Override
        public long getPosition() {
            return stored.position();
        }

        @Override
        public long getStartPosition() {
            return stored.startPosition();
        }

        @Override
        public long getEndPosition() {
            return stored.endPosition();
        }

        @Override
        public long getLineNumber() {
            return stored.lineNumber();
        }

        @Override
        public long getColumnNumber() {
            return stored.columnNumber();
        }

        @Override
        public String getCode() {
            return stored.code();
        }

        @Override
        public String getMessage(Locale locale) {
            return stored.message();
        }

        @Override
        public String toString() {
            var retainedRendering = stored.rendering().isEmpty() ? fallbackRendering(stored.kind(), stored.source(), stored.lineNumber(),
                    stored.message())
                    : stored.rendering();
            var rendering = source == null ? retainedRendering : source.getName() + retainedRendering;
            return includePreamble ? "[diagnostics replayed from previous compilation]" + System.lineSeparator() + rendering : rendering;
        }
    }
}
