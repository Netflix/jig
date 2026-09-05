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

package com.netflix.tools.jig.module;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.xml.stream.XMLStreamException;

import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.Dependency;
import com.netflix.tools.jig.internal.org.apache.maven.model.Parent;
import com.netflix.tools.jig.internal.org.apache.maven.model.Repository;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.DefaultModelBuilderFactory;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.DefaultModelBuildingRequest;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.FileModelSource;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelBuildingException;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelBuildingRequest;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelSource;
import com.netflix.tools.jig.internal.org.apache.maven.model.io.DefaultModelReader;
import com.netflix.tools.jig.internal.org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import com.netflix.tools.jig.internal.org.apache.maven.model.resolution.InvalidRepositoryException;
import com.netflix.tools.jig.internal.org.apache.maven.model.resolution.ModelResolver;
import com.netflix.tools.jig.internal.org.apache.maven.model.resolution.UnresolvableModelException;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.ArtifactDescriptorUtils;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;

/**
 * Reads an effective Maven model using Maven's inheritance and interpolation
 * rules.
 */
final class EffectivePomReader {
    private EffectivePomReader() {}

    static Model read(Path pom, Artifact coordinate, ModuleRepositorySession session) throws IOException {
        Path absolute = pom.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("POM is not a file: " + absolute);
        }
        var raw = new DefaultModelReader().read(absolute.toFile(), Map.of());
        raw.setGroupId(coordinate.getGroupId());
        raw.setArtifactId(coordinate.getArtifactId());
        raw.setVersion(coordinate.getVersion());
        raw.setPackaging("jar");
        var request = new DefaultModelBuildingRequest()
                .setRawModel(raw)
                .setPomFile(absolute.toFile())
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_1)
                .setProcessPlugins(false)
                .setSystemProperties(System.getProperties())
                .setUserProperties(new Properties())
                .setModelResolver(new Resolver(session));
        com.netflix.tools.jig.internal.org.apache.maven.model.Model effective;
        try {
            effective = new DefaultModelBuilderFactory()
                    .newInstance()
                    .build(request)
                    .getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw new IOException("Failed to build effective Maven model from " + absolute, e);
        }

        var encoded = new ByteArrayOutputStream();
        new MavenXpp3Writer().write(encoded, effective);
        try (var input = new ByteArrayInputStream(encoded.toByteArray())) {
            return new MavenStaxReader().read(input);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to convert effective Maven model from " + absolute, e);
        }
    }

    private static final class Resolver implements ModelResolver {
        private final ModuleRepositorySession session;
        private final List<RemoteRepository> repositories;

        private Resolver(ModuleRepositorySession session) {
            this(session, session.pomRepositories());
        }

        private Resolver(ModuleRepositorySession session, List<RemoteRepository> repositories) {
            this.session = session;
            this.repositories = new ArrayList<>(repositories);
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
            try {
                return new FileModelSource(session.resolvePom(groupId, artifactId, version, repositories));
            } catch (IOException e) {
                throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
            }
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            String version = resolveVersion(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
            parent.setVersion(version);
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), version);
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            String version = resolveVersion(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
            dependency.setVersion(version);
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), version);
        }

        @Override
        public void addRepository(Repository repository) throws InvalidRepositoryException {
            addRepository(repository, false);
        }

        @Override
        public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
            if (repository.getId() == null || repository.getUrl() == null) {
                throw new InvalidRepositoryException("Repository requires an id and URL", repository);
            }
            int existing = indexOf(repository.getId());
            if (existing >= 0 && !replace) {
                return;
            }
            RemoteRepository configured = session.configurePomRepository(ArtifactDescriptorUtils.toRemoteRepository(repository));
            if (existing >= 0) {
                repositories.set(existing, configured);
            } else {
                repositories.add(configured);
            }
        }

        @Override
        public ModelResolver newCopy() {
            return new Resolver(session, repositories);
        }

        private String resolveVersion(String groupId, String artifactId, String version) throws UnresolvableModelException {
            try {
                return session.resolvePomVersion(groupId, artifactId, version, repositories);
            } catch (IOException e) {
                throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
            }
        }

        private int indexOf(String id) {
            for (int i = 0; i < repositories.size(); i++) {
                if (repositories.get(i)
                                .getId()
                                .equals(id)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
