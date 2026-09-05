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

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Filesystem location for compiled source-module bases and patches. */
public final class SourceModuleOutputs implements AutoCloseable {
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Duration RECONCILIATION_INTERVAL = Duration.ofDays(1);

    private record State(Path base, Path patch) {
        Path lastUsedPath() {
            return patch == null ? base : patch;
        }
    }

    private final Path modules;
    private final Set<String> usedModules = new HashSet<>();

    public SourceModuleOutputs(Path root) throws IOException {
        root = Objects.requireNonNull(root, "root")
                .toAbsolutePath()
                .normalize();
        modules = Files.createDirectories(root.resolve("modules"));
    }

    public synchronized Path moduleDirectory(String moduleName) throws IOException {
        usedModules.add(moduleName);
        return Files.createDirectories(modules.resolve(moduleName));
    }

    /** Expires old outputs belonging to modules compiled through this instance. */
    @Override
    public synchronized void close() throws IOException {
        if (usedModules.isEmpty()) {
            return;
        }
        var now = Instant.now();
        var reconciliationCutoff = FileTime.from(now.minus(RECONCILIATION_INTERVAL));
        var due = usedModules.stream()
                .map(modules::resolve)
                .filter(module -> modified(module).compareTo(reconciliationCutoff) < 0)
                .toList();
        if (!due.isEmpty()) {
            var retentionCutoff = FileTime.from(now.minus(RETENTION));
            var reconciled = FileTime.from(Instant.now());
            for (var module : due) {
                if (modified(module).compareTo(reconciliationCutoff) >= 0) {
                    continue;
                }
                reconcileModule(module, retentionCutoff);
                if (Files.isDirectory(module)) {
                    Files.setLastModifiedTime(module, reconciled);
                }
            }
        }
        usedModules.clear();
    }

    private static void reconcileModule(Path module, FileTime cutoff) throws IOException {
        if (!Files.isDirectory(module)) {
            return;
        }
        var configurations = module.resolve("configuration");
        if (Files.isDirectory(configurations)) {
            try (var paths = Files.list(configurations)) {
                for (var root : paths.filter(Files::isDirectory).toList()) {
                    reconcileRoot(root, cutoff);
                }
            }
        }
        deleteOldDirectories(module.resolve("complete"), cutoff);
        deleteOldFiles(module.resolve("observations"), cutoff);
        removeEmptyDirectories(configurations);
        removeEmptyDirectories(module.resolve("complete"));
        removeEmptyDirectories(module.resolve("observations"));
        removeEmptyDirectories(module);
    }

    private static void reconcileRoot(Path root, FileTime cutoff) throws IOException {
        var states = states(root);
        var retained = new HashSet<Path>();
        for (var state : states) {
            if (modified(state.lastUsedPath()).compareTo(cutoff) >= 0) {
                retained.add(state.base());
                if (state.patch() != null) {
                    retained.add(state.patch());
                }
            }
        }

        for (var state : states) {
            if (state.patch() != null && !retained.contains(state.patch())) {
                deleteTree(state.patch());
            }
        }
        var bases = root.resolve("base");
        if (Files.isDirectory(bases)) {
            try (var paths = Files.list(bases)) {
                for (var base : paths.filter(Files::isDirectory).toList()) {
                    if (!retained.contains(base)) {
                        deleteTree(base);
                    }
                }
            }
        }
        var patches = root.resolve("patch");
        if (Files.isDirectory(patches)) {
            try (var paths = Files.list(patches)) {
                for (var identity : paths.filter(Files::isDirectory).toList()) {
                    try (var baseNames = Files.list(identity)) {
                        for (var baseName : baseNames.filter(Files::isDirectory).toList()) {
                            if (!Files.isDirectory(bases.resolve(baseName.getFileName()
                                    .toString()))) {
                                deleteTree(baseName);
                            }
                        }
                    }
                    removeEmptyDirectories(identity);
                }
            }
        }
        deleteOldStagingDirectories(root, cutoff);
        reconcileCompilationManifests(root);
        deleteOldFiles(root.resolve("reconciliations"), cutoff);
        removeEmptyDirectories(root.resolve("state"));
        removeEmptyDirectories(root.resolve("reconciliations"));
        removeEmptyDirectories(patches);
        removeEmptyDirectories(bases);
    }

    private static List<State> states(Path module) throws IOException {
        var states = new ArrayList<State>();
        var bases = module.resolve("base");
        if (Files.isDirectory(bases)) {
            try (var paths = Files.list(bases)) {
                paths.filter(Files::isDirectory).forEach(base -> states.add(new State(base, null)));
            }
        }
        var patches = module.resolve("patch");
        if (Files.isDirectory(patches)) {
            try (var identities = Files.list(patches)) {
                for (var identity : identities.filter(Files::isDirectory).toList()) {
                    try (var baseNames = Files.list(identity)) {
                        for (var patch : baseNames.filter(Files::isDirectory).toList()) {
                            var base = bases.resolve(patch.getFileName()
                                    .toString());
                            if (Files.isDirectory(base)) {
                                states.add(new State(base, patch));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(states);
    }

    private static void reconcileCompilationManifests(Path root) throws IOException {
        var retained = new HashSet<String>();
        var bases = root.resolve("base");
        if (Files.isDirectory(bases)) {
            try (var paths = Files.list(bases)) {
                paths.filter(Files::isDirectory).forEach(path -> retained.add(path.getFileName()
                        .toString()));
            }
        }
        var patches = root.resolve("patch");
        if (Files.isDirectory(patches)) {
            try (var paths = Files.list(patches)) {
                paths.filter(Files::isDirectory).forEach(path -> retained.add(path.getFileName()
                        .toString()));
            }
        }
        var states = root.resolve("state");
        if (Files.isDirectory(states)) {
            try (var paths = Files.list(states)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    if (!retained.contains(path.getFileName()
                            .toString())) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private static void deleteOldFiles(Path root, FileTime cutoff) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.list(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                if (modified(path).compareTo(cutoff) < 0) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void deleteOldDirectories(Path root, FileTime cutoff) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.list(root)) {
            for (var path : paths.filter(Files::isDirectory).toList()) {
                if (modified(path).compareTo(cutoff) < 0) {
                    deleteTree(path);
                }
            }
        }
    }

    private static void deleteOldStagingDirectories(Path root, FileTime cutoff) throws IOException {
        try (var paths = Files.list(root)) {
            for (var path : paths.filter(Files::isDirectory).toList()) {
                var name = path.getFileName().toString();
                if ((name.startsWith(".compile-") || name.startsWith(".base-")) && modified(path).compareTo(cutoff) < 0) {
                    deleteTree(path);
                }
            }
        }
    }

    private static void removeEmptyDirectories(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return;
        }
        try (var children = Files.list(path)) {
            for (var child : children.filter(Files::isDirectory).toList()) {
                removeEmptyDirectories(child);
            }
        }
        try (var children = Files.list(path)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static FileTime modified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        var deleted = root.resolveSibling("." + root.getFileName() + ".delete-" + UUID.randomUUID());
        try {
            Files.move(root, deleted, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException e) {
            return;
        } catch (AtomicMoveNotSupportedException e) {
            return;
        }
        makeDirectoriesWritable(deleted);
        try (var paths = Files.walk(deleted)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
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
