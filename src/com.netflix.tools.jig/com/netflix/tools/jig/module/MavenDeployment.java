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

/** A Maven deployment derived from flat, module-named artifacts. */
public final class MavenDeployment implements AutoCloseable {
    private static final Set<String> SYSTEM_MODULE_PREFIXES = Set.of("java.", "jdk.");

    private final Path temporaryDirectory;
    private final List<Artifact> artifacts;

    private MavenDeployment(Path temporaryDirectory, List<Artifact> artifacts) {
        this.temporaryDirectory = temporaryDirectory;
        this.artifacts = List.copyOf(artifacts);
    }

    public static MavenDeployment create(Path artifactDirectory, ModuleRepositorySession session) throws IOException {
        return create(artifactDirectory, session, null);
    }

    public static MavenDeployment create(Path artifactDirectory, ModuleRepositorySession session,
            String automaticModuleVersion) throws IOException {
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
            for (Path mainArtifact : mainArtifacts) {
                ModuleDescriptor descriptor = descriptor(mainArtifact);
                if (descriptor.isAutomatic() && ModuleIdentity.parseJar(mainArtifact).moduleName() == null) {
                    throw new IllegalArgumentException("Automatic module has no Automatic-Module-Name: "
                            + mainArtifact.getFileName());
                }
                Path expected = directory.resolve(descriptor.name() + ".jar");
                if (!mainArtifact.equals(expected)) {
                    throw new IllegalArgumentException("Module JAR must be named " + expected.getFileName() + ": " + mainArtifact);
                }
                if (modules.putIfAbsent(descriptor.name(), descriptor) != null) {
                    throw new IllegalArgumentException("Duplicate module artifact: " + descriptor.name());
                }
            }

            var suppliedPoms = new LinkedHashMap<String, Model>();
            var moduleVersions = new LinkedHashMap<String, String>();
            for (var entry : modules.entrySet()) {
                String moduleName = entry.getKey();
                ModuleDescriptor descriptor = entry.getValue();
                Path modulePom = directory.resolve(moduleName + ".pom");
                Model suppliedPom = Files.isRegularFile(modulePom)
                        ? descriptor.isAutomatic()
                                ? PublicationMetadataReader.readAutomaticModulePom(modulePom)
                                : PublicationMetadataReader.read(modulePom)
                        : null;
                String suppliedVersion = suppliedPom == null ? null : suppliedPom.getVersion();
                String version = descriptor.rawVersion()
                        .orElseGet(() -> descriptor.isAutomatic() && suppliedVersion != null
                                ? suppliedVersion
                                : automaticModuleVersion);
                if (version == null || version.isBlank()) {
                    throw new IllegalArgumentException("Module has no version: " + moduleName);
                }
                suppliedPoms.put(moduleName, suppliedPom);
                moduleVersions.put(moduleName, version);
            }

            for (var entry : modules.entrySet()) {
                String moduleName = entry.getKey();
                ModuleDescriptor descriptor = entry.getValue();
                Path modulePom = directory.resolve(moduleName + ".pom");
                Model suppliedPom = suppliedPoms.get(moduleName);
                String version = moduleVersions.get(moduleName);
                Artifact coordinate = ArtifactCandidates.locationCoordinate(moduleName, version);
                boolean completePom = descriptor.isAutomatic() && isCompletePom(suppliedPom);
                if (completePom) {
                    validateAutomaticModulePom(modulePom, suppliedPom, coordinate,
                            automaticModuleVersion);
                }
                Model consumer = completePom
                        ? automaticModulePom(suppliedPom, moduleVersions, session)
                        : consumerPom(suppliedPom, coordinate, descriptor, modules, moduleVersions,
                                session);
                Path generatedPom = temporaryDirectory.resolve(moduleName + ".pom");
                writePom(consumer, generatedPom);
                artifacts.add(artifact(coordinate, "pom", "", generatedPom));
                artifacts.addAll(moduleArtifacts(directory, coordinate, moduleName, modules.keySet()));
            }

            boolean snapshot = artifacts.getFirst().isSnapshot();
            if (artifacts.stream().anyMatch(artifact -> artifact.isSnapshot() != snapshot)) {
                throw new IllegalArgumentException("Maven artifact directory mixes release and snapshot versions");
            }
            complete = true;
            return new MavenDeployment(temporaryDirectory, artifacts);
        } finally {
            if (!complete) {
                deleteTree(temporaryDirectory);
            }
        }
    }

    public Collection<Artifact> artifacts() {
        return artifacts;
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
                          .filter(path -> !path.getFileName().toString().equals(moduleName + ".pom"))
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
        return references.iterator()
                         .next()
                         .descriptor();
    }

    private static boolean isCompletePom(Model model) {
        return model != null && (model.getGroupId() != null
                || model.getArtifactId() != null
                || model.getVersion() != null
                || model.getPackaging() != null
                || !model.getDependencies().isEmpty());
    }

    private static void validateAutomaticModulePom(Path pom, Model model, Artifact coordinate,
            String requestedVersion) {
        if (!coordinate.getGroupId().equals(model.getGroupId())) {
            throw new IllegalArgumentException(pom.getFileName() + " requires groupId "
                    + coordinate.getGroupId());
        }
        if (!coordinate.getArtifactId().equals(model.getArtifactId())) {
            throw new IllegalArgumentException(pom.getFileName() + " requires artifactId "
                    + coordinate.getArtifactId());
        }
        if (!coordinate.getVersion().equals(model.getVersion())) {
            throw new IllegalArgumentException(pom.getFileName() + " requires version "
                    + coordinate.getVersion());
        }
        if (requestedVersion != null && !requestedVersion.equals(model.getVersion())) {
            throw new IllegalArgumentException(pom.getFileName() + " has version "
                    + model.getVersion() + " but --module-version is " + requestedVersion);
        }
        if (model.getPackaging() != null && !"jar".equals(model.getPackaging())) {
            throw new IllegalArgumentException(pom.getFileName() + " requires packaging jar");
        }
    }

    private static Model automaticModulePom(Model supplied, Map<String, String> localVersions,
            ModuleRepositorySession session) throws IOException {
        var dependencies = new ArrayList<Dependency>();
        for (var dependency : supplied.getDependencies()) {
            if (!ArtifactCandidates.isLocationCoordinate(dependency.getGroupId(),
                    dependency.getArtifactId())) {
                dependencies.add(dependency);
                continue;
            }
            String moduleName = dependency.getArtifactId();
            if (isSystemModule(moduleName)) {
                continue;
            }
            String version = dependency.getVersion();
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("Required module has no version: " + moduleName);
            }
            Artifact coordinate;
            String localVersion = localVersions.get(moduleName);
            if (localVersion == null) {
                coordinate = session.locateModule(moduleName, version);
            } else {
                if (!version.equals(localVersion)) {
                    throw new IllegalArgumentException("Required module "
                            + moduleName
                            + "@"
                            + version
                            + " does not match the artifact directory version "
                            + localVersion);
                }
                coordinate = ArtifactCandidates.locationCoordinate(moduleName, version);
            }
            dependencies.add(Dependency.newBuilder(dependency)
                    .groupId(coordinate.getGroupId())
                    .artifactId(coordinate.getArtifactId())
                    .version(coordinate.getVersion())
                    .build());
        }
        return Model.newBuilder(supplied)
                .dependencies(dependencies)
                .build();
    }

    private static Model consumerPom(Model merged, Artifact coordinate, ModuleDescriptor descriptor,
            Map<String, ModuleDescriptor> localModules, Map<String, String> localVersions,
            ModuleRepositorySession session) throws IOException {
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
                String localVersion = localVersions.get(name);
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
            if (MavenDependency.isOptional(requirement)) {
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
                   .licenses(merged.getLicenses())
                   .developers(merged.getDevelopers())
                   .scm(merged.getScm());
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
            writer.setAddLocationInformation(false);
            writer.write(output, model);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to write " + path, e);
        }
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
