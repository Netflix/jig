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

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLStreamException;

import com.netflix.tools.jig.internal.org.apache.maven.api.model.Dependency;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxWriter;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;

/** A Maven deployment derived from flat, module-named artifacts. */
public final class MavenDeployment implements AutoCloseable {
    private static final Set<String> SYSTEM_MODULE_PREFIXES = Set.of("java.", "jdk.");

    private final Path temporaryDirectory;
    private final List<Artifact> artifacts;
    private final RemoteRepository deploymentRepository;

    private MavenDeployment(Path temporaryDirectory, List<Artifact> artifacts, RemoteRepository deploymentRepository) {
        this.temporaryDirectory = temporaryDirectory;
        this.artifacts = List.copyOf(artifacts);
        this.deploymentRepository = deploymentRepository;
    }

    public static MavenDeployment create(Path artifactDirectory, Path consumerPom, ModuleRepositorySession session) throws IOException {
        Path directory = artifactDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Maven artifact directory is not a directory: " + directory);
        }
        var mainArtifacts = mainArtifacts(directory);
        if (mainArtifacts.isEmpty()) {
            throw new IllegalArgumentException("Maven artifact directory contains no module JARs: " + directory);
        }

        Path temporaryDirectory = Files.createTempDirectory("jig-maven-deployment-");
        boolean complete = false;
        try {
            var artifacts = new ArrayList<Artifact>();
            var modules = new LinkedHashMap<String, ModuleDescriptor>();
            Model deploymentMetadata = null;
            for (Path mainArtifact : mainArtifacts) {
                ModuleDescriptor descriptor = descriptor(mainArtifact);
                Path expected = directory.resolve(descriptor.name() + ".jar");
                if (!mainArtifact.equals(expected)) {
                    throw new IllegalArgumentException("Module JAR must be named " + expected.getFileName() + ": " + mainArtifact);
                }
                if (modules.putIfAbsent(descriptor.name(), descriptor) != null) {
                    throw new IllegalArgumentException("Duplicate module artifact: " + descriptor.name());
                }
            }

            for (var entry : modules.entrySet()) {
                String moduleName = entry.getKey();
                ModuleDescriptor descriptor = entry.getValue();
                String version = descriptor.rawVersion().orElseThrow(() -> new IllegalArgumentException("Module has no version: " + moduleName));
                Artifact coordinate = ArtifactCandidates.locationCoordinate(moduleName, version);
                Model merged = consumerPom == null ? null : EffectivePomReader.read(consumerPom, coordinate, session);
                if (deploymentMetadata == null) {
                    deploymentMetadata = merged;
                }
                Model consumer = consumerPom(merged, coordinate, descriptor, modules, session);
                Path generatedPom = temporaryDirectory.resolve(moduleName + ".pom");
                writePom(consumer, generatedPom);
                artifacts.add(artifact(coordinate, "pom", "", generatedPom));
                artifacts.addAll(moduleArtifacts(directory, coordinate, moduleName, modules.keySet()));
            }

            boolean snapshot = artifacts.getFirst().isSnapshot();
            if (artifacts.stream().anyMatch(artifact -> artifact.isSnapshot() != snapshot)) {
                throw new IllegalArgumentException("Maven artifact directory mixes release and snapshot versions");
            }
            RemoteRepository repository = deploymentRepository(deploymentMetadata, snapshot);
            complete = true;
            return new MavenDeployment(temporaryDirectory, artifacts, repository);
        } finally {
            if (!complete) {
                deleteTree(temporaryDirectory);
            }
        }
    }

    public Collection<Artifact> artifacts() {
        return artifacts;
    }

    public RemoteRepository deploymentRepository() {
        return deploymentRepository;
    }

    @Override
    public void close() throws IOException {
        deleteTree(temporaryDirectory);
    }

    private static List<Path> mainArtifacts(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                          .filter(path -> path.getFileName()
                                              .toString()
                                              .endsWith(".jar"))
                          .filter(MavenDeployment::isMainArtifact)
                          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                          .toList();
        }
    }

    private static boolean isMainArtifact(Path path) {
        try {
            ModuleDescriptor descriptor = descriptor(path);
            return path.getFileName()
                       .toString()
                       .equals(descriptor.name() + ".jar");
        } catch (RuntimeException _) {
            return false;
        }
    }

    private static List<Artifact> moduleArtifacts(Path directory, Artifact coordinate,
            String moduleName, Set<String> moduleNames) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                          .filter(path -> belongsToModule(path.getFileName().toString(), moduleName, moduleNames))
                          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                          .map(path -> moduleArtifact(coordinate, moduleName, path))
                          .toList();
        }
    }

    private static boolean belongsToModule(String fileName, String moduleName,
            Set<String> moduleNames) {
        if (!hasModulePrefix(fileName, moduleName)) {
            return false;
        }
        return moduleNames.stream()
                          .filter(candidate -> candidate.length() > moduleName.length())
                          .noneMatch(candidate -> hasModulePrefix(fileName, candidate));
    }

    private static boolean hasModulePrefix(String fileName, String moduleName) {
        return fileName.startsWith(moduleName + ".") || fileName.startsWith(moduleName + "-");
    }

    private static Artifact moduleArtifact(Artifact coordinate, String moduleName, Path path) {
        String suffix = path.getFileName()
                            .toString()
                            .substring(moduleName.length());
        String classifier;
        String extension;
        if (suffix.startsWith(".")) {
            classifier = "";
            extension = suffix.substring(1);
        } else {
            int separator = suffix.indexOf('.', 1);
            if (separator < 2) {
                throw new IllegalArgumentException("Module artifact has no extension: " + path);
            }
            classifier = suffix.substring(1, separator);
            extension = suffix.substring(separator + 1);
        }
        if (extension.isEmpty()) {
            throw new IllegalArgumentException("Module artifact has no extension: " + path);
        }
        return artifact(coordinate, extension, classifier, path);
    }

    private static ModuleDescriptor descriptor(Path artifact) {
        var references = ModuleFinder.of(artifact).findAll();
        if (references.size() != 1) {
            throw new IllegalArgumentException("Artifact does not contain exactly one module: " + artifact);
        }
        ModuleDescriptor descriptor = references.iterator()
                .next()
                .descriptor();
        if (descriptor.isAutomatic()) {
            throw new IllegalArgumentException("Artifact does not contain module-info.class: " + artifact);
        }
        return descriptor;
    }

    private static Model consumerPom(Model merged, Artifact coordinate, ModuleDescriptor descriptor,
            Map<String, ModuleDescriptor> localModules, ModuleRepositorySession session)
            throws IOException {
        var dependencies = new ArrayList<Dependency>();
        for (var requirement : descriptor.requires()) {
            String name = requirement.name();
            if (isSystemModule(name)) {
                continue;
            }
            String version = requirement.compiledVersion()
                    .map(Object::toString)
                    .orElseThrow(() -> new IllegalArgumentException("Required module has no compiled version: " + descriptor.name() + " requires " + name));
            Artifact dependencyCoordinate;
            ModuleDescriptor local = localModules.get(name);
            if (local == null) {
                dependencyCoordinate = session.locateModule(name, version);
            } else {
                String localVersion = local.rawVersion().orElseThrow(() -> new IllegalArgumentException("Module has no version: " + name));
                if (!version.equals(localVersion)) {
                    throw new IllegalArgumentException(descriptor.name()
                            + " requires "
                            + name
                            + "@"
                            + version
                            + " but the artifact directory contains "
                            + name
                            + "@"
                            + localVersion);
                }
                dependencyCoordinate = ArtifactCandidates.locationCoordinate(name, version);
            }
            var dependency = Dependency.newBuilder()
                    .groupId(dependencyCoordinate.getGroupId())
                    .artifactId(dependencyCoordinate.getArtifactId())
                    .version(dependencyCoordinate.getVersion())
                    .scope("compile");
            if (requirement.modifiers().contains(Modifier.STATIC)) {
                dependency.optional("true");
            }
            dependencies.add(dependency.build());
        }
        dependencies.sort(Comparator.comparing(Dependency::getGroupId)
                .thenComparing(Dependency::getArtifactId));

        var builder = Model.newBuilder();
        if (merged != null) {
            builder.name(merged.getName())
                   .description(merged.getDescription())
                   .url(merged.getUrl())
                   .inceptionYear(merged.getInceptionYear())
                   .organization(merged.getOrganization())
                   .licenses(merged.getLicenses())
                   .developers(merged.getDevelopers())
                   .contributors(merged.getContributors())
                   .mailingLists(merged.getMailingLists())
                   .prerequisites(merged.getPrerequisites())
                   .scm(merged.getScm())
                   .issueManagement(merged.getIssueManagement())
                   .ciManagement(merged.getCiManagement())
                   .distributionManagement(merged.getDistributionManagement())
                   .properties(merged.getProperties());
        }
        return builder.namespaceUri("http://maven.apache.org/POM/4.0.0")
                      .modelVersion("4.0.0")
                      .groupId(coordinate.getGroupId())
                      .artifactId(coordinate.getArtifactId())
                      .version(coordinate.getVersion())
                      .packaging("jar")
                      .dependencies(dependencies)
                      .build();
    }

    private static Artifact artifact(Artifact coordinate, String extension, String classifier,
            Path path) {
        return new DefaultArtifact(coordinate.getGroupId(), coordinate.getArtifactId(), classifier, extension, coordinate.getVersion())
                .setPath(path);
    }

    private static boolean isSystemModule(String moduleName) {
        return SYSTEM_MODULE_PREFIXES.stream().anyMatch(moduleName::startsWith);
    }

    private static void writePom(Model model, Path path) throws IOException {
        try (var output = Files.newOutputStream(path)) {
            var writer = new MavenStaxWriter();
            writer.setNamespace("http://maven.apache.org/POM/4.0.0");
            writer.write(output, model);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to write " + path, e);
        }
    }

    private static RemoteRepository deploymentRepository(Model model, boolean snapshot) {
        if (model == null || model.getDistributionManagement() == null) {
            return null;
        }
        var repository = snapshot ? model.getDistributionManagement().getSnapshotRepository() : model.getDistributionManagement().getRepository();
        if (repository == null && snapshot) {
            repository = model.getDistributionManagement().getRepository();
        }
        if (repository == null || repository.getId() == null || repository.getUrl() == null) {
            return null;
        }
        return new Builder(repository.getId(), "default", repository.getUrl()).build();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
