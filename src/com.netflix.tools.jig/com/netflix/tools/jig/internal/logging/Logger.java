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

package com.netflix.tools.jig.internal.logging;

import java.lang.System.Logger.Level;
import java.util.Objects;

public final class Logger {
    private final System.Logger logger;

    Logger(System.Logger logger) {
        this.logger = Objects.requireNonNull(logger);
    }

    public boolean isTraceEnabled() {
        return logger.isLoggable(Level.TRACE);
    }

    public boolean isDebugEnabled() {
        return logger.isLoggable(Level.DEBUG);
    }

    public void trace(String message, Object... arguments) {
        log(Level.TRACE, message, arguments);
    }

    public void debug(String message, Object... arguments) {
        log(Level.DEBUG, message, arguments);
    }

    public void info(String message, Object... arguments) {
        log(Level.INFO, message, arguments);
    }

    public void warn(String message, Object... arguments) {
        log(Level.WARNING, message, arguments);
    }

    private void log(Level level, String message, Object[] arguments) {
        if (!logger.isLoggable(level)) {
            return;
        }
        Throwable thrown = arguments.length > placeholders(message) && arguments[arguments.length - 1] instanceof Throwable throwable
                ? throwable
                : null;
        int argumentCount = thrown == null ? arguments.length : arguments.length - 1;
        String formatted = format(message, arguments, argumentCount);
        if (thrown == null) {
            logger.log(level, formatted);
        } else {
            logger.log(level, formatted, thrown);
        }
    }

    private static int placeholders(String message) {
        int count = 0;
        for (int index = 0;
             (index = message.indexOf("{}", index)) >= 0;
             index += 2) {
            count++;
        }
        return count;
    }

    public static String format(String message, Object[] arguments, int argumentCount) {
        var result = new StringBuilder(message.length() + argumentCount * 8);
        int offset = 0;
        int argument = 0;
        while (argument < argumentCount) {
            int placeholder = message.indexOf("{}", offset);
            if (placeholder < 0) {
                break;
            }
            result.append(message, offset, placeholder).append(arguments[argument++]);
            offset = placeholder + 2;
        }
        result.append(message, offset, message.length());
        while (argument < argumentCount) {
            result.append(' ').append(arguments[argument++]);
        }
        return result.toString();
    }
}
