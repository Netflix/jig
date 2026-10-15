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
    private BuildCapture metadata;
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
            if (metadata != null) {
                throw new IOException("Duplicate Gradle project capture");
            }
            metadata = capture;
        }
    }

    List<String> projects() throws IOException {
        if (!discovered) {
            throw new IOException("Gradle did not return a capture");
        }
        return List.copyOf(projects);
    }

    String tabSeparatedProjects() throws IOException {
        return ProjectListing.gradleContexts(discoveries, GradleCommandLine.CLASSPATHS);
    }

    BuildCapture metadata() throws IOException {
        if (metadata == null) {
            throw new IOException("Gradle did not return a project capture");
        }
        return metadata;
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
