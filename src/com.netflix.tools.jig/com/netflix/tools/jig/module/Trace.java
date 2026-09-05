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

package com.netflix.tools.jig.module;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.ScopedValue.CallableOp;
import java.util.Objects;

/**
 * Shared diagnostic tracing for jig's module resolution and proxy activity.
 * Enabled by an explicit output context or the {@code jig.verbose} system
 * property.
 */
public final class Trace {

    private Trace() {}

    private static final ScopedValue<String> CONTEXT = ScopedValue.newInstance();
    private static final ScopedValue<PrintWriter> OUTPUT = ScopedValue.newInstance();
    private static final PrintWriter SYSTEM_OUTPUT = new PrintWriter(System.err, true);

    /** An operation which may throw a checked exception. */
    @FunctionalInterface
    public interface Operation<X extends Throwable> {
        void run() throws X;
    }

    /** Runs an operation with the context appended to any enclosing context. */
    public static <X extends Throwable> void withContext(String context, Operation<X> operation) throws X {
        withContext(context, () -> {
            operation.run();
            return null;
        });
    }

    /** Calls an operation with the context appended to any enclosing context. */
    public static <T, X extends Throwable> T withContext(String context,
            CallableOp<? extends T, X> operation)
            throws X {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operation, "operation");
        var previous = CONTEXT.orElse("");
        var value = previous.isEmpty() ? context : previous + " " + context;
        return ScopedValue.where(CONTEXT, value).call(operation);
    }

    /** Directs tracing to the given stream while the operation runs. */
    public static <X extends Throwable> void withOutput(PrintStream output, Operation<X> operation) throws X {
        withOutput(new PrintWriter(output, true), operation);
    }

    /** Directs tracing to the given writer while the operation runs. */
    public static <X extends Throwable> void withOutput(PrintWriter output, Operation<X> operation) throws X {
        Objects.requireNonNull(operation, "operation");
        callWithOutput(output, () -> {
            operation.run();
            return null;
        });
    }

    /** Directs tracing to the given writer while the operation is called. */
    public static <T, X extends Throwable> T callWithOutput(PrintWriter output,
            CallableOp<? extends T, X> operation)
            throws X {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(operation, "operation");
        return ScopedValue.where(OUTPUT, output).call(operation);
    }

    /** Directs tracing to the given stream while the operation is called. */
    public static <T, X extends Throwable> T callWithOutput(PrintStream output,
            CallableOp<? extends T, X> operation)
            throws X {
        return callWithOutput(new PrintWriter(output, true), operation);
    }

    public static boolean isEnabled() {
        return OUTPUT.isBound() || Boolean.getBoolean("jig.verbose");
    }

    public static void trace(String fmt, Object... args) {
        PrintWriter output = OUTPUT.isBound() ? OUTPUT.get() : null;
        if (output == null) {
            if (!isEnabled()) {
                return;
            }
            output = SYSTEM_OUTPUT;
        }
        String context = CONTEXT.isBound() ? CONTEXT.get() : null;
        synchronized (output) {
            output.print('[');
            if (context != null) {
                output.print(context);
                output.print(' ');
            }
            output.printf(fmt, args);
            output.println(']');
            output.flush();
        }
    }
}
