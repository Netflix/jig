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
import java.util.ArrayList;
import java.util.List;

import com.netflix.tools.jig.GradleCommandLine.Request;

/** Aggregates discovery records or a selected project's distinct metadata and compiler records. */
final class GradleCapture {
    private final Request request;
    private final List<String> projects = new ArrayList<>();
    private final List<BuildCapture> discoveries = new ArrayList<>();
    private boolean discovered;
    private final List<BuildCapture> metadata = new ArrayList<>();
    private BuildCapture compiler;

    GradleCapture(Request request) {
        this.request = request;
    }

    void accept(BuildCapture capture) throws IOException {
        if (request.listProjects() || capture.require("kind").equals("projects")) {
            capture.expect("kind", "projects");
            capture.require("build-path");
            projects.addAll(capture.list("project-paths"));
            discoveries.add(capture);
            discovered = true;
        } else if (request.compiler() && capture.require("kind").equals("compiler-options")) {
            if (compiler != null) {
                throw new IOException("Duplicate Gradle compiler options capture");
            }
            compiler = capture;
        } else {
            capture.expect("kind", request.compiler() ? "compiler-metadata" : request.listSourceSets() ? "source-sets" : "arguments");
            projects.addAll(capture.list("project-paths"));
            discovered = true;
            if (request.compiler() || request.listSourceSets()) {
                if (!metadata.isEmpty()) {
                    throw new IOException("Duplicate Gradle project capture");
                }
            } else {
                String sourceSet = capture.require("source-set");
                String classpath = capture.require("context-classpath");
                for (var existing : metadata) {
                    if (existing.require("source-set").equals(sourceSet)
                            && existing.require("context-classpath").equals(classpath)) {
                        throw new IOException("Duplicate Gradle project capture: " + sourceSet + "/" + classpath);
                    }
                }
            }
            metadata.add(capture);
        }
    }

    List<String> projects() throws IOException {
        if (!discovered) {
            throw new IOException("Gradle did not return a capture");
        }
        return List.copyOf(projects);
    }

    List<BuildCapture> discoveries() {
        return List.copyOf(discoveries);
    }

    String tabSeparatedProjects() throws IOException {
        return ProjectListing.gradleContexts(discoveries, GradleCommandLine.CLASSPATHS);
    }

    BuildCapture metadata() throws IOException {
        if (metadata.isEmpty()) {
            throw new IOException("Gradle did not return a project capture");
        }
        return request.listSourceSets() || request.compiler()
                ? metadata.getFirst()
                : metadata(request.sourceSet(), request.classpath());
    }

    BuildCapture metadata(String sourceSet, String classpath) throws IOException {
        for (var capture : metadata) {
            if (capture.require("source-set").equals(sourceSet)
                    && (classpath == null || capture.require("context-classpath").equals(classpath))) {
                return capture;
            }
        }
        if (metadata.size() == 1) {
            metadata.getFirst().expect("source-set", sourceSet);
            if (classpath != null) {
                metadata.getFirst().expect("context-classpath", classpath);
            }
        }
        throw new IOException("Gradle did not return project context " + sourceSet
                + (classpath == null ? "" : "/" + classpath));
    }

    List<BuildCapture> metadataCaptures() {
        return List.copyOf(metadata);
    }

    List<String> compilerOptions() throws IOException {
        if (compiler == null) {
            throw new IOException("Missing Gradle compiler options capture");
        }
        compiler.expect("project-path", request.projectPath());
        compiler.expect("source-set", request.sourceSet());
        return compiler.list("compiler-options");
    }
}
