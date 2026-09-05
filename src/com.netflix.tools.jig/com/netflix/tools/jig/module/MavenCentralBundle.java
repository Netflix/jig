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
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLStreamException;

import com.netflix.tools.jig.internal.org.apache.maven.api.model.Model;
import com.netflix.tools.jig.internal.org.apache.maven.model.v4.MavenStaxReader;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;

/** A Maven Central Portal deployment bundle. */
public final class MavenCentralBundle implements AutoCloseable {
    private static final List<String> CHECKSUM_ALGORITHMS = List.of("MD5", "SHA-1");

    private final Path directory;
    private final Path bundle;

    private MavenCentralBundle(Path directory, Path bundle) {
        this.directory = directory;
        this.bundle = bundle;
    }

    public static MavenCentralBundle create(Collection<Artifact> artifacts) throws IOException {
        validate(artifacts);
        Path directory = Files.createTempDirectory("jig-maven-central-");
        boolean complete = false;
        try {
            Path repository = Files.createDirectory(directory.resolve("repository"));
            for (Artifact artifact : artifacts) {
                Path target = repository.resolve(repositoryPath(artifact));
                Files.createDirectories(target.getParent());
                Files.copy(artifact.getPath(), target);
            }
            for (Path artifact : regularFiles(repository)) {
                if (!artifact.getFileName()
                             .toString()
                             .endsWith(".asc")) {
                    writeChecksums(artifact);
                }
            }
            Path bundle = directory.resolve("central-bundle.zip");
            zip(repository, bundle);
            complete = true;
            return new MavenCentralBundle(directory, bundle);
        } finally {
            if (!complete) {
                deleteTree(directory);
            }
        }
    }

    public Path path() {
        return bundle;
    }

    @Override
    public void close() throws IOException {
        deleteTree(directory);
    }

    private static void validate(Collection<Artifact> artifacts) throws IOException {
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("Maven Central deployment contains no artifacts");
        }
        var components = new LinkedHashMap<String, List<Artifact>>();
        for (Artifact artifact : artifacts) {
            validateCoordinate(artifact);
            if (artifact.getPath() == null || !Files.isRegularFile(artifact.getPath())) {
                throw new IllegalArgumentException("Maven artifact is not a file: " + artifact);
            }
            if (artifact.isSnapshot()) {
                throw new IllegalArgumentException("Maven Central deployment cannot contain a snapshot: " + artifact);
            }
            String component = artifact.getGroupId()
                    + ":"
                    + artifact.getArtifactId()
                    + ":"
                    + artifact.getVersion();
            components.computeIfAbsent(component, ignored -> new ArrayList<>()).add(artifact);
        }
        for (var entry : components.entrySet()) {
            validateComponent(entry.getKey(), entry.getValue());
        }
    }

    private static void validateComponent(String component, List<Artifact> artifacts) throws IOException {
        Map<String, Artifact> byName = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            String name = artifactName(artifact);
            if (byName.putIfAbsent(name, artifact) != null) {
                throw new IllegalArgumentException("Duplicate Maven Central artifact: " + name);
            }
        }
        Artifact pom = requireArtifact(component, artifacts, "pom", "");
        requireArtifact(component, artifacts, "jar", "");
        requireArtifact(component, artifacts, "jar", "sources");
        requireArtifact(component, artifacts, "jar", "javadoc");
        for (Artifact artifact : artifacts) {
            if (!artifact.getExtension().endsWith(".asc")) {
                String signature = artifactName(artifact) + ".asc";
                if (!byName.containsKey(signature)) {
                    throw new IllegalArgumentException("Maven Central artifact has no signature: " + artifactName(artifact));
                }
            }
        }
        validatePom(component, pom, readPom(pom.getPath()));
    }

    private static Artifact requireArtifact(String component, List<Artifact> artifacts, String extension,
            String classifier) {
        return artifacts.stream()
                .filter(artifact -> artifact.getExtension().equals(extension))
                .filter(artifact -> artifact.getClassifier().equals(classifier))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Maven Central component " + component + " requires " + description(extension, classifier)));
    }

    private static String description(String extension, String classifier) {
        return classifier.isEmpty() ? extension : classifier + " " + extension;
    }

    private static void validatePom(String component, Artifact pom, Model model) {
        requireEqual(component, "groupId", pom.getGroupId(), model.getGroupId());
        requireEqual(component, "artifactId", pom.getArtifactId(), model.getArtifactId());
        requireEqual(component, "version", pom.getVersion(), model.getVersion());
        requireValue(component, "name", model.getName());
        requireValue(component, "description", model.getDescription());
        requireUrl(component, "url", model.getUrl());
        if (model.getLicenses().isEmpty()) {
            throw missingMetadata(component, "licenses");
        }
        for (int i = 0; i < model.getLicenses().size(); i++) {
            var license = model.getLicenses().get(i);
            String field = "licenses.license[" + i + "]";
            requireValue(component, field + ".name", license.getName());
            requireUrl(component, field + ".url", license.getUrl());
        }
        if (model.getDevelopers().isEmpty()) {
            throw missingMetadata(component, "developers");
        }
        for (int i = 0; i < model.getDevelopers().size(); i++) {
            var developer = model.getDevelopers().get(i);
            if (blank(developer.getId()) && blank(developer.getName())) {
                throw missingMetadata(component, "developers.developer[" + i + "].id or name");
            }
            optionalUrl(component, "developers.developer[" + i + "].url", developer.getUrl());
            optionalUrl(component, "developers.developer[" + i + "].organizationUrl", developer.getOrganizationUrl());
        }
        if (model.getScm() == null) {
            throw missingMetadata(component, "scm");
        }
        requireScmConnection(component, "scm.connection",
                model.getScm().getConnection());
        requireScmConnection(component, "scm.developerConnection",
                model.getScm().getDeveloperConnection());
        requireUrl(component, "scm.url",
                model.getScm().getUrl());
    }

    private static void requireScmConnection(String component, String field, String value) {
        requireValue(component, field, value);
        if (!value.startsWith("scm:")) {
            throw new IllegalArgumentException("Maven Central component " + component + " has an invalid " + field + ": " + value);
        }
    }

    private static void requireEqual(String component, String field, String expected,
            String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Maven Central component "
                    + component
                    + " POM "
                    + field
                    + " is "
                    + actual
                    + "; expected "
                    + expected);
        }
    }

    private static void requireUrl(String component, String field, String value) {
        requireValue(component, field, value);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Maven Central component " + component + " has an invalid " + field + ": " + value, e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Maven Central component " + component + " has a non-absolute " + field + ": " + value);
        }
    }

    private static void optionalUrl(String component, String field, String value) {
        if (!blank(value)) {
            requireUrl(component, field, value);
        }
    }

    private static void requireValue(String component, String field, String value) {
        if (blank(value)) {
            throw missingMetadata(component, field);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateCoordinate(Artifact artifact) {
        requireCoordinatePart(artifact, "groupId", artifact.getGroupId(), true);
        requireCoordinatePart(artifact, "artifactId", artifact.getArtifactId(), false);
        requireCoordinatePart(artifact, "version", artifact.getVersion(), false);
    }

    private static void requireCoordinatePart(Artifact artifact, String field, String value,
            boolean allowDots) {
        if (blank(value)
                || value.contains("/")
                || value.contains("\\")
                || value.equals(".")
                || value.equals("..")) {
            throw new IllegalArgumentException("Maven Central artifact has an invalid " + field + ": " + artifact);
        }
        if (allowDots) {
            for (String segment : value.split("\\.", -1)) {
                if (segment.isEmpty()) {
                    throw new IllegalArgumentException("Maven Central artifact has an invalid " + field + ": " + artifact);
                }
            }
        }
    }

    private static IllegalArgumentException missingMetadata(String component, String field) {
        return new IllegalArgumentException("Maven Central component " + component + " has no " + field);
    }

    private static Model readPom(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return new MavenStaxReader().read(input);
        } catch (XMLStreamException e) {
            throw new IOException("Failed to read " + path, e);
        }
    }

    private static Path repositoryPath(Artifact artifact) {
        Path path = Path.of("");
        for (String part : artifact.getGroupId().split("\\.")) {
            path = path.resolve(part);
        }
        return path.resolve(artifact.getArtifactId())
                   .resolve(artifact.getVersion())
                   .resolve(artifactName(artifact));
    }

    private static String artifactName(Artifact artifact) {
        String classifier = artifact.getClassifier().isEmpty() ? "" : "-" + artifact.getClassifier();
        return artifact.getArtifactId()
                + "-"
                + artifact.getVersion()
                + classifier
                + "."
                + artifact.getExtension();
    }

    private static void writeChecksums(Path artifact) throws IOException {
        for (String algorithm : CHECKSUM_ALGORITHMS) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(algorithm);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            try (InputStream input = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, count);
                }
            }
            String extension = algorithm.equals("MD5") ? ".md5" : ".sha1";
            Files.writeString(artifact.resolveSibling(artifact.getFileName() + extension),
                    HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static void zip(Path repository, Path bundle) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(bundle))) {
            for (Path file : regularFiles(repository)) {
                String name = repository.relativize(file)
                                        .toString()
                                        .replace('\\', '/');
                output.putNextEntry(new ZipEntry(name));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static List<Path> regularFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
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
