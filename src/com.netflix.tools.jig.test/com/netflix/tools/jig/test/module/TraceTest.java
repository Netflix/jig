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

package com.netflix.tools.jig.test.module;

import java.io.PrintWriter;
import java.io.StringWriter;

import com.netflix.tools.jig.module.Trace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceTest {

    @Test
    void nestedContextsRetainTheRequestContext() {
        var output = new StringWriter();

        Trace.withOutput(new PrintWriter(output, true),
                () -> Trace.withContext("proxy 7", () -> Trace.withContext("locate com.example", () -> Trace.trace("map source -> target"))));

        assertEquals("[proxy 7 locate com.example map source -> target]\n", output.toString());
    }

    @Test
    void messagesWithoutContextMatchCompilerStyle() {
        var output = new StringWriter();

        Trace.withOutput(new PrintWriter(output, true), () -> Trace.trace("selected %s", "com.example"));

        assertEquals("[selected com.example]\n", output.toString());
    }
}
