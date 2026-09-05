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

package com.netflix.tools.jig.test.module;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.List;

import com.netflix.tools.jig.module.ModuleRepositorySession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BomResolutionTest {

    private static ModuleRepositorySession session;

    @TempDir
    private static Path localRepo;

    @BeforeAll
    static void setUp() {
        session = ModuleRepositorySession.create(localRepo, List.of(ModuleRepositorySession.centralRepository()));
    }

    @AfterAll
    static void tearDown() {
        session.close();
    }

    @Test
    void slf4jResolvesViaBom() {
        // org.slf4j bare candidate is org.slf4j:slf4j which doesn't exist
        // BOM path: org.slf4j:slf4j-bom -> finds slf4j-api
        var result = ModuleTestSupport.resolve(session, "org.slf4j", "2.0.17");

        assertNotNull(result, "should find org.slf4j via BOM");
        ModuleDescriptor descriptor = result.descriptor();
        assertEquals("org.slf4j", descriptor.name());
        assertFalse(descriptor.isAutomatic());
    }

    @Test
    void jacksonDatabindResolvesViaBom() {
        // com.fasterxml.jackson.databind -> candidates won't include jackson-databind
        // BOM path: com.fasterxml.jackson:jackson-bom -> finds jackson-databind
        var result = ModuleTestSupport.resolve(session, "com.fasterxml.jackson.databind", "2.18.3");

        assertNotNull(result, "should find com.fasterxml.jackson.databind via BOM");
        ModuleDescriptor descriptor = result.descriptor();
        assertEquals("com.fasterxml.jackson.databind", descriptor.name());
    }

    @Test
    void jacksonAnnotationResolvesViaBom() {
        // jackson-annotations lives at com.fasterxml.jackson.core, not discoverable
        // via candidates — requires BOM walk with module name verification
        var result = ModuleTestSupport.resolve(session, "com.fasterxml.jackson.annotation", "2.18.3");

        assertNotNull(result, "should find com.fasterxml.jackson.annotation via BOM");
        ModuleDescriptor descriptor = result.descriptor();
        assertEquals("com.fasterxml.jackson.annotation", descriptor.name());
        assertFalse(descriptor.isAutomatic());
    }
}
