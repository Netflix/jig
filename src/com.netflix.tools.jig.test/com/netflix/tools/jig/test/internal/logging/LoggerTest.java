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

package com.netflix.tools.jig.test.internal.logging;

import com.netflix.tools.jig.internal.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggerTest {
    @Test
    void formatsSlf4jStyleArguments() {
        assertEquals("Resolved spring.core at 6.2.6", Logger.format("Resolved {} at {}", new Object[] {"spring.core", "6.2.6"}, 2));
    }

    @Test
    void appendsArgumentsWithoutPlaceholders() {
        assertEquals("Resolution failed spring.core", Logger.format("Resolution failed", new Object[] {"spring.core"}, 1));
    }
}
