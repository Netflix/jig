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

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.named.DiscriminatingNameMapper;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.named.NameMappers;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.LocalRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscriminatingNameMapperTest {
    private static final AtomicInteger HOSTNAME_LOOKUPS = new AtomicInteger();

    @Test
    void constructionDoesNotResolveTheHostname() {
        HOSTNAME_LOOKUPS.set(0);

        new TrackingNameMapper();

        assertEquals(0, HOSTNAME_LOOKUPS.get());
    }

    @Test
    void resolvesTheHostnameOnceWhenFirstNeeded() {
        HOSTNAME_LOOKUPS.set(0);
        var mapper = new TrackingNameMapper();
        var session = session(Map.of());

        mapper.nameLocks(session, List.of(), List.of());
        mapper.nameLocks(session, List.of(), List.of());

        assertEquals(1, HOSTNAME_LOOKUPS.get());
    }

    @Test
    void configuredDiscriminatorDoesNotResolveTheHostname() {
        HOSTNAME_LOOKUPS.set(0);
        var mapper = new TrackingNameMapper();
        var session = session(Map.of(DiscriminatingNameMapper.CONFIG_PROP_DISCRIMINATOR, "configured"));

        mapper.nameLocks(session, List.of(), List.of());

        assertEquals(0, HOSTNAME_LOOKUPS.get());
    }

    private static RepositorySystemSession session(Map<String, String> configuration) {
        return (RepositorySystemSession) Proxy.newProxyInstance(
                DiscriminatingNameMapperTest.class.getClassLoader(),
                new Class<?>[] {RepositorySystemSession.class},
                (_, method, _) -> switch (method.getName()) {
                    case "getConfigProperties" -> configuration;
                    case "getLocalRepository" -> new LocalRepository(Path.of("repository"));
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static final class TrackingNameMapper extends DiscriminatingNameMapper {
        private TrackingNameMapper() {
            super(NameMappers.gavNameMapper());
        }

        @Override
        protected String getHostname() {
            HOSTNAME_LOOKUPS.incrementAndGet();
            return "host";
        }
    }
}
