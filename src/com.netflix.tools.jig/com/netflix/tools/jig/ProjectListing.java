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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.netflix.tools.jig.CommandLine.ToolOption;

final class ProjectListing {
    static final ToolOption FORMAT = ToolOption.option("--format", "text|tsv|tsv-contexts",
            "Project listing format; tsv includes directories, tsv-contexts puts directories first and includes native contexts");

    enum Format {
        TEXT,
        TSV,
        TSV_CONTEXTS
    }

    private ProjectListing() {}

    static Format format(String value, boolean listProjects) {
        if (value == null) {
            return Format.TEXT;
        }
        if (!listProjects) {
            throw new IllegalArgumentException("--format applies only to project listing");
        }
        return switch (value) {
            case "text" -> Format.TEXT;
            case "tsv" -> Format.TSV;
            case "tsv-contexts" -> Format.TSV_CONTEXTS;
            default -> throw new IllegalArgumentException("--format must be text or tsv or tsv-contexts");
        };
    }

    static String tsv(List<BuildCapture> captures, String selectorKey) throws IOException {
        var output = new StringBuilder();
        projects(captures, selectorKey).forEach((selector, directory) -> output.append(selector).append('\t').append(directory).append('\n'));
        return output.toString();
    }

    static String mavenContexts(List<BuildCapture> captures, List<String> scopes) throws IOException {
        var rows = new TreeSet<String>();
        for (var project : projects(captures, "projects").entrySet()) {
            if (!project.getKey().matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")) {
                throw new IOException("Invalid Maven project selector: " + project.getKey());
            }
            for (String scope : scopes) {
                rows.add(project.getValue() + '\t' + project.getKey() + '\t' + scope);
            }
        }
        return render(rows);
    }

    static String gradleContexts(List<BuildCapture> captures, List<String> classpaths) throws IOException {
        var projects = projects(captures, "project-paths");
        var sourceSets = new TreeMap<String, Set<String>>();
        for (var capture : captures) {
            var capturedProjects = projects(List.of(capture), "project-paths");
            var capturedSets = new TreeMap<String, Set<String>>();
            capturedProjects.keySet().forEach(selector -> capturedSets.put(selector, new TreeSet<>()));
            List<String> owners = capture.list("source-set-projects");
            List<String> names = capture.list("source-sets");
            if (owners.size() != names.size()) {
                throw new IOException("Source sets and project selectors have different counts");
            }
            for (int index = 0; index < names.size(); index++) {
                String owner = owners.get(index);
                String name = names.get(index);
                validateField(owner, "source set project");
                validateField(name, "source set");
                if (name.isBlank()) {
                    throw new IOException("Empty project source set");
                }
                var selected = capturedSets.get(owner);
                if (selected == null) {
                    throw new IOException("Source set project is not in the Gradle capture: " + owner);
                }
                selected.add(name);
            }
            for (var project : capturedSets.entrySet()) {
                Set<String> previous = sourceSets.putIfAbsent(project.getKey(), project.getValue());
                if (previous != null && !previous.equals(project.getValue())) {
                    throw new IOException("Conflicting Gradle source sets for " + project.getKey());
                }
            }
        }
        var rows = new TreeSet<String>();
        for (var project : projects.entrySet()) {
            String selector = project.getKey();
            if (!selector.startsWith(":") || selector.contains("::") || selector.length() > 1 && selector.endsWith(":")) {
                throw new IOException("Invalid Gradle project selector: " + selector);
            }
            String prefix = project.getValue() + '\t' + selector + '\t';
            Set<String> names = sourceSets.get(selector);
            if (names.isEmpty()) {
                // Keep projects without Java source sets visible; empty fields are not defaults.
                rows.add(prefix + '\t');
            } else {
                for (String name : names) {
                    for (String classpath : classpaths) {
                        rows.add(prefix + name + '\t' + classpath);
                    }
                }
            }
        }
        return render(rows);
    }

    private static String render(Set<String> rows) {
        return rows.isEmpty() ? "" : String.join("\n", rows) + '\n';
    }

    private static Map<String, String> projects(List<BuildCapture> captures, String selectorKey) throws IOException {
        var projects = new TreeMap<String, String>();
        for (var capture : captures) {
            List<String> selectors = capture.list(selectorKey);
            List<String> directories = capture.list("project-directories");
            if (selectors.size() != directories.size()) {
                throw new IOException("Project selectors and directories have different counts");
            }
            for (int index = 0; index < selectors.size(); index++) {
                String selector = selectors.get(index);
                String directory = directories.get(index);
                validateField(selector, "selector");
                validateField(directory, "directory");
                try {
                    if (!Path.of(directory).isAbsolute()) {
                        throw new IOException("Invalid project directory: expected an absolute path");
                    }
                } catch (InvalidPathException failure) {
                    throw new IOException("Invalid project directory", failure);
                }
                String previous = projects.putIfAbsent(selector, directory);
                if (previous != null && !previous.equals(directory)) {
                    throw new IOException("Conflicting project directory for " + selector);
                }
            }
        }
        return projects;
    }

    private static void validateField(String value, String name) throws IOException {
        if (value.isEmpty()) {
            throw new IOException("Empty project " + name);
        }
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Cannot list project " + name + " as TSV: contains a tab or newline");
        }
    }
}
