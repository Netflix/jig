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

package com.netflix.module;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.SourceVersion;

/** Reads, writes, and verifies detached {@code module-info.hash} files. */
public final class ModuleInfoHash {
    /**
     * A module key in a hash file.
     *
     * @param moduleName the Java module name
     * @param version the version reported by the module descriptor, if present
     */
    public record Coordinate(String moduleName, Optional<ModuleDescriptor.Version> version) implements Comparable<Coordinate> {
        /** Validates and creates a module coordinate. */
        public Coordinate {
            if (!SourceVersion.isName(moduleName)) {
                throw new IllegalArgumentException("Invalid module name: " + moduleName);
            }
            Objects.requireNonNull(version, "version");
        }

        /**
         * Creates an unversioned module coordinate.
         *
         * @param moduleName the Java module name
         */
        public Coordinate(String moduleName) {
            this(moduleName, Optional.empty());
        }

        /**
         * Creates a coordinate by parsing a module version.
         *
         * @param moduleName the Java module name
         * @param version the module version string
         * @throws IllegalArgumentException if the name or version is invalid
         */
        public Coordinate(String moduleName, String version) {
            this(moduleName, Optional.of(ModuleDescriptor.Version.parse(version)));
        }

        static Coordinate parse(String value) {
            var separator = value.lastIndexOf('@');
            if (separator < 0) {
                return new Coordinate(value);
            }
            if (separator == 0 || separator + 1 == value.length()) {
                throw new IllegalArgumentException("Invalid module coordinate: " + value);
            }
            return new Coordinate(value.substring(0, separator), value.substring(separator + 1));
        }

        @Override
        public int compareTo(Coordinate other) {
            return toString().compareTo(other.toString());
        }

        @Override
        public String toString() {
            return moduleName + version.map(value -> "@" + value).orElse("");
        }
    }

    private final Map<Coordinate, ModuleHash> entries;

    private ModuleInfoHash(Map<Coordinate, ModuleHash> entries) {
        this.entries = Map.copyOf(entries);
    }

    /**
     * Reads a detached module hash file. Blank lines and lines beginning with
     * {@code #} are ignored.
     *
     * @param path the hash file
     * @return the hashes in the file
     * @throws IOException if the file cannot be read or contains an invalid entry
     */
    public static ModuleInfoHash read(Path path) throws IOException {
        Map<Coordinate, ModuleHash> entries = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            int equals = line.indexOf('=');
            if (equals <= 0)
                throw new IOException("Invalid hash entry: " + rawLine);
            Coordinate key;
            try {
                key = Coordinate.parse(line.substring(0, equals));
                ModuleHash previous = entries.putIfAbsent(key, ModuleHash.parse(line.substring(equals + 1)));
                if (previous != null)
                    throw new IOException("Duplicate hash entry: " + key);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid hash entry: " + rawLine, e);
            }
        }
        return new ModuleInfoHash(entries);
    }

    /**
     * Writes entries in coordinate order.
     *
     * @param path the destination file
     * @throws IOException if the file cannot be written
     */
    public void write(Path path) throws IOException {
        var content = new StringBuilder();
        entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry ->
                        content.append(entry.getKey())
                                .append('=')
                                .append(entry.getValue())
                                .append('\n'));
        Files.writeString(path, content);
    }

    /**
     * Returns the hash recorded for a coordinate.
     *
     * @param coordinate the coordinate to find
     * @return the recorded hash, or an empty value
     */
    public Optional<ModuleHash> get(Coordinate coordinate) {
        return Optional.ofNullable(entries.get(coordinate));
    }

    /**
     * Returns the hash recorded for an unversioned module name.
     *
     * @param name the module name
     * @return the recorded hash, or an empty value
     */
    public Optional<ModuleHash> get(String name) {
        return get(new Coordinate(name));
    }

    /**
     * Returns the hash recorded for a module name and descriptor version.
     *
     * @param name the module name
     * @param version the module version
     * @return the recorded hash, or an empty value
     */
    public Optional<ModuleHash> get(String name, String version) {
        return get(new Coordinate(name, version));
    }

    /**
     * Tests whether a module has the hash recorded for the coordinate reported by its descriptor.
     *
     * @param reference the resolved module
     * @return {@code true} if an entry exists and matches the module content
     * @throws IOException if the module cannot be read
     */
    public boolean verify(ModuleReference reference) throws IOException {
        String name = reference.descriptor().name();
        var coordinate = new Coordinate(name, reference.descriptor().version());
        Optional<ModuleHash> expected = get(coordinate);
        return expected.isPresent()
                && expected.get().equals(ModuleHash.moduleSha256(reference));
    }

    /**
     * Tests whether a module has the hash recorded for its name and version.
     *
     * @param name the module name
     * @param version the module version
     * @param reference the resolved module
     * @return {@code true} if an entry exists and matches the module content
     * @throws IOException if the module cannot be read
     */
    public boolean verify(String name, String version, ModuleReference reference) throws IOException {
        Optional<ModuleHash> expected = get(name, version);
        return expected.isPresent()
                && expected.get().equals(ModuleHash.moduleSha256(reference));
    }

    /**
     * Returns the recorded coordinates.
     *
     * @return an unmodifiable set of coordinates
     */
    public Set<Coordinate> keys() {
        return entries.keySet();
    }

    /**
     * Returns the hash recorded for a coordinate.
     *
     * @param key the coordinate to find
     * @return the recorded hash, or an empty value
     */
    public Optional<ModuleHash> hashForKey(Coordinate key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Tests whether all observed entries occur unchanged in this set.
     *
     * @param observed the entries that must be covered
     * @return {@code true} if every observed entry is present and equal
     */
    public boolean covers(ModuleInfoHash observed) {
        return entries.entrySet().containsAll(observed.entries.entrySet());
    }

    /**
     * Describes entries which are missing, no longer observed, or changed.
     *
     * @param observed the resolved entries to compare
     * @return immutable descriptions ordered by coordinate
     */
    public List<String> differences(ModuleInfoHash observed) {
        var keys = new TreeSet<Coordinate>(entries.keySet());
        keys.addAll(observed.entries.keySet());
        var differences = new java.util.ArrayList<String>();
        for (Coordinate key : keys) {
            ModuleHash expected = entries.get(key);
            ModuleHash resolved = observed.entries.get(key);
            if (expected == null) {
                differences.add(key + " is not recorded; resolved " + resolved);
            } else if (resolved == null) {
                differences.add(key + " is no longer resolved; expected " + expected);
            } else if (!expected.equals(resolved)) {
                differences.add(key + " changed; expected " + expected + ", resolved " + resolved);
            }
        }
        return List.copyOf(differences);
    }

    /**
     * Returns the number of recorded coordinates.
     *
     * @return the entry count
     */
    public int size() {
        return entries.size();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModuleInfoHash hashes && entries.equals(hashes.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    /**
     * Creates an empty hash-set builder.
     *
     * @return a new builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /** Builds immutable detached module hash sets. */
    public static final class Builder {
        private final Map<Coordinate, ModuleHash> entries = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Records a hash by unversioned module name.
         *
         * @param name the module name
         * @param hash the content hash
         * @return this builder
         */
        public Builder put(String name, ModuleHash hash) {
            return put(new Coordinate(name), hash);
        }

        /**
         * Records a hash by module name and descriptor version.
         *
         * @param name the module name
         * @param version the module version
         * @param hash the content hash
         * @return this builder
         */
        public Builder put(String name, String version, ModuleHash hash) {
            return put(new Coordinate(name, version), hash);
        }

        /**
         * Records a hash by coordinate.
         *
         * @param coordinate the module coordinate
         * @param hash the content hash
         * @return this builder
         */
        public Builder put(Coordinate coordinate, ModuleHash hash) {
            entries.put(coordinate, hash);
            return this;
        }

        /**
         * Computes and records the hash of a module using the coordinate reported by its descriptor.
         *
         * @param reference the resolved module
         * @return this builder
         * @throws IOException if the module cannot be read
         */
        public Builder computeAndPut(ModuleReference reference) throws IOException {
            String name = reference.descriptor().name();
            return put(new Coordinate(name, reference.descriptor().version()), ModuleHash.moduleSha256(reference));
        }

        /**
         * Creates an immutable snapshot of the recorded entries.
         *
         * @return the module hash set
         */
        public ModuleInfoHash build() {
            return new ModuleInfoHash(entries);
        }
    }
}
