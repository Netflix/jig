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
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import javax.xml.stream.XMLStreamException;

import com.netflix.tools.jig.internal.org.apache.maven.api.model.Dependency;
import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxWriter;

/** Generates Maven consumer POMs for selected source modules. */
public final class ConsumerPomGenerator {

    private ConsumerPomGenerator() {}

    public static void generate(ModuleResolution resolution, String moduleVersion, Path outputDirectory) throws IOException {
        var sourceModules = selectedSourceModules(resolution);
        if (sourceModules.isEmpty()) {
            throw new FindException("No source modules selected for POM generation");
        }
        Path absoluteOutput = outputDirectory.toAbsolutePath();
        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.createDirectory(absoluteOutput);
        for (var entry : sourceModules.entrySet().stream()
                .sorted(Entry.comparingByKey())
                .toList()) {
            String moduleName = entry.getKey();
            writePom(model(entry.getValue(), moduleVersion, sourceModules.keySet(), resolution),
                    outputDirectory.resolve(moduleName).resolve(moduleName + "-" + moduleVersion + ".pom"));
        }
    }

    static Map<String, SourceModule> selectedSourceModules(ModuleResolution resolution) {
        var result = new LinkedHashMap<String, SourceModule>();
        resolution.configuration()
                  .modules()
                  .forEach(module -> {
                      if (module.reference() instanceof SourceModuleReference source) {
                          result.put(module.name(), source.sourceModule());
                      }
                  });
        return result;
    }

    static Model model(SourceModule sourceModule, String moduleVersion, Collection<String> sourceModuleNames,
                       ModuleResolution resolution) {
        String moduleName = sourceModule.name();
        var coordinate = ArtifactCandidates.locationCoordinate(moduleName, moduleVersion);
        var properties = new LinkedHashMap<String, String>();

        var dependencies = new ArrayList<Dependency>();
        for (var requirement : sourceModule.descriptor().requires()) {
            var dependencyName = requirement.name();
            var selectedSource = sourceModuleNames.contains(dependencyName);
            if (!selectedSource && ModuleFinder.ofSystem()
                    .find(dependencyName)
                    .isPresent()
                    && requirement.compiledVersion().isEmpty()) {
                continue;
            }

            String version;
            if (selectedSource) {
                version = moduleVersion;
            } else {
                version = requirement.compiledVersion()
                                     .map(Object::toString)
                                     .orElseGet(
                                             () -> resolution.finder()
                                                             .find(dependencyName)
                                                             .flatMap(reference -> reference.descriptor().version())
                                                             .map(Object::toString)
                                                             .orElseThrow(() -> new FindException("No version found for required module " + dependencyName)));
            }
            var dependencyCoordinate = ArtifactCandidates.locationCoordinate(dependencyName, version);

            var dependency = Dependency.newBuilder()
                    .groupId(dependencyCoordinate.getGroupId())
                    .artifactId(dependencyCoordinate.getArtifactId())
                    .version(version)
                    .scope("compile");
            if (MavenDependency.isOptional(requirement)) {
                dependency.optional("true");
            }
            dependencies.add(dependency.build());
        }
        dependencies.sort(Comparator.comparing(Dependency::getGroupId)
                .thenComparing(Dependency::getArtifactId));

        return Model.newBuilder()
                .namespaceUri("http://maven.apache.org/POM/4.0.0")
                .modelVersion("4.0.0")
                .groupId(coordinate.getGroupId())
                .artifactId(coordinate.getArtifactId())
                .version(moduleVersion)
                .packaging("jar")
                .properties(properties)
                .dependencies(dependencies)
                .build();
    }

    static void writePom(Model model, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (var output = Files.newOutputStream(path)) {
            var writer = new MavenStaxWriter();
            writer.setNamespace("http://maven.apache.org/POM/4.0.0");
            writer.setAddLocationInformation(false);
            writer.write(output, model);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to write " + path, e);
        }
    }
}
