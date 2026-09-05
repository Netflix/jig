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

import java.net.URI;
import java.util.Optional;

import com.netflix.tools.jig.module.MavenArtifactOrigin;
import com.netflix.tools.jig.module.ModuleOrigin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenArtifactOriginTest {
    @Test
    void parsesMavenPackageUrl() {
        var origin = assertInstanceOf(MavenArtifactOrigin.class, ModuleOrigin.parse("pkg:maven/org.apache.commons/commons-configuration2@2.15.1"));

        assertEquals(new MavenArtifactOrigin("org.apache.commons", "commons-configuration2", "2.15.1"), origin);
    }

    @Test
    void parsesUnversionedMavenPackageUrlForLatestLookup() {
        var origin = assertInstanceOf(MavenArtifactOrigin.class, ModuleOrigin.parse("pkg:maven/com.example/example-library"));

        assertEquals("com.example", origin.groupId());
        assertEquals("example-library", origin.artifactId());
        assertTrue(origin.latest());
        assertEquals("pkg:maven/com.example/example-library", origin.toString());
    }

    @Test
    void parsesRepositoryUrl() {
        var origin = assertInstanceOf(MavenArtifactOrigin.class,
                ModuleOrigin
                        .parse("""
                        pkg:maven/com.example/example%2Dlibrary@1.0%2E0?\
                        repository_url=https%3A%2F%2Frepository.example.com%2Fmaven%2F
                        """
                                .strip()));

        assertEquals("example-library", origin.artifactId());
        assertEquals("1.0.0", origin.version());
        assertEquals(Optional.of(URI.create("https://repository.example.com/maven/")), origin.repositoryUrl());
        assertEquals(
                "pkg:maven/com.example/example-library@1.0.0?" + "repository_url=https%3A%2F%2Frepository.example.com%2Fmaven%2F",
                origin.toString());
    }

    @Test
    void plusIsNotDecodedAsSpace() {
        var origin = assertInstanceOf(MavenArtifactOrigin.class, ModuleOrigin.parse("pkg:maven/com.example/example+library@1.0.0"));

        assertEquals("example+library", origin.artifactId());
    }

    @Test
    void rejectsUnsupportedLookupUrls() {
        assertThrows(IllegalArgumentException.class, () -> ModuleOrigin.parse("https://example.com/library.jar"));
        assertThrows(IllegalArgumentException.class, () -> ModuleOrigin.parse("pkg:npm/example@1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> ModuleOrigin.parse("pkg:maven/com.example/"));
        assertThrows(IllegalArgumentException.class, () -> ModuleOrigin.parse("pkg:maven/com.example/example@1.0.0?classifier=tests"));
        assertThrows(IllegalArgumentException.class, () -> ModuleOrigin.parse("pkg:maven/com.example/example@1.0.0?type=test-jar"));
        assertThrows(IllegalArgumentException.class, () -> ModuleOrigin.parse("pkg:maven/com.example/example@1.0.0#path"));
    }

    @Test
    void rejectsCredentialsInRepositoryUrl() {
        assertThrows(IllegalArgumentException.class,
                () ->
                        ModuleOrigin
                                .parse("""
                        pkg:maven/com.example/example@1.0.0?\
                        repository_url=https%3A%2F%2Fuser%3Apassword%40repository.example.com%2Fmaven
                        """
                                        .strip()));
    }
}
