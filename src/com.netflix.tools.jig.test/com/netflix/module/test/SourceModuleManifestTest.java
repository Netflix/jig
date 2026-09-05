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

package com.netflix.module.test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic.Kind;
import javax.tools.StandardLocation;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import com.netflix.module.compile.ModulePathEntry;
import com.netflix.module.compile.internal.CompilationDiagnostic;
import com.netflix.module.compile.internal.ContentHash;
import com.netflix.module.compile.internal.SourceCompilation;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;
import com.netflix.module.compile.internal.SourceModuleCompilation.SystemImage;
import com.netflix.module.compile.internal.SourceModuleManifest;
import com.netflix.module.compile.internal.SourceModuleManifest.FileObservation;
import com.netflix.module.compile.internal.SourceModuleManifest.Observations;
import com.netflix.module.compile.internal.SourceModuleManifest.Reconciliation;
import com.netflix.module.compile.internal.SourcePath;
import com.netflix.module.compile.internal.StoredDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceModuleManifestTest {
    @Test
    void recordsConcreteFileObservations(@TempDir Path directory) throws Exception {
        var sourceRoot = directory.resolve("sources");
        var hash = ContentHash.sha256(new byte[] {1});
        var observations = new Observations(sourceRoot, Map.of("p/A.java", new FileObservation(123, 456, 789, "file-key", hash)));
        var manifest = directory.resolve("observations");

        SourceModuleManifest.writeObservations(manifest, observations);

        assertEquals(observations, SourceModuleManifest.readObservations(manifest)
                .orElseThrow());
    }

    @Test
    void recordsSourcePathReconciliationSeparately(@TempDir Path directory) throws Exception {
        var reconciliation = new Reconciliation("compiled-output");
        var manifest = directory.resolve("reconciliation");

        SourceModuleManifest.writeReconciliation(manifest, reconciliation);

        assertEquals(reconciliation, SourceModuleManifest.readReconciliation(manifest)
                .orElseThrow());
    }

    @Test
    void recordsNullSourceDiagnosticsInEncounterOrder(@TempDir Path directory) throws Exception {
        var source = new SourcePath("p/A.java");
        var secondSource = new SourcePath("p/B.java");
        var hash = ContentHash.sha256(new byte[] {1});
        var diagnostics = List.of(
                new StoredDiagnostic(Kind.WARNING, "source-warning", secondSource, 1, 1, 1,
                        1, 1, "source warning"),
                new StoredDiagnostic(Kind.NOTE, "module-note", null, -1, -1, -1,
                        -1, -1, "module note"),
                new StoredDiagnostic(Kind.WARNING, "second-source-warning", source, 2, 2, 2,
                        2, 2, "second source warning"));
        var state = new State(
                Map.of(
                        source,
                        new SourceCompilation(source, hash, hash, Set.of("p.A"),
                                Map.of()),
                        secondSource,
                        new SourceCompilation(secondSource, hash, hash, Set.of("p.B"),
                                Map.of())),
                List.of(),
                Map.of(),
                null,
                null,
                diagnostics.stream()
                        .map(diagnostic ->
                                new CompilationDiagnostic(Set.of(diagnostic.source() == null ? source : diagnostic.source()), diagnostic))
                        .toList());
        var manifest = directory.resolve("compilation-diagnostics");

        SourceModuleManifest.writeCompilation(manifest, state);

        assertEquals(state, SourceModuleManifest.readCompilation(manifest)
                .orElseThrow());
    }

    @Test
    void recordsCompilationFactsOnceForTheModule(@TempDir Path directory) throws Exception {
        var source = new SourcePath("p/A.java");
        var sourceHash = ContentHash.sha256(new byte[] {1});
        var observedHash = ContentHash.sha256(new byte[] {3});
        var dependencyHash = new ModuleHash(Type.MODULE, "sha256", "04".repeat(32));
        var state = new State(
                Map.of(
                        source,
                        new SourceCompilation(source, sourceHash, observedHash, Set.of("p.A"),
                                Map.of("jrt:/java.base/java/lang/Object.class", ContentHash.sha256(new byte[] {6})))),
                List.of(new ModulePathEntry("dependency.module", StandardLocation.MODULE_PATH, dependencyHash)),
                Map.of("resource.txt", ContentHash.sha256(new byte[] {5})),
                new SystemImage(directory.resolve("lib/modules"), 123, 456, 789, "file-key"));
        var manifest = directory.resolve("compilation");

        SourceModuleManifest.writeCompilation(manifest, state);

        assertEquals(state, SourceModuleManifest.readCompilation(manifest)
                .orElseThrow());
    }
}
