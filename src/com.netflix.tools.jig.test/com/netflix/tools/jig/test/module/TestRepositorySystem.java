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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession.CloseableSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilterSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.TransporterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.supplier.RepositorySystemSupplier;
import com.netflix.tools.jig.internal.org.eclipse.aether.supplier.SessionBuilderSupplier;
import com.netflix.tools.jig.internal.org.eclipse.aether.transport.file.FileTransporterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import com.netflix.tools.jig.module.maven.transport.JdkTransporterFactory;
import com.netflix.tools.jig.module.maven.transport.ModuleProbeRepositoryFilterSource;

/**
 * Test helper that provides an Aether {@link RepositorySystem} and session
 * using the resolver supplier directly.
 */
final class TestRepositorySystem {

    record Context(RepositorySystem system, CloseableSession session, List<RemoteRepository> repositories) implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }

    static Context create(Path localRepo, List<RemoteRepository> repositories, Map<String, TransporterFactory> extraTransports) {
        var system = new RepositorySystemSupplier() {
            @Override
            protected Map<String, TransporterFactory> createTransporterFactories() {
                var factories = new HashMap<String, TransporterFactory>();
                factories.put("file", new FileTransporterFactory());
                factories.put(JdkTransporterFactory.NAME, new JdkTransporterFactory(headers -> null));
                factories.putAll(extraTransports);
                return factories;
            }

            @Override
            protected Map<String, RemoteRepositoryFilterSource> createRemoteRepositoryFilterSources() {
                return Map.of(ModuleProbeRepositoryFilterSource.NAME, new ModuleProbeRepositoryFilterSource());
            }
        }.get();

        var session = new SessionBuilderSupplier(system)
                .get()
                .withLocalRepositoryBaseDirectories(localRepo)
                .setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false))
                .build();

        return new Context(system, session, repositories);
    }

    static RemoteRepository centralRepository() {
        return new Builder("central", "default", "https://repo.maven.apache.org/maven2/")
                .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                .setSnapshotPolicy(new RepositoryPolicy(false, null, null))
                .build();
    }

    private TestRepositorySystem() {}
}
