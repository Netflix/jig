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

package com.netflix.tools.jig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.classfile.ClassFile;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.netflix.module.compile.internal.ContentHash;
import com.netflix.tools.jig.module.ModuleRepositorySession;

/** Packages only our Java 8 capture code against the selected Maven runtime. */
final class MavenCaptureExtension {
    private MavenCaptureExtension() {}

    static Path create(Path mavenHome, boolean prepare) throws IOException {
        List<Path> libraries;
        try (var files = Files.list(mavenHome.resolve("lib"))) {
            libraries = files.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        Path core = libraries.stream().filter(path -> path.getFileName().toString().startsWith("maven-core-"))
                .findFirst().orElseThrow(() -> new IOException("Maven core API not found in " + mavenHome));
        byte[] starter;
        try (var jar = new JarFile(core.toFile())) {
            var entry = jar.getJarEntry("org/apache/maven/lifecycle/internal/LifecycleStarter.class");
            if (entry == null) {
                throw new IOException("Maven lifecycle API not found in " + core);
            }
            try (var input = jar.getInputStream(entry)) {
                starter = input.readAllBytes();
            }
        }
        boolean interfaceStarter = ClassFile.of().parse(starter).flags().has(AccessFlag.INTERFACE);
        String source = new String(resource("maven-capture.java.template"), StandardCharsets.UTF_8)
                .replace("LIFECYCLE_ADAPTER", interfaceStarter ? "implements" : "extends");
        byte[] descriptor = resource(prepare ? "maven-build-components.xml" : "maven-components.xml");
        var inputs = new ByteArrayOutputStream();
        inputs.write(source.getBytes(StandardCharsets.UTF_8));
        inputs.write(descriptor);
        inputs.write(Files.readAllBytes(core));
        inputs.write(System.getProperty("java.runtime.version").getBytes(StandardCharsets.UTF_8));
        // This is a compiled code artifact, independent of projects and requests.
        Path directory = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv())
                .resolve("maven/capture").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path artifact = directory.resolve(ContentHash.sha256(inputs.toByteArray()).hex() + ".jar");
        if (Files.isRegularFile(artifact)) {
            return artifact;
        }
        Path temporary = Files.createTempDirectory(directory, "compile-");
        try {
            Path classes = Files.createDirectories(temporary.resolve("classes"));
            compile(source, libraries, classes);
            Path output = temporary.resolve("capture.jar");
            try (var jar = new JarOutputStream(Files.newOutputStream(output)); var files = Files.walk(classes)) {
                entry(jar, "META-INF/plexus/components.xml", descriptor);
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    entry(jar, classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            Files.move(output, artifact, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return artifact;
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static void compile(String source, List<Path> libraries, Path classes) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK compiler is required to prepare the Maven capture extension");
        }
        var input = new SimpleJavaFileObject(URI.create("string:///com/netflix/tools/jig/maven/capture/Capture.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var diagnostics = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> arguments = List.of("--release", "8", "-Xlint:-options", "-proc:none", "-g:none", "-classpath",
                    String.join(System.getProperty("path.separator"), libraries.stream().map(Path::toString).toList()), "-d", classes.toString());
            if (!compiler.getTask(diagnostics, manager, null, arguments, null, List.of(input)).call()) {
                throw new IOException("Cannot compile the Maven capture extension:\n" + diagnostics);
            }
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = MavenCaptureExtension.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Maven capture resource is missing: " + name);
            }
            return input.readAllBytes();
        }
    }

    private static void entry(JarOutputStream output, String name, byte[] content) throws IOException {
        var entry = new JarEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }
}
