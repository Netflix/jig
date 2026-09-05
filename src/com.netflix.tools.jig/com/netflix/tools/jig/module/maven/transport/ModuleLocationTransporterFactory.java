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

import java.util.List;
import java.util.Objects;

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.Transporter;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.TransporterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.transfer.NoTransporterException;

/**
 * Factory for {@link ModuleLocationTransporter}. Accepts repositories with the
 * {@code jig+location-module} protocol. Rejects all other protocols.
 *
 * <p>Must be {@linkplain #configure configured} with a {@link RepositorySystem}
 * and repository list before the first transporter is created. Transporters
 * are created lazily on first resolver access.
 */
public final class ModuleLocationTransporterFactory implements TransporterFactory {

    public static final String NAME = "jig-location-module";

    private RepositorySystem system;
    private RepositorySystemSession backendSession;
    private List<RemoteRepository> repositories;
    private List<RemoteRepository> bomRepositories;

    /**
     * Configures this factory with the resolver infrastructure needed by
     * the transporter. Called once during repository session creation.
     *
     * @param system          the repository system for artifact resolution
     * @param repositories    non-virtual repositories (Central, mirrors) for
     *                        resolving main jars and POMs
     * @param bomRepositories repositories including the BOM location virtual
     *                        repo, for cross-transport BOM lookups
     */
    public void configure(RepositorySystem system, RepositorySystemSession backendSession, List<RemoteRepository> repositories,
                          List<RemoteRepository> bomRepositories) {
        this.system = Objects.requireNonNull(system, "system");
        this.backendSession = Objects.requireNonNull(backendSession, "backendSession");
        this.repositories = List.copyOf(repositories);
        this.bomRepositories = List.copyOf(bomRepositories);
    }

    @Override
    public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository) throws NoTransporterException {
        if (!"jig+location-module".equals(repository.getProtocol())) {
            throw new NoTransporterException(repository);
        }
        Objects.requireNonNull(system, "ModuleLocationTransporterFactory not configured");
        return new ModuleLocationTransporter(system, backendSession, session, repositories, bomRepositories);
    }

    @Override
    public float getPriority() {
        return 0;
    }
}
