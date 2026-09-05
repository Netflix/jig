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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Content identity of a resolved module or module patch.
 *
 * @param type the kind of content represented by the hash
 * @param algorithm the lowercase digest algorithm name
 * @param digest the encoded digest
 */
public record ModuleHash(Type type, String algorithm, String digest) {

    /** The kind of module content represented by a hash. */
    public enum Type {
        /** A complete module. */
        MODULE("module"),
        /** Content applied with {@code --patch-module}. */
        PATCH("patch");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }

        static Type parse(String value) {
            for (Type type : values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unsupported module hash type: " + value);
        }
    }

    /** Validates and creates a module hash. */
    public ModuleHash {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(digest, "digest");
        if (!algorithm.equals(algorithm.toLowerCase(Locale.ROOT)) || !algorithm.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid module hash algorithm: " + algorithm);
        }
        if (algorithm.equals("sha256") && !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 module hash: " + digest);
        }
        if (digest.isEmpty() || digest.indexOf(':') >= 0 || !digest.equals(digest.strip())) {
            throw new IllegalArgumentException("Invalid module hash digest: " + digest);
        }
    }

    /**
     * Parses the {@code type:algorithm:digest} external form.
     *
     * @param value the external form
     * @return the parsed hash
     * @throws IllegalArgumentException if the value is not a valid module hash
     */
    public static ModuleHash parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] fields = value.split(":", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Invalid module hash: " + value);
        }
        return new ModuleHash(Type.parse(fields[0]), fields[1], fields[2]);
    }

    /**
     * Computes a SHA-256 identity from the names and bytes of a module's resources.
     *
     * @param reference the module to hash
     * @return the module hash
     * @throws IOException if the module or one of its resources cannot be read
     */
    public static ModuleHash moduleSha256(ModuleReference reference) throws IOException {
        Objects.requireNonNull(reference, "reference");
        var digest = sha256();
        byte[] buffer = new byte[32 * 1024];
        try (ModuleReader reader = reference.open();
             Stream<String> resources = reader.list()) {
            resources.sorted().forEach(resource -> {
                digest.update(resource.getBytes(StandardCharsets.UTF_8));
                if (resource.endsWith("/")) {
                    return;
                }
                try (InputStream input = reader.open(resource).orElseThrow()) {
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        digest.update(buffer, 0, read);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return new ModuleHash(Type.MODULE, "sha256",
                HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Computes a SHA-256 identity for a patch archive or directory.
     *
     * @param path the patch archive or directory
     * @return the patch hash
     * @throws IOException if the path does not exist or cannot be read
     */
    public static ModuleHash patchSha256(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        var digest = sha256();
        if (Files.isRegularFile(path)) {
            update(digest, "", Files.readAllBytes(path));
        } else if (Files.isDirectory(path)) {
            try (var paths = Files.walk(path)) {
                for (var file : paths.filter(Files::isRegularFile)
                                     .sorted()
                                     .toList()) {
                    update(
                            digest,
                            path.relativize(file)
                                .toString()
                                .replace('\\', '/'),
                            Files.readAllBytes(file));
                }
            }
        } else {
            throw new IOException("Module patch path does not exist: " + path);
        }
        return new ModuleHash(Type.PATCH, "sha256",
                HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }

    private static void update(MessageDigest digest, String name, byte[] content) {
        var encoded = name.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(encoded.length)
                .array());
        digest.update(encoded);
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(content.length)
                .array());
        digest.update(content);
    }

    /**
     * Reads a hash in external form from a single-line file.
     *
     * @param path the file to read
     * @return the parsed hash
     * @throws IOException if the file cannot be read or does not contain one
     *     valid hash
     */
    public static ModuleHash read(Path path) throws IOException {
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 1 || lines.getFirst().isBlank()) {
            throw new IOException("Module hash must contain exactly one line: " + path);
        }
        try {
            return parse(lines.getFirst());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid module hash " + path, e);
        }
    }

    /**
     * Writes this hash in external form followed by a line terminator.
     *
     * @param path the destination file
     * @throws IOException if the hash cannot be written
     */
    public void write(Path path) throws IOException {
        Files.writeString(path, toString() + "\n", StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return type + ":" + algorithm + ":" + digest;
    }
}
