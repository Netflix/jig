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

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.metadata.Metadata;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilter;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilterSource;

/**
 * Stops repository fallback after a partial module probe has produced its
 * result.
 */
public final class ModuleProbeRepositoryFilterSource implements RemoteRepositoryFilterSource {

    public static final String NAME = "jig-module-probe";

    private static final Result ACCEPT = new Result(true, "module probe requires repository access");
    private static final Result REJECT = new Result(false, "module probe already completed");

    @Override
    public RemoteRepositoryFilter getRemoteRepositoryFilter(RepositorySystemSession session) {
        return new RemoteRepositoryFilter() {
            @Override
            public Result acceptArtifact(RemoteRepository repository, Artifact artifact) {
                return MavenModuleProbe.hasResult() ? REJECT : ACCEPT;
            }

            @Override
            public Result acceptMetadata(RemoteRepository repository, Metadata metadata) {
                return ACCEPT;
            }
        };
    }

    private record Result(boolean isAccepted, String reasoning) implements RemoteRepositoryFilter.Result {}
}
