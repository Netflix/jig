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

package com.netflix.tools.jig.module.maven.transport;

import java.util.Objects;
import java.util.function.Supplier;

import com.netflix.tools.jig.module.maven.transport.AbstractModuleTransporter.ModuleIdentity;

/**
 * Scoped probe for module identity via the Maven transport layer.
 * While an operation runs in its lexical scope, signals {@link JdkTransporter}
 * to attempt a forward range request instead of a full download.
 *
 * <p>On <b>200</b>: the full module artifact flows through Aether's normal
 * caching pipeline. The transporter reads identity from the downloaded bytes
 * and sets the {@linkplain #result() result}.
 *
 * <p>On <b>206</b>: the transporter parses the partial bytes for
 * manifest/module-info, optionally does a suffix range read for the
 * central directory, sets the result, and throws {@link Complete}
 * to prevent Aether from caching partial data.
 *
 * <p>Either way, {@link #call(Supplier)} returns the probe result alongside
 * the operation result, with no branching on success versus exception.
 */
final class MavenModuleProbe {

    private static final ScopedValue<MavenModuleProbe> CURRENT = ScopedValue.newInstance();

    record Outcome<T>(T value, ModuleIdentity identity) {}

    private ModuleIdentity result;

    static <T> Outcome<T> call(Supplier<? extends T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (CURRENT.isBound()) {
            throw new IllegalStateException("MavenModuleProbe is already active");
        }
        var probe = new MavenModuleProbe();
        try {
            var value = ScopedValue.where(CURRENT, probe).call(operation::get);
            return new Outcome<>(value, probe.result);
        } catch (RuntimeException failure) {
            if (probe.result == null) {
                throw failure;
            }
            return new Outcome<>(null, probe.result);
        }
    }

    static boolean isActive() {
        return CURRENT.isBound();
    }

    static void setResult(ModuleIdentity identity) {
        if (CURRENT.isBound()) {
            CURRENT.get().result = identity;
        }
    }

    static boolean hasResult() {
        return CURRENT.isBound() && CURRENT.get().result != null;
    }

    /**
     * Stacktrace-less exception thrown on the 206 path to abort
     * Aether's caching of partial data. The actual result is on
     * the {@link MavenModuleProbe}, not this exception.
     */
    static final class Complete extends Exception {
        Complete() {
            super(null, null, true, false);
        }
    }
}
