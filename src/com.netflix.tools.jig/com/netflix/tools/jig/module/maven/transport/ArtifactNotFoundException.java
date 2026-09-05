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

package com.netflix.tools.jig.module.maven.transport;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;

/**
 * Thrown when an artifact does not exist on any configured repository.
 * Distinct from {@link java.io.IOException} to allow callers to
 * distinguish "not found" from other failures — optional dependencies
 * with missing artifacts can be safely skipped.
 */
public final class ArtifactNotFoundException extends Exception {

    private final Artifact artifact;

    public ArtifactNotFoundException(Artifact artifact) {
        super("Artifact not found: " + artifact);
        this.artifact = artifact;
    }

    public Artifact artifact() {
        return artifact;
    }
}
