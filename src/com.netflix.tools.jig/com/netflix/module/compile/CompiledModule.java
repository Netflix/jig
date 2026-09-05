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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;

import com.netflix.module.ModuleHash;
import com.netflix.module.compile.internal.CompiledModuleHash;
import com.netflix.module.compile.internal.SourceModuleOutput.Result;

/**
 * An immutable compiled module represented by a base directory and optional
 * patch.
 */
public final class CompiledModule {
    final Result result;
    private final String configurationIdentity;
    private volatile ModuleHash hash;
    private volatile ModuleHash compilationHash;

    CompiledModule(Result result, String configurationIdentity) {
        this.result = Objects.requireNonNull(result, "result");
        this.configurationIdentity = Objects.requireNonNull(configurationIdentity, "configurationIdentity");
    }

    /**
     * Computes the byte-oriented module hash only when it is requested.
     *
     * @return the content hash of the complete compiled module
     * @throws IOException if the compiled content cannot be read or touched
     */
    public ModuleHash hash() throws IOException {
        touch();
        var value = hash;
        if (value != null) {
            return value;
        }
        synchronized (this) {
            if (hash == null) {
                hash = CompiledModuleHash.of(result.output(), result.patch());
            }
            return hash;
        }
    }

    /**
     * Computes a conservative compile-time API identity. Method bodies and
     * resources do not contribute to this identity.
     *
     * @return the source module's compile-time API identity
     * @throws IOException if the compiled content cannot be read or touched
     */
    public ModuleHash compilationHash() throws IOException {
        touch();
        var value = compilationHash;
        if (value != null) {
            return value;
        }
        synchronized (this) {
            if (compilationHash == null) {
                compilationHash = result.state()
                                        .sources()
                                        .isEmpty()
                        ? hash() : CompiledModuleHash.compilation(result.output(), result.patch(), configurationIdentity,
                        result.state());
            }
            return compilationHash;
        }
    }

    Path output() {
        return result.output();
    }

    Path patch() {
        return result.patch();
    }

    private void touch() throws IOException {
        var time = FileTime.from(Instant.now());
        Files.setLastModifiedTime(result.output(), time);
        if (result.patch() != null) {
            Files.setLastModifiedTime(result.patch(), time);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompiledModule that && result.output().equals(that.result.output()) && Objects.equals(result.patch(), that.result.patch());
    }

    @Override
    public int hashCode() {
        return Objects.hash(result.output(), result.patch());
    }

    @Override
    public String toString() {
        return "CompiledModule[output="
                + result.output()
                + ", patch="
                + result.patch()
                + "]";
    }
}
