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

import java.util.List;

/** Encodes arguments for the JDK tools' argument-file tokenizer. */
final class JavaArgumentFile {
    private JavaArgumentFile() {}

    static String render(List<String> arguments) {
        var output = new StringBuilder();
        arguments.forEach(argument -> append(output, argument));
        return output.toString();
    }

    static void append(StringBuilder output, String argument) {
        if (argument.isEmpty() || argument.chars().anyMatch(character -> Character.isWhitespace(character)
                || character == '\\' || character == '"' || character == '\'' || character == '#')) {
            appendQuoted(output, argument);
        } else {
            output.append(argument).append('\n');
        }
    }

    private static void appendQuoted(StringBuilder output, String argument) {
        output.append('"');
        for (int index = 0; index < argument.length(); index++) {
            char character = argument.charAt(index);
            switch (character) {
                case '\\', '"' -> output.append('\\').append(character);
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                case '\f' -> output.append("\\f");
                default -> output.append(character);
            }
        }
        output.append('"').append('\n');
    }
}
