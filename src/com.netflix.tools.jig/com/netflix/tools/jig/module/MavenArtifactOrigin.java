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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;

/** Maven package coordinate from which a module was resolved. */
public record MavenArtifactOrigin(String groupId, String artifactId, String version,
        Optional<URI> repositoryUrl)
        implements ModuleOrigin {

    public static final String PROPERTY = "origin";
    private static final String PREFIX = "pkg:maven/";
    private static final String LATEST_VERSION = "[0,)";

    public MavenArtifactOrigin(String groupId, String artifactId) {
        this(groupId, artifactId, LATEST_VERSION, Optional.empty());
    }

    public MavenArtifactOrigin(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, Optional.empty());
    }

    public MavenArtifactOrigin {
        requireCoordinatePart(groupId, "groupId");
        requireCoordinatePart(artifactId, "artifactId");
        requireCoordinatePart(version, "version");
        Objects.requireNonNull(repositoryUrl, "repositoryUrl");
    }

    public static MavenArtifactOrigin of(Artifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return new MavenArtifactOrigin(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    }

    static MavenArtifactOrigin parse(URI url) {
        String raw = url.getRawSchemeSpecificPart();
        if (!raw.startsWith("maven/")) {
            throw new IllegalArgumentException("Unsupported package URL type for module lookup: " + url);
        }
        if (url.getRawFragment() != null) {
            throw new IllegalArgumentException("Package URL subpaths are not supported for module lookup: " + url);
        }

        String packagePart;
        String qualifierPart = null;
        int qualifierSeparator = raw.indexOf('?');
        if (qualifierSeparator < 0) {
            packagePart = raw.substring("maven/".length());
        } else {
            packagePart = raw.substring("maven/".length(), qualifierSeparator);
            qualifierPart = raw.substring(qualifierSeparator + 1);
        }

        int nameSeparator = packagePart.indexOf('/');
        int versionSeparator = packagePart.lastIndexOf('@');
        if (nameSeparator <= 0
                || nameSeparator != packagePart.lastIndexOf('/')
                || nameSeparator == packagePart.length() - 1
                || (versionSeparator >= 0 && (versionSeparator <= nameSeparator + 1 || versionSeparator == packagePart.length() - 1))) {
            throw new IllegalArgumentException("Maven package URL requires namespace and name: " + url);
        }

        Map<String, String> qualifiers = qualifiers(qualifierPart, url);
        URI repositoryUrl = qualifiers.containsKey("repository_url") ? repositoryUrl(qualifiers.get("repository_url"), url) : null;
        int nameEnd = versionSeparator < 0 ? packagePart.length() : versionSeparator;
        String version = versionSeparator < 0 ? LATEST_VERSION : decode(packagePart.substring(versionSeparator + 1), url);
        return new MavenArtifactOrigin(decode(packagePart.substring(0, nameSeparator), url), decode(packagePart.substring(nameSeparator + 1, nameEnd), url), version,
                Optional.ofNullable(repositoryUrl));
    }

    public boolean latest() {
        return version.equals(LATEST_VERSION);
    }

    @Override
    public String toString() {
        String packageUrl = PREFIX
                + groupId
                + "/"
                + artifactId
                + (latest() ? "" : "@" + version);
        return repositoryUrl.map(repository -> packageUrl + "?repository_url=" + encode(repository.toString())).orElse(packageUrl);
    }

    private static Map<String, String> qualifiers(String value, URI url) {
        if (value == null) {
            return Map.of();
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty package URL qualifiers: " + url);
        }
        var qualifiers = new LinkedHashMap<String, String>();
        for (String entry : value.split("&", -1)) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("Invalid package URL qualifier: " + url);
            }
            String name = decode(entry.substring(0, separator), url).toLowerCase(Locale.ROOT);
            String qualifierValue = decode(entry.substring(separator + 1), url);
            if (!name.equals("repository_url")) {
                throw new IllegalArgumentException("Unsupported Maven package URL qualifier " + name + ": " + url);
            }
            if (qualifiers.putIfAbsent(name, qualifierValue) != null) {
                throw new IllegalArgumentException("Duplicate Maven package URL qualifier " + name + ": " + url);
            }
        }
        return Map.copyOf(qualifiers);
    }

    private static URI repositoryUrl(String value, URI packageUrl) {
        URI repository;
        try {
            repository = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid repository_url in package URL: " + packageUrl, e);
        }
        if (!repository.isAbsolute()
                || repository.isOpaque()
                || repository.getRawUserInfo() != null
                || repository.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid repository_url in package URL: " + packageUrl);
        }
        return repository;
    }

    private static String decode(String value, URI url) {
        var result = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            if (value.charAt(i) != '%') {
                result.append(value.charAt(i++));
                continue;
            }
            var bytes = new ByteArrayOutputStream();
            while (i < value.length() && value.charAt(i) == '%') {
                if (i + 2 >= value.length()) {
                    throw new IllegalArgumentException("Invalid percent encoding in " + url);
                }
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid percent encoding in " + url);
                }
                bytes.write((high << 4) | low);
                i += 3;
            }
            try {
                result.append(StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Invalid UTF-8 encoding in " + url, e);
            }
        }
        return result.toString();
    }

    private static String encode(String value) {
        var result = new StringBuilder();
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            int character = valueByte & 0xff;
            if (character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-'
                    || character == '.'
                    || character == '_'
                    || character == '~') {
                result.append((char) character);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit(character >>> 4, 16)));
                result.append(Character.toUpperCase(Character.forDigit(character & 0xf, 16)));
            }
        }
        return result.toString();
    }

    private static void requireCoordinatePart(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('/') >= 0 || value.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Invalid Maven " + name + ": " + value);
        }
    }
}
