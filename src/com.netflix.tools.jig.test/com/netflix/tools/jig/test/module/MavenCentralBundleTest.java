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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.module.MavenCentralBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCentralBundleTest {
    @Test
    void createsSignedRepositoryBundleWithRequiredChecksums(@TempDir Path directory) throws Exception {
        Collection<Artifact> artifacts = artifacts(directory, pom("Example deployment"));

        try (var bundle = MavenCentralBundle.create(artifacts);
             var zip = new ZipFile(bundle.path()
                     .toFile())) {
            Set<String> names = zip.stream()
                    .map(entry -> entry.getName())
                    .collect(Collectors.toSet());
            String base = "com/example/com.example.application/1.2.3/com.example.application-1.2.3";
            assertTrue(names.contains(base + ".pom"));
            assertTrue(names.contains(base + ".pom.asc"));
            assertTrue(names.contains(base + ".pom.md5"));
            assertTrue(names.contains(base + ".pom.sha1"));
            assertTrue(names.contains(base + ".jar"));
            assertTrue(names.contains(base + "-sources.jar"));
            assertTrue(names.contains(base + "-javadoc.jar"));
            assertEquals(16, names.size());
        }
    }

    @Test
    void validatesCentralPomMetadata(@TempDir Path directory) throws Exception {
        Collection<Artifact> artifacts = artifacts(directory, pom(""));

        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralBundle.create(artifacts));

        assertTrue(exception.getMessage().contains("has no description"),
                exception.getMessage());
    }

    @Test
    void validatesLicenseDetails(@TempDir Path directory) throws Exception {
        String pom = pom("Example deployment").replace("<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>", "");
        Collection<Artifact> artifacts = artifacts(directory, pom);

        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralBundle.create(artifacts));

        assertTrue(exception.getMessage().contains("license[0].url"),
                exception.getMessage());
    }

    @Test
    void validatesDeveloperIdentity(@TempDir Path directory) throws Exception {
        String pom = pom("Example deployment").replace("<developer><name>Example Maintainers</name></developer>", "<developer/>");
        Collection<Artifact> artifacts = artifacts(directory, pom);

        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralBundle.create(artifacts));

        assertTrue(exception.getMessage().contains("developer[0].id or name"),
                exception.getMessage());
    }

    @Test
    void validatesAbsoluteMetadataUrls(@TempDir Path directory) throws Exception {
        String pom = pom("Example deployment").replace("<url>https://example.com/project</url>", "<url>project</url>");
        Collection<Artifact> artifacts = artifacts(directory, pom);

        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralBundle.create(artifacts));

        assertTrue(exception.getMessage().contains("non-absolute url"),
                exception.getMessage());
    }

    @Test
    void validatesScmConnections(@TempDir Path directory) throws Exception {
        String pom = pom("Example deployment").replace("scm:git:https://example.com/project.git", "https://example.com/project.git");
        Collection<Artifact> artifacts = artifacts(directory, pom);

        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralBundle.create(artifacts));

        assertTrue(exception.getMessage().contains("invalid scm.connection"),
                exception.getMessage());
    }

    @Test
    void validatesPomCoordinates(@TempDir Path directory) throws Exception {
        String pom = pom("Example deployment").replace("<artifactId>com.example.application</artifactId>", "<artifactId>other</artifactId>");
        Collection<Artifact> artifacts = artifacts(directory, pom);

        var exception = assertThrows(IllegalArgumentException.class, () -> MavenCentralBundle.create(artifacts));

        assertTrue(exception.getMessage().contains("POM artifactId is other"),
                exception.getMessage());
    }

    private static Collection<Artifact> artifacts(Path directory, String pom) throws Exception {
        var artifacts = new ArrayList<Artifact>();
        add(artifacts, directory, "pom", "", pom);
        add(artifacts, directory, "jar", "", "binary");
        add(artifacts, directory, "jar", "sources", "sources");
        add(artifacts, directory, "jar", "javadoc", "javadoc");
        var signatures = new ArrayList<Artifact>();
        for (Artifact artifact : artifacts) {
            add(signatures, directory, artifact.getExtension() + ".asc", artifact.getClassifier(),
                    "signature");
        }
        artifacts.addAll(signatures);
        return artifacts;
    }

    private static void add(Collection<Artifact> artifacts, Path directory, String extension,
                            String classifier, String contents)
            throws Exception {
        Path path = directory.resolve(artifacts.size() + "." + extension.replace('.', '-'));
        Files.writeString(path, contents);
        artifacts.add(new DefaultArtifact("com.example", "com.example.application", classifier, extension, "1.2.3")
                .setPath(path));
    }

    private static String pom(String description) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>com.example.application</artifactId>
                  <version>1.2.3</version>
                  <name>Example</name>
                  <description>%s</description>
                  <url>https://example.com/project</url>
                  <licenses>
                    <license>
                      <name>Apache-2.0</name>
                      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                    </license>
                  </licenses>
                  <developers>
                    <developer><name>Example Maintainers</name></developer>
                  </developers>
                  <scm>
                    <connection>scm:git:https://example.com/project.git</connection>
                    <developerConnection>scm:git:ssh://example.com/project.git</developerConnection>
                    <url>https://example.com/project</url>
                  </scm>
                </project>
                """
                .formatted(description);
    }
}
