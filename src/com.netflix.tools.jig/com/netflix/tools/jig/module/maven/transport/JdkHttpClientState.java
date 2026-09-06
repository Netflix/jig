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

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.Authentication;

/**
 * HTTP clients and their executor shared by transporters in one repository
 * session.
 */
final class JdkHttpClientState implements AutoCloseable {
    private static final Object SESSION_KEY = JdkHttpClientState.class;

    record AuthenticationScope(URI repository, Authentication authentication) {}

    record ProxyProfile(String host, int port, Authentication authentication) {}

    record ClientProfile(
            Version version,
            int connectTimeout,
            SSLContext sslContext,
            boolean insecure,
            InetAddress localAddress,
            AuthenticationScope serverAuthentication,
            ProxyProfile proxy) {}

    static JdkHttpClientState get(RepositorySystemSession session) {
        return (JdkHttpClientState) session.getData().computeIfAbsent(SESSION_KEY,
                () -> {
                    var state = new JdkHttpClientState();
                    try {
                        if (!session.addOnSessionEndedHandler(state::close)) {
                            throw new IllegalArgumentException("Repository session does not support end handlers");
                        }
                    } catch (RuntimeException e) {
                        state.close();
                        throw e;
                    }
                    return state;
                });
    }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ClientProfile, HttpClient> clients = new HashMap<>();
    private boolean closed;

    Executor executor() {
        return executor;
    }

    synchronized HttpClient client(ClientProfile profile, Supplier<HttpClient> factory) {
        if (closed) {
            throw new IllegalStateException("HTTP client state is closed");
        }
        return clients.computeIfAbsent(profile, _ -> factory.get());
    }

    @Override
    public void close() {
        ArrayList<HttpClient> closing;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            closing = new ArrayList<>(clients.values());
            clients.clear();
        }
        RuntimeException failure = null;
        for (HttpClient client : closing) {
            try {
                JdkTransporterCloser.closer(client).run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        try {
            executor.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
