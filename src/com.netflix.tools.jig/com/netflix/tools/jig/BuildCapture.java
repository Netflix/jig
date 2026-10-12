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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** A single versioned transport record, validated before adapter-specific projection. */
final class BuildCapture {
    private final String tool;
    private final Properties values;

    private BuildCapture(String tool, Properties values) {
        this.tool = tool;
        this.values = values;
    }

    static BuildCapture decode(String tool, String encoded) throws IOException {
        var values = new Properties();
        try {
            values.load(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid " + tool + " capture", failure);
        }
        if (!"2".equals(values.getProperty("version"))) {
            throw new IOException("Unsupported " + tool + " capture version: " + values.getProperty("version"));
        }
        var capture = new BuildCapture(tool, values);
        capture.require("kind");
        return capture;
    }

    String require(String name) throws IOException {
        String value = values.getProperty(name);
        if (value == null) {
            throw new IOException("Missing " + tool + " capture entry: " + name);
        }
        return value;
    }

    String optional(String name) {
        return values.getProperty(name);
    }

    void expect(String name, String expected) throws IOException {
        String actual = require(name);
        if (!expected.equals(actual)) {
            throw new IOException("Unexpected " + tool + " capture " + name + ": " + actual + " (expected " + expected + ")");
        }
    }

    boolean flag(String name) throws IOException {
        String value = require(name);
        if (!value.equals("true") && !value.equals("false")) {
            throw new IOException("Invalid " + tool + " capture flag for " + name + ": " + value);
        }
        return Boolean.parseBoolean(value);
    }

    List<String> list(String name) throws IOException {
        String count = require(name + ".count");
        int size;
        try {
            size = Integer.parseInt(count);
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid " + tool + " capture count for " + name + ": " + count, failure);
        }
        if (size < 0 || size > 100_000) {
            throw new IOException("Invalid " + tool + " capture count for " + name + ": " + count);
        }
        var entries = new ArrayList<String>(size);
        for (int index = 0; index < size; index++) {
            entries.add(require(name + "." + index));
        }
        return List.copyOf(entries);
    }
}
