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
import java.lang.module.FindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.xml.stream.XMLStreamException;

import com.netflix.module.ModuleInfoHash;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Build;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Dependency;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Parent;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Resource;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;

/**
 * Generates module-info.pom files and a Maven reactor for selected source
 * modules.
 */
public final class ModulePomGenerator {
    private static final String GROUP_ID = "local";
    private static final String VERSION = "0";

    private ModulePomGenerator() {}

    public static void generate(ModuleResolution resolution, Path projectDirectory, Path moduleRepository) throws IOException {
        var sourceModules = ConsumerPomGenerator.selectedSourceModules(resolution);
        if (sourceModules.isEmpty()) {
            throw new FindException("No source modules selected for POM generation");
        }

        Path project = projectDirectory.toAbsolutePath().normalize();
        Files.createDirectories(project);
        var moduleDirectories = sourceModules.entrySet().stream()
                .sorted(Entry.comparingByKey())
                .map(
                        entry -> Map.entry(entry.getKey(),
                                entry.getValue()
                                     .sourceDirectory()
                                     .toAbsolutePath()
                                     .normalize()))
                .toList();
        for (var entry : moduleDirectories) {
            if (!entry.getValue().startsWith(project)) {
                throw new IllegalArgumentException("Module POM root must contain source module " + entry.getKey() + ": " + entry.getValue());
            }
        }

        String projectName = project.getFileName() == null ? "project" : project.getFileName().toString();
        Path rootPom = project.resolve("pom.xml");
        Model existing = readPom(rootPom);
        String parentGroupId = existing == null || existing.getGroupId() == null
                ? inheritedGroupId(existing)
                : existing.getGroupId();
        String parentArtifactId = existing == null || existing.getArtifactId() == null
                ? projectName + "-parent"
                : existing.getArtifactId();
        String parentVersion = existing == null || existing.getVersion() == null
                ? inheritedVersion(existing)
                : existing.getVersion();
        var build = Build.newBuilder()
                .sourceDirectory("${project.basedir}")
                .resources(List.of(Resource.newBuilder()
                        .directory("${project.basedir}")
                        .filtering("false")
                        .excludes(List.of("**/*.java", "module-info.hash", "module-info.pom", "pom.xml", "target/**"))
                        .build()))
                .build();
        var properties = new LinkedHashMap<String, String>();
        if (existing != null) {
            properties.putAll(existing.getProperties());
        }
        properties.putIfAbsent("maven.compiler.release", Integer.toString(Runtime.version()
                .feature()));
        properties.putIfAbsent("project.build.sourceEncoding", "UTF-8");
        var parentBuilder = existing == null ? Model.newBuilder() : existing.with();
        var parentModel = parentBuilder.namespaceUri("http://maven.apache.org/POM/4.0.0")
                .modelVersion("4.0.0")
                .groupId(parentGroupId)
                .artifactId(parentArtifactId)
                .version(parentVersion)
                .packaging("pom")
                .modules(moduleDirectories.stream()
                        .map(entry -> relativePath(project, entry.getValue()
                                .resolve("module-info.pom")))
                        .toList())
                .properties(properties)
                .build();
        ConsumerPomGenerator.writePom(parentModel, rootPom);

        for (var entry : moduleDirectories) {
            String moduleName = entry.getKey();
            SourceModule sourceModule = sourceModules.get(moduleName);
            var parent = Parent.newBuilder()
                    .groupId(parentGroupId)
                    .artifactId(parentArtifactId)
                    .version(parentVersion)
                    .relativePath(relativePath(entry.getValue(), rootPom))
                    .build();
            var child = buildModel(sourceModule, sourceModules, resolution).with()
                    .parent(parent)
                    .properties(sourceModule.release() == null ? Map.of() : Map.of("maven.compiler.release", Integer.toString(sourceModule.release())))
                    .build(build)
                    .build();
            ConsumerPomGenerator.writePom(child, entry.getValue()
                    .resolve("module-info.pom"));
        }

        Path config = project.resolve(".mvn/maven.config");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "-Dmaven.repo.local.tail=" + moduleRepository.toAbsolutePath().normalize() + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Model buildModel(SourceModule sourceModule, Map<String, SourceModule> sourceModules, ModuleResolution resolution) throws IOException {
        Model base = ConsumerPomGenerator.model(sourceModule, VERSION, sourceModules.keySet(), resolution);
        var dependencies = new LinkedHashMap<String, Dependency>();
        base.getDependencies().forEach(dependency -> dependencies.put(dependency.getArtifactId(), dependency));

        var recordedVersions = new LinkedHashMap<String, String>();
        Path hashFile = sourceModule.sourceDirectory().resolve("module-info.hash");
        if (Files.isRegularFile(hashFile)) {
            for (var coordinate : ModuleInfoHash.read(hashFile).keys()) {
                recordedVersions.put(coordinate.moduleName(),
                        coordinate.version().toString());
            }
        }

        for (var requirement : sourceModule.descriptor().requires()) {
            String name = requirement.name();
            if (dependencies.containsKey(name) || isJdkModule(name)) {
                continue;
            }
            String version = sourceModules.containsKey(name) ? VERSION : requirement.compiledVersion()
                    .map(Object::toString)
                    .orElseGet(() -> recordedVersions.get(name));
            if (version == null) {
                continue;
            }
            dependencies.put(name, dependency(name, version, MavenDependency.isOptional(requirement)));
        }
        for (var entry : recordedVersions.entrySet()) {
            String name = entry.getKey();
            if (dependencies.containsKey(name) || sourceModules.containsKey(name) || isJdkModule(name)) {
                continue;
            }
            dependencies.put(name, dependency(name, entry.getValue(), true));
        }

        var sortedDependencies = new ArrayList<>(dependencies.values());
        sortedDependencies.sort(Comparator.comparing(Dependency::getGroupId)
                .thenComparing(Dependency::getArtifactId));
        return base.with()
                   .dependencies(sortedDependencies)
                   .build();
    }

    private static Model readPom(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (var input = Files.newInputStream(path)) {
            return new MavenStaxReader().read(input);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to read " + path, e);
        }
    }

    private static String inheritedGroupId(Model model) {
        return model != null && model.getParent() != null && model.getParent().getGroupId() != null
                ? model.getParent().getGroupId()
                : GROUP_ID;
    }

    private static String inheritedVersion(Model model) {
        return model != null && model.getParent() != null && model.getParent().getVersion() != null
                ? model.getParent().getVersion()
                : VERSION;
    }

    private static Dependency dependency(String moduleName, String version, boolean optional) {
        var coordinate = ArtifactCandidates.locationCoordinate(moduleName, version);
        var dependency = Dependency.newBuilder()
                .groupId(coordinate.getGroupId())
                .artifactId(coordinate.getArtifactId())
                .version(version)
                .scope("compile");
        if (optional) {
            dependency.optional("true");
        }
        return dependency.build();
    }

    private static boolean isJdkModule(String moduleName) {
        return moduleName.startsWith("java.") || moduleName.startsWith("jdk.");
    }

    private static String relativePath(Path from, Path to) {
        return from.relativize(to)
                   .toString()
                   .replace(from.getFileSystem()
                                .getSeparator(),
                           "/");
    }
}
