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
import java.util.List;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.collection.CollectRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactDescriptorResult;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResult;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.DependencyRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.DependencyResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.DependencyResult;
import com.netflix.tools.jig.module.ModuleResolutionFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModuleResolutionFailureTest {
    @Test
    void translatesStructuredAetherResultsWithoutSuppressedForest() {
        var el = new IOException("No version found for required module java.desktop in tomcat-embed-el");
        var core = new IOException("No version found for required module java.desktop in tomcat-embed-core");
        var collectRequest = new CollectRequest();
        var dependencyRequest = new DependencyRequest(collectRequest, null);
        var dependencyResult = new DependencyResult(dependencyRequest).setCollectExceptions(List.of(descriptorFailure("org.apache:tomcat-embed-el:pom:10.1.40", el), descriptorFailure("org.apache:tomcat-embed-core:pom:10.1.40", core)));
        var failure = new DependencyResolutionException(dependencyResult, new RuntimeException("Failed to collect dependencies"));

        ModuleResolutionFailure translated = ModuleResolutionFailure.from(failure);

        assertEquals(
                """
                Failed to resolve module dependencies:
                  No version found for required module java.desktop in tomcat-embed-el
                  No version found for required module java.desktop in tomcat-embed-core""",
                translated.message());
        assertSame(el, translated.cause());
    }

    private static ArtifactDescriptorException descriptorFailure(String coordinates, IOException cause) {
        var artifact = new DefaultArtifact(coordinates);
        var artifactRequest = new ArtifactRequest(artifact, List.of(), null);
        var artifactResult = new ArtifactResult(artifactRequest).addException(ArtifactResult.NO_REPOSITORY, new RuntimeException("Could not transfer artifact", cause));
        var resolution = new ArtifactResolutionException(List.of(artifactResult));
        var descriptorRequest = new ArtifactDescriptorRequest(artifact, List.of(), null);
        var descriptorResult = new ArtifactDescriptorResult(descriptorRequest).addException(resolution);
        return new ArtifactDescriptorException(descriptorResult);
    }
}
