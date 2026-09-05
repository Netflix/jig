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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorResult;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeResult;
import com.netflix.tools.jig.test.module.TestRepositorySystem.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests that readArtifactDescriptor follows real Maven relocations
 * published on Central, and that versions() returns versions for the
 * relocated artifact.
 */
public class RelocationFollowingTest {

    private static Context context;

    @TempDir
    private static Path localRepo;

    @BeforeAll
    static void setUp() {
        context = TestRepositorySystem.create(localRepo, List.of(TestRepositorySystem.centralRepository()), Map.of());
    }

    @AfterAll
    static void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("readArtifactDescriptor follows mysql-connector-java relocation on Central")
    void realRelocationFollowed() throws Exception {
        // mysql:mysql-connector-java:8.0.33 has a real relocation POM on Central
        // pointing to com.mysql:mysql-connector-j
        var request = new ArtifactDescriptorRequest(new DefaultArtifact("mysql", "mysql-connector-java", "jar", "8.0.33"),
                context.repositories(), null);
        ArtifactDescriptorResult result = context.system().readArtifactDescriptor(context.session(), request);

        assertEquals("com.mysql", result.getArtifact().getGroupId(),
                "should follow relocation to com.mysql");
        assertEquals("mysql-connector-j", result.getArtifact().getArtifactId(),
                "should follow relocation to mysql-connector-j");
    }

    @Test
    @DisplayName("versions available after following real relocation")
    void versionsAfterRelocation() throws Exception {
        // Follow the relocation first
        var descRequest = new ArtifactDescriptorRequest(new DefaultArtifact("mysql", "mysql-connector-java", "jar", "8.0.33"),
                context.repositories(), null);
        ArtifactDescriptorResult desc = context.system().readArtifactDescriptor(context.session(), descRequest);

        // Get versions for the relocated artifact
        var rangeRequest = new VersionRangeRequest(desc.getArtifact().setVersion("[0,)"),
                context.repositories(), null);
        VersionRangeResult range = context.system().resolveVersionRange(context.session(), rangeRequest);

        assertFalse(range.getVersions()
                         .isEmpty(),
                "should find versions for relocated artifact");
    }

    private static RemoteRepository centralRepository() {
        return new Builder("central", "default", "https://repo.maven.apache.org/maven2/")
                .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                .setSnapshotPolicy(new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                .build();
    }
}
