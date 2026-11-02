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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.netflix.module.compile.internal.ContentHash;
import com.netflix.tools.jig.GradleCommandLine.Request;
import com.netflix.tools.jig.module.ModuleRepositorySession;

/** Queries the explicitly selected Gradle build using its own runtime. */
final class GradleCommands {
    private static final String CAPTURE_PREFIX = "jig-gradle:";
    private static final String CAPTURE_TASK = "_jigCapture";
    private static final Map<String, ReentrantLock> PROJECT_CAPTURES = new ConcurrentHashMap<>();

    private GradleCommands() {}

    static int run(PrintWriter out, PrintWriter err, String[] arguments) {
        if (arguments.length == 0 || Arrays.stream(arguments).anyMatch(argument -> argument.equals("--help") || argument.equals("-h"))) {
            out.print(GradleCommandLine.help());
            return 0;
        }
        Request request;
        try {
            request = GradleCommandLine.parse(arguments);
        } catch (IllegalArgumentException e) {
            err.println("jig gradle: " + e.getMessage());
            return 2;
        }
        ReentrantLock projectCapture = allContexts(request)
                ? PROJECT_CAPTURES.computeIfAbsent(projectCaptureKey(request), ignored -> new ReentrantLock())
                : null;
        if (projectCapture != null) {
            projectCapture.lock();
        }
        try {
            var launcher = BuildProcess.launcher(request.root(), "gradle", "gradlew", "gradle.bat", "gradlew.bat");
            var cache = new ProjectQueryCache("Gradle", request.root(), "projects", launcher, "gradle-init.gradle");
            boolean allContexts = allContexts(request);
            String query = request.listProjects() ? "projects" : request.listSourceSets() ? "source-sets\n" + request.projectPath()
                    : request.compiler() ? "compiler\n" + request.projectPath() + '\n' + request.sourceSet()
                    : "arguments\n" + request.projectPath() + (allContexts ? "" : "\n" + request.sourceSet() + "\n" + request.classpath())
                            + '\n' + request.options().stream().sorted().toList() + '\n' + request.moduleForm() + '\n' + request.lenient();
            var resultCache = request.listProjects() ? cache : new ProjectQueryCache("Gradle", request.root(), query, launcher, "gradle-init.gradle");
            if (!request.listProjects()) {
                if (request.refresh()) {
                    resultCache.clear();
                }
                var result = request.refresh() ? List.<BuildCapture>of() : resultCache.read();
                if (!result.isEmpty()) {
                    if (request.verbose()) {
                        err.println("jig gradle: cache hit");
                    }
                    if (request.listSourceSets()) {
                        result.getFirst().list("source-sets").stream().distinct().sorted().forEach(out::println);
                    } else {
                        var selected = cachedArguments(result, request);
                        write(out, request.argumentFile(), JavaArgumentFile.render(selected.list("arguments")));
                    }
                    return 0;
                }
            }
            if (request.refresh()) {
                cache.clear();
            }
            var cached = request.refresh() ? List.<BuildCapture>of() : cache.read();
            if (!cached.isEmpty()) {
                if (request.verbose() && (request.listProjects() || request.listSourceSets())) {
                    err.println("jig gradle: cache hit");
                }
                var projects = ProjectListing.projects(cached, "project-paths");
                if (request.listProjects()) {
                    if (request.tabSeparated()) {
                        out.print(ProjectListing.gradleContexts(cached, GradleCommandLine.CLASSPATHS));
                    } else {
                        projects.keySet().forEach(out::println);
                    }
                    return 0;
                }
                if (!projects.containsKey(request.projectPath())) {
                    throw new IllegalArgumentException("Unknown project path in this build: " + request.projectPath());
                }
                var names = new TreeSet<String>();
                for (var capture : cached) {
                    var owners = capture.list("source-set-projects");
                    var sets = capture.list("source-sets");
                    for (int index = 0; index < owners.size(); index++) {
                        if (owners.get(index).equals(request.projectPath())) {
                            names.add(sets.get(index));
                        }
                    }
                }
                if (request.listSourceSets()) {
                    names.forEach(out::println);
                    return 0;
                }
                if (!names.contains(request.sourceSet())) {
                    throw new IllegalArgumentException("Unknown source set in project " + request.projectPath() + ": " + request.sourceSet());
                }
            }
            if (request.verbose()) {
                err.println("jig gradle: cache miss");
            }
            var captures = new GradleCapture(request);
            CaptureResult capture = capture(request, initScript(), captures, err);
            boolean partial = capture.status() != 0 && allContexts
                    && captures.containsMetadata(request.sourceSet(), request.classpath());
            if (capture.status() != 0 && !partial) {
                capture.diagnostics().forEach(err::println);
                return capture.status();
            }
            if (!partial || request.verbose()) {
                capture.diagnostics().forEach(err::println);
            }
            List<String> projects = captures.projects();
            if (request.listProjects()) {
                cache.store(captures.discoveries());
                if (request.tabSeparated()) {
                    out.print(captures.tabSeparatedProjects());
                } else {
                    projects.stream().distinct().sorted().forEach(out::println);
                }
            } else {
                if (!projects.contains(request.projectPath())) {
                    throw new IllegalArgumentException("Unknown project path in this build: " + request.projectPath());
                }
                BuildCapture metadata = captures.metadata();
                metadata.expect("project-path", request.projectPath());
                List<String> sourceSets = metadata.list("source-sets");
                if (request.listSourceSets()) {
                    resultCache.store(List.of(metadata));
                    sourceSets.stream().distinct().sorted().forEach(out::println);
                } else if (request.compiler()) {
                    if (!sourceSets.contains(request.sourceSet())) {
                        throw new IllegalArgumentException("Unknown source set in project " + request.projectPath() + ": " + request.sourceSet());
                    }
                    metadata.expect("source-set", request.sourceSet());
                    var resolved = captures.compilerOptions();
                    var inputs = new ArrayList<>(captures.discoveries());
                    inputs.add(metadata);
                    resultCache.store(List.of(BuildCapture.arguments("Gradle", resolved, inputs)));
                    write(out, request.argumentFile(), JavaArgumentFile.render(resolved));
                } else {
                    if (!sourceSets.contains(request.sourceSet())) {
                        throw new IllegalArgumentException("Unknown source set in project " + request.projectPath() + ": " + request.sourceSet());
                    }
                    var cachedArguments = new ArrayList<BuildCapture>();
                    List<String> selected = null;
                    for (var context : captures.metadataCaptures()) {
                        context.expect("project-path", request.projectPath());
                        String sourceSet = context.require("source-set");
                        String classpath = context.require("context-classpath");
                        var contextRequest = contextRequest(request, sourceSet, classpath);
                        var resolved = GradleArguments.resolve(contextRequest, GradleArguments.View.read(context));
                        var inputs = new ArrayList<>(captures.discoveries());
                        inputs.add(context);
                        cachedArguments.add(BuildCapture.arguments("Gradle", resolved, inputs, Map.of(
                                "project-path", request.projectPath(), "source-set", sourceSet, "context-classpath", classpath)));
                        if (sourceSet.equals(request.sourceSet()) && classpath.equals(request.classpath())) {
                            selected = resolved;
                        }
                    }
                    if (selected == null) {
                        throw new IOException("Gradle did not return the requested project context");
                    }
                    if (partial) {
                        var inputs = new ArrayList<>(captures.discoveries());
                        inputs.add(metadata);
                        String diagnostics = String.join(System.lineSeparator(), capture.diagnostics());
                        for (String sourceSet : sourceSets) {
                            for (String classpath : GradleCommandLine.CLASSPATHS) {
                                if (!captures.containsMetadata(sourceSet, classpath)) {
                                    String failure = "Gradle could not capture project context " + request.projectPath() + "/"
                                            + sourceSet + "/" + classpath + System.lineSeparator() + diagnostics;
                                    cachedArguments.add(BuildCapture.arguments("Gradle", List.of(), inputs, Map.of(
                                            "project-path", request.projectPath(), "source-set", sourceSet,
                                            "context-classpath", classpath, "failure", failure)));
                                }
                            }
                        }
                    }
                    resultCache.store(cachedArguments);
                    write(out, request.argumentFile(), JavaArgumentFile.render(selected));
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("jig gradle: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("jig gradle: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("jig gradle: interrupted");
            return 1;
        } finally {
            if (projectCapture != null) {
                projectCapture.unlock();
            }
        }
    }

    private static BuildCapture cachedArguments(List<BuildCapture> captures, Request request) throws IOException {
        if (request.compiler()) {
            return captures.getFirst();
        }
        for (var capture : captures) {
            if (request.projectPath().equals(capture.optional("project-path"))
                    && request.sourceSet().equals(capture.optional("source-set"))
                    && request.classpath().equals(capture.optional("context-classpath"))) {
                String failure = capture.optional("failure");
                if (failure != null) {
                    throw new IOException(failure);
                }
                return capture;
            }
        }
        throw new IOException("Cached Gradle project model does not contain " + request.projectPath() + "/"
                + request.sourceSet() + "/" + request.classpath());
    }

    private static Request contextRequest(Request request, String sourceSet, String classpath) {
        return new Request(request.root(), request.projectPath(), sourceSet, classpath, false, false, false, false,
                request.options(), request.moduleForm(), request.argumentFile(), request.lenient(), request.verbose(), request.refresh());
    }

    private static boolean allContexts(Request request) {
        return !request.listProjects() && !request.listSourceSets() && !request.compiler()
                && (request.options().contains("source-path") || request.options().contains("module-source-path"));
    }

    private static String projectCaptureKey(Request request) {
        return request.root().toAbsolutePath().normalize() + "\n" + request.projectPath() + "\n"
                + request.options().stream().sorted().toList() + "\n" + request.moduleForm() + "\n" + request.lenient();
    }

    private static void write(PrintWriter out, Path file, String arguments) throws IOException {
        if (file == null) {
            out.print(arguments);
        } else {
            Files.writeString(file, arguments);
        }
    }

    private static Path initScript() throws IOException {
        byte[] script;
        try (var resource = GradleCommands.class.getResourceAsStream("gradle-init.gradle")) {
            if (resource == null) {
                throw new IOException("Gradle capture init script is missing");
            }
            script = resource.readAllBytes();
        }
        // The content-addressed script path stays stable for Gradle's configuration cache.
        Path directory = ModuleRepositorySession.cacheDirectory(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv())
                .resolve("gradle").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path init = directory.resolve(ContentHash.sha256(script).hex() + ".gradle");
        if (!Files.exists(init)) {
            Path temporary = Files.createTempFile(directory, "capture-", ".tmp");
            try {
                Files.write(temporary, script);
                Files.move(temporary, init, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return init;
    }

    private static CaptureResult capture(Request request, Path init, GradleCapture captures, PrintWriter err)
            throws IOException, InterruptedException {
        var arguments = new ArrayList<>(BuildProcess.launcher(request.root(), "gradle", "gradlew", "gradle.bat", "gradlew.bat"));
        arguments.addAll(
                List.of(
                        "--project-dir",
                        request.root().toString(),
                        "--init-script",
                        init.toString(),
                        "--console",
                        "plain",
                        "--quiet",
                        "-Dorg.gradle.unsafe.isolated-projects=false",
                        "-Pjig.gradle.root=" + request.root(),
                        "-Pjig.gradle.list=" + request.listProjects(),
                        "-Pjig.gradle.list-contexts=" + request.listProjects(),
                        "-Pjig.gradle.list-source-sets=" + request.listSourceSets(),
                        "-Pjig.gradle.all-contexts=" + allContexts(request)));
        if (!request.listProjects()) {
            arguments.add("-Pjig.gradle.project-path=" + request.projectPath());
        }
        if (!request.listProjects() && !request.listSourceSets()) {
            arguments.add("-Pjig.gradle.compiler=" + request.compiler());
            arguments.add("-Pjig.gradle.source-set=" + request.sourceSet());
            arguments.add("-Pjig.gradle.classpath=" + request.classpath());
            arguments.add("-Pjig.gradle.options=" + String.join(",", request.options().stream().sorted().toList()));
            arguments.add("-Pjig.gradle.lenient=" + request.lenient());
        }
        if (allContexts(request)) {
            arguments.add("--continue");
        }
        arguments.add(":" + CAPTURE_TASK);
        if (request.verbose()) {
            err.println("jig gradle: " + arguments);
        }
        var diagnostics = new ArrayList<String>();
        int status = BuildProcess.run(request.root(), arguments, line -> {
            int captureStart = line.indexOf(CAPTURE_PREFIX);
            if (captureStart >= 0) {
                if (captureStart > 0) {
                    diagnostics.add(line.substring(0, captureStart));
                }
                captures.accept(BuildCapture.decode("Gradle", line.substring(captureStart + CAPTURE_PREFIX.length())));
            } else {
                diagnostics.add(line);
            }
        });
        return new CaptureResult(status, List.copyOf(diagnostics));
    }

    private record CaptureResult(int status, List<String> diagnostics) {}
}
