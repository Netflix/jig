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
import java.io.InputStream;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ConstantPoolSharingOption;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.SourceDebugExtensionAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import com.netflix.module.compile.internal.SourceModuleCompilation.State;

/** Computes the content identity of compiled module outputs. */
public final class CompiledModuleHash {
    static final String COMPILATION_ALGORITHM = "jig-source-api-1";

    private CompiledModuleHash() {}

    public static ModuleHash of(Path output, Path patch) throws IOException {
        var modules = ModuleFinder.of(output).findAll();
        if (modules.size() != 1) {
            throw new IOException("Compiled output must contain exactly one module: " + output);
        }
        var base = modules.iterator().next();
        return patch == null ? ModuleHash.moduleSha256(base) : ModuleHash.moduleSha256(overlay(base, output, patch));
    }

    public static ModuleHash compilation(Path output, Path patch, String configurationIdentity,
            State state)
            throws IOException {
        var digest = sha256();
        update(digest, "source-module-compilation-api-1");
        update(digest, configurationIdentity);
        update(digest, state.moduleInfoOptionsHash());
        var modulePath = ModulePathCompilation.entries(state.modulePathEntries());
        update(digest, modulePath.size());
        for (var entry : modulePath) {
            update(digest, entry.location()
                                .name());
            update(digest, entry.moduleName());
            update(digest, entry.hash()
                                .toString());
        }
        var files = new TreeMap<String, Path>();
        collect(output, files);
        if (patch != null) {
            collect(patch, files);
        }
        var classes = files.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".class"))
                .toList();
        update(digest, classes.size());
        for (var entry : classes) {
            update(digest, entry.getKey());
            var signature = classSignature(Files.readAllBytes(entry.getValue()));
            update(digest, signature.length);
            digest.update(signature);
        }
        var systemImage = state.systemImage();
        if (systemImage == null) {
            update(digest, 0);
        } else {
            update(digest, 1);
            update(digest, systemImage.path()
                    .toString());
            update(digest, Long.toString(systemImage.size()));
            update(digest, Long.toString(systemImage.modifiedSeconds()));
            update(digest, systemImage.modifiedNanos());
            update(digest, systemImage.fileKey());
        }
        return new ModuleHash(Type.MODULE, COMPILATION_ALGORITHM,
                HexFormat.of().formatHex(digest.digest()));
    }

    private static ModuleReference overlay(ModuleReference base, Path basePath, Path patchPath) throws IOException {
        var files = new TreeMap<String, Path>();
        collect(basePath, files);
        collect(patchPath, files);
        var immutable = Map.copyOf(files);
        return new ModuleReference(base.descriptor(),
                base.location().orElse(null)) {
            @Override
            public ModuleReader open() {
                return new ModuleReader() {
                    @Override
                    public Optional<URI> find(String name) {
                        var path = immutable.get(name);
                        return path == null ? Optional.empty() : Optional.of(path.toUri());
                    }

                    @Override
                    public Optional<InputStream> open(String name) throws IOException {
                        var path = immutable.get(name);
                        return path == null ? Optional.empty() : Optional.of(Files.newInputStream(path));
                    }

                    @Override
                    public Stream<String> list() {
                        return immutable.keySet().stream();
                    }

                    @Override
                    public void close() {}
                };
            }
        };
    }

    private static void collect(Path root, Map<String, Path> files) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(candidate -> !candidate.equals(root)).toList()) {
                var name = root.relativize(path)
                               .toString()
                               .replace('\\', '/');
                if (Files.isDirectory(path)) {
                    name += "/";
                }
                files.put(name, path);
            }
        }
    }

    private static byte[] classSignature(byte[] bytes) {
        var classFile = ClassFile.of(ConstantPoolSharingOption.NEW_POOL);
        var transform = ClassTransform.dropping(element ->
                element instanceof MethodModel method && !api(method.flags())
                        || element instanceof FieldModel field && !api(field.flags())
                        || element instanceof SourceFileAttribute
                        || element instanceof SourceDebugExtensionAttribute)
                .andThen(ClassTransform.transformingMethods(MethodTransform.dropping(CodeModel.class::isInstance)));
        return classFile.transformClass(classFile.parse(bytes), transform);
    }

    private static boolean api(AccessFlags flags) {
        return flags.has(AccessFlag.PUBLIC) || flags.has(AccessFlag.PROTECTED);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }

    private static void update(MessageDigest digest, ContentHash hash) {
        if (hash == null) {
            update(digest, -1);
        } else {
            update(digest, hash.bytes().length);
            hash.update(digest);
        }
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

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value)
                .array());
    }
}
