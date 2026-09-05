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
 * Factory for {@link BomLocationTransporter}. Accepts repositories with the
 * {@code jig+location-bom} protocol.
 */
public final class BomLocationTransporterFactory implements TransporterFactory {

    public static final String NAME = "jig-location-bom";

    private RepositorySystem system;
    private RepositorySystemSession backendSession;
    private List<RemoteRepository> repositories;

    public void configure(RepositorySystem system, RepositorySystemSession backendSession, List<RemoteRepository> repositories) {
        this.system = Objects.requireNonNull(system, "system");
        this.backendSession = Objects.requireNonNull(backendSession, "backendSession");
        this.repositories = List.copyOf(repositories);
    }

    @Override
    public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository) throws NoTransporterException {
        if (!"jig+location-bom".equals(repository.getProtocol())) {
            throw new NoTransporterException(repository);
        }
        Objects.requireNonNull(system, "BomLocationTransporterFactory not configured");
        return new BomLocationTransporter(system, backendSession, session, repositories);
    }

    @Override
    public float getPriority() {
        return 0;
    }
}
