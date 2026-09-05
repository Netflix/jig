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

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.module.ArtifactCandidates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CanonicalCoordinateTest {

    @Test
    void reverseDns() {
        Artifact a = ArtifactCandidates.locationCoordinate("com.fasterxml.jackson.databind", null);
        assertEquals("com.fasterxml", a.getGroupId());
        assertEquals("com.fasterxml.jackson.databind", a.getArtifactId());
        assertEquals("pom", a.getExtension());
    }

    @Test
    void virtualLocation() {
        Artifact a = ArtifactCandidates.moduleLocationCoordinate("com.fasterxml.jackson.databind", null);
        assertEquals("com.fasterxml.module", a.getGroupId());
        assertEquals("com.fasterxml.jackson.databind", a.getArtifactId());
    }

    @Test
    void deeper() {
        Artifact a = ArtifactCandidates.locationCoordinate("org.apache.logging.log4j", null);
        assertEquals("org.apache", a.getGroupId());
        assertEquals("org.apache.logging.log4j", a.getArtifactId());
    }

    @Test
    void topDomain() {
        Artifact a = ArtifactCandidates.locationCoordinate("org.slf4j", null);
        assertEquals("org.slf4j", a.getGroupId());
        assertEquals("org.slf4j", a.getArtifactId());
    }

    @Test
    void codeHostAlias() {
        Artifact a = ArtifactCandidates.locationCoordinate("io.github.openfeign.feign.core", null);
        assertEquals("io.github.openfeign", a.getGroupId());
        assertEquals("io.github.openfeign.feign.core", a.getArtifactId());
    }

    @Test
    void prefixAliasSpring() {
        Artifact a = ArtifactCandidates.locationCoordinate("spring.core", null);
        assertEquals("org.springframework", a.getGroupId());
        assertEquals("spring.core", a.getArtifactId());
    }

    @Test
    void prefixAliasKotlin() {
        Artifact a = ArtifactCandidates.locationCoordinate("kotlin.stdlib", null);
        assertEquals("org.jetbrains", a.getGroupId());
        assertEquals("kotlin.stdlib", a.getArtifactId());
    }

    @Test
    void prefixAliasLombok() {
        Artifact a = ArtifactCandidates.locationCoordinate("lombok", null);
        assertEquals("org.projectlombok", a.getGroupId());
        assertEquals("lombok", a.getArtifactId());
    }

    @Test
    void bare() {
        Artifact a = ArtifactCandidates.locationCoordinate("junit", null);
        assertEquals("junit", a.getGroupId());
        assertEquals("junit", a.getArtifactId());
    }
}
