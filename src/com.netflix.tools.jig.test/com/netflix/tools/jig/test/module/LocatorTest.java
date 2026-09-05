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
import java.util.SequencedSet;

import com.netflix.tools.jig.module.ModuleRepositorySession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocatorTest {

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
    @DisplayName("bare candidate, reverse-DNS, explicit module (org.junit.jupiter.api)")
    void bareExplicitModule() {
        var result = ModuleTestSupport.resolve(session, "org.junit.jupiter.api", "5.12.2");

        assertNotNull(result);
        assertEquals("org.junit.jupiter.api", result.descriptor()
                .name());
        assertFalse(result.descriptor()
                          .isAutomatic());
    }

    @Test
    @DisplayName("bare candidate, reverse-DNS explicit module (org.yaml.snakeyaml)")
    void bareAutomaticModule() {
        var result = ModuleTestSupport.resolve(session, "org.yaml.snakeyaml", "2.3");

        assertNotNull(result);
        assertEquals("org.yaml.snakeyaml", result.descriptor()
                .name());
        assertFalse(result.descriptor()
                          .isAutomatic());
    }

    @Test
    @DisplayName("bare candidate, deep nesting with no BOM available (org.apache.tomcat.embed.el)")
    void bareDeepNesting() {
        var result = ModuleTestSupport.resolve(session, "org.apache.tomcat.embed.el", "10.1.40");

        assertNotNull(result);
        assertEquals("org.apache.tomcat.embed.el", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("BOM fallback, heuristic miss falls to jar verify (org.slf4j via slf4j-bom)")
    void bomFallbackSlf4j() {
        var result = ModuleTestSupport.resolve(session, "org.slf4j", "2.0.17");

        assertNotNull(result);
        assertEquals("org.slf4j", result.descriptor()
                .name());
        assertFalse(result.descriptor()
                          .isAutomatic());
        // SLF4J 2.x uses ServiceLoader for SLF4JServiceProvider
        assertTrue(result.descriptor()
                         .uses()
                         .contains("org.slf4j.spi.SLF4JServiceProvider"),
                "should have uses for SLF4JServiceProvider, but uses: " + result.descriptor().uses());
    }

    @Test
    @DisplayName("BOM fallback (com.fasterxml.jackson.databind via jackson-bom)")
    void bomJacksonDatabind() {
        var result = ModuleTestSupport.resolve(session, "com.fasterxml.jackson.databind", "2.18.3");

        assertNotNull(result);
        assertEquals("com.fasterxml.jackson.databind", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("prefix alias, non-reverse-DNS (spring.core → org.springframework:spring-core)")
    void prefixAliasSpringCore() {
        var result = ModuleTestSupport.resolve(session, "spring.core", "6.2.7");

        assertNotNull(result);
        assertEquals("spring.core", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("prefix alias, nested (spring.boot → org.springframework.boot:spring-boot)")
    void prefixAliasSpringBoot() {
        var result = ModuleTestSupport.resolve(session, "spring.boot", "3.4.5");

        assertNotNull(result);
        assertEquals("spring.boot", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("provides scanning from META-INF/services (org.slf4j.simple)")
    void providesScanningFromMetaInfServices() {
        var result = ModuleTestSupport.resolve(session, "org.slf4j.simple", "2.0.17");

        assertNotNull(result);
        ModuleDescriptor descriptor = result.descriptor();
        boolean providesSLF4J = descriptor.provides().stream()
                .anyMatch(p -> p.service().equals("org.slf4j.spi.SLF4JServiceProvider"));
        assertTrue(providesSLF4J, "should provide SLF4JServiceProvider, but provides: " + descriptor.provides());
    }

    @Test
    @DisplayName("converted module uses are serialized in module-info.class")
    void usesAreSerialized() {
        var result = ModuleTestSupport.resolve(session, "org.slf4j", "2.0.17");

        assertNotNull(result);
        ModuleDescriptor descriptor = result.descriptor();
        assertTrue(descriptor.uses().contains("org.slf4j.spi.SLF4JServiceProvider"),
                "uses should be serialized, but got: " + descriptor.uses());
    }

    @Test
    @DisplayName("bare candidate verifies module name matches (ch.qos.logback.classic)")
    void bareVerifiesModuleName() {
        var result = ModuleTestSupport.resolve(session, "ch.qos.logback.classic", "1.5.18");

        assertNotNull(result);
        assertEquals("ch.qos.logback.classic", result.descriptor()
                .name());
    }

    // -- Relocations --

    @Test
    @DisplayName("relocation: org.objectweb.asm → org.ow2.asm:asm")
    void relocationAsm() {
        var result = ModuleTestSupport.resolve(session, "org.objectweb.asm", "9.7.1");

        assertNotNull(result);
        assertEquals("org.objectweb.asm", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("relocation: com.google.common → com.google.guava:guava")
    void relocationGuava() {
        var result = ModuleTestSupport.resolve(session, "com.google.common", "33.4.8-jre");

        assertNotNull(result);
        assertEquals("com.google.common", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("relocation: org.apache.commons.cli → commons-cli:commons-cli")
    void relocationCommonsCli() {
        var result = ModuleTestSupport.resolve(session, "org.apache.commons.cli", "1.9.0");

        assertNotNull(result);
        assertEquals("org.apache.commons.cli", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("relocation: org.apache.commons.codec → commons-codec:commons-codec")
    void relocationCommonsCodec() {
        var result = ModuleTestSupport.resolve(session, "org.apache.commons.codec", "1.17.2");

        assertNotNull(result);
        assertEquals("org.apache.commons.codec", result.descriptor()
                .name());
    }

    @Test
    @DisplayName("listVersions: explicit module (org.junit.jupiter.api)")
    void listVersionsExplicit() {
        SequencedSet<String> versions = session.listVersions("org.junit.jupiter.api");

        assertFalse(versions.isEmpty(), "should have versions");
        assertTrue(versions.contains("5.12.2"), "should contain 5.12.2");
    }

    @Test
    @DisplayName("listVersions: relocated module (org.slf4j)")
    void listVersionsRelocated() {
        SequencedSet<String> versions = session.listVersions("org.slf4j");

        assertFalse(versions.isEmpty(), "should have versions");
        assertTrue(versions.contains("2.0.17"), "should contain 2.0.17");
    }

    @Test
    @DisplayName("listVersions: explicit artifact alias (jopt.simple)")
    void listVersionsExplicitArtifactAlias() {
        SequencedSet<String> versions = session.listVersions("jopt.simple");

        assertFalse(versions.isEmpty(), "should have versions");
        assertTrue(versions.contains("5.0.4"), "should contain 5.0.4");
    }
}
