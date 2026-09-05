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

import java.io.IOException;
import java.lang.module.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.netflix.tools.jig.module.ModuleRepositorySession;
import com.netflix.tools.jig.module.ModuleResolution;
import com.netflix.tools.jig.module.SourceModuleFinder;
import com.netflix.tools.jig.module.SourceModuleReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the canonical Spring Boot source module example —
 * a single-module project with {@code module-info.java} at the source root,
 * resolved against Maven Central.
 */
public class SpringBootSourceModuleTest {

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
    @DisplayName("single-module Spring Boot project resolves from source root")
    void springBootSourceModule(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("module-info.java"),
                """
                /**
                 * @release 21
                 * @mainClass com.example.cli.App
                 */
                open module com.example.cli {
                    requires spring.boot.starter; // @3.4.5
                }
                """);

        var sourceFinder = SourceModuleFinder.of(tempDir);
        var sourceRef = (SourceModuleReference) sourceFinder.find("com.example.cli").orElseThrow();
        assertEquals("com.example.cli", sourceRef.descriptor()
                .name());
        assertEquals(21, sourceRef.sourceModule()
                .release());

        var config = resolve(sourceFinder, "com.example.cli");

        assertTrue(config.findModule("com.example.cli")
                         .isPresent());
        assertTrue(config.findModule("spring.boot.starter")
                         .isPresent());
        assertTrue(config.findModule("spring.boot")
                         .isPresent());
        assertTrue(config.findModule("spring.boot.autoconfigure")
                         .isPresent());

        var springBootStarter = config.findModule("spring.boot.starter").orElseThrow();
        assertEquals(
                "3.4.5",
                springBootStarter.reference()
                                 .descriptor()
                                 .version()
                                 .map(Object::toString)
                                 .orElse(null));
    }

    @Test
    @DisplayName("spring-boot-starter-web resolves full dependency tree from source root")
    void springBootStarterWeb(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("module-info.java"),
                """
                open module com.example.web {
                    requires spring.boot.starter.web; // @3.4.5
                }
                """);

        var sourceFinder = SourceModuleFinder.of(tempDir);
        var config = resolve(sourceFinder, "com.example.web");

        assertTrue(config.findModule("com.example.web")
                         .isPresent());
        assertTrue(config.findModule("spring.boot.starter.web")
                         .isPresent());
        assertTrue(config.findModule("spring.webmvc")
                         .isPresent());
        assertTrue(config.findModule("spring.web")
                         .isPresent());
        assertTrue(config.findModule("spring.boot")
                         .isPresent());
        assertTrue(config.findModule("spring.boot.autoconfigure")
                         .isPresent());
        assertTrue(config.findModule("spring.context")
                         .isPresent());
        assertTrue(config.findModule("spring.core")
                         .isPresent());
        assertTrue(config.findModule("org.apache.tomcat.embed.core")
                         .isPresent());
        assertTrue(config.findModule("com.fasterxml.jackson.databind")
                         .isPresent());
    }

    @Test
    @DisplayName("root-level module-info.java is discovered by SourceModuleFinder")
    void rootLevelModuleDiscovery(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("module-info.java"),
                """
                module simple.root {
                }
                """);

        var finder = SourceModuleFinder.of(tempDir);
        var ref = finder.find("simple.root");
        assertTrue(ref.isPresent(), "should find module-info.java at source path root");
        assertEquals("simple.root",
                ref.get()
                   .descriptor()
                   .name());
    }

    private static Configuration resolve(SourceModuleFinder sourceFinder, String rootModule) {
        return ModuleResolution.resolve(session, sourceFinder, List.of(rootModule), false, false).configuration();
    }
}
