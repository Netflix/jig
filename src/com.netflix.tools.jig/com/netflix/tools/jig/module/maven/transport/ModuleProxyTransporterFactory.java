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

/** Creates transporters for the module repository proxy. */
public final class ModuleProxyTransporterFactory implements TransporterFactory {

    public static final String NAME = "jig-module-proxy";

    private RepositorySystem system;
    private RepositorySystemSession backendSession;
    private RepositorySystemSession locationSession;
    private List<RemoteRepository> repositories;

    public void configure(RepositorySystem system, RepositorySystemSession backendSession, RepositorySystemSession locationSession,
                          List<RemoteRepository> repositories) {
        this.system = Objects.requireNonNull(system, "system");
        this.backendSession = Objects.requireNonNull(backendSession, "backendSession");
        this.locationSession = Objects.requireNonNull(locationSession, "locationSession");
        this.repositories = List.copyOf(repositories);
    }

    @Override
    public Transporter newInstance(RepositorySystemSession consumerSession, RemoteRepository repository) throws NoTransporterException {
        if (!"jig+module".equals(repository.getProtocol())) {
            throw new NoTransporterException(repository);
        }
        Objects.requireNonNull(system, "ModuleProxyTransporterFactory not configured");
        return new ModuleProxyTransporter(system, backendSession, locationSession, repositories);
    }

    @Override
    public float getPriority() {
        return 0;
    }
}
