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

import java.net.URI;
import java.util.List;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemSignatureOriginTest {
    @Test
    void defaultCompilationUsesTheSystemImage() throws Exception {
        var origin = origin("java.lang.String", List.of());

        assertEquals("jrt", origin.getScheme());
        assertEquals("/java.base/java/lang/String.class", origin.getPath());
    }

    @Test
    void releaseCompilationUsesCtSym() throws Exception {
        var origin = origin("java.lang.String", List.of("--release", "25"));

        assertEquals("jar", origin.getScheme());
        assertTrue(origin.toString().contains("/lib/ct.sym!/") && origin.toString().endsWith("/java.base/java/lang/String.sig"));
    }

    @Test
    void exportedSystemPackageUsesTheSystemImage() throws Exception {
        var origin = origin("jdk.internal.misc.Unsafe", List.of("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"));

        assertEquals("jrt", origin.getScheme());
        assertEquals("/java.base/jdk/internal/misc/Unsafe.class", origin.getPath());
    }

    private static URI origin(String binaryName, List<String> options) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var source = new SimpleJavaFileObject(URI.create("memory:///Example.java"), Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return "class Example { String value; }";
                }
            };
            var task = (JavacTask) compiler.getTask(null, files, null, options, null,
                    List.of(source));
            task.parse();
            task.analyze();
            var elements = task.getElements();
            var type = elements.getTypeElement(binaryName);
            return elements.getFileObjectOf(type).toUri();
        }
    }
}
