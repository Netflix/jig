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
import java.util.TreeMap;

import com.netflix.tools.jig.CommandLine.ToolOption;

final class ProjectListing {
    static final ToolOption FORMAT = ToolOption.option("--format", "text|tsv", "Project listing format; tsv includes project directories");

    private ProjectListing() {}

    static boolean tabSeparated(String format, boolean listProjects) {
        if (format == null) {
            return false;
        }
        if (!listProjects) {
            throw new IllegalArgumentException("--format applies only to project listing");
        }
        if (!format.equals("text") && !format.equals("tsv")) {
            throw new IllegalArgumentException("--format must be text or tsv");
        }
        return format.equals("tsv");
    }

    static String tsv(List<BuildCapture> captures, String selectorKey) throws IOException {
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
        var output = new StringBuilder();
        projects.forEach((selector, directory) -> output.append(selector).append('\t').append(directory).append('\n'));
        return output.toString();
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
