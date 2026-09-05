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

package com.netflix.tools.jig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.netflix.tools.jig.internal.openpgp.OpenPgpSigner;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;

/** Creates detached OpenPGP signatures for Maven deployment artifacts. */
final class MavenArtifactSigner implements AutoCloseable {
    static final String KEY = "MAVEN_GPG_KEY";
    static final String FINGERPRINT = "MAVEN_GPG_KEY_FINGERPRINT";
    static final String PASSPHRASE = "MAVEN_GPG_PASSPHRASE";

    private final OpenPgpSigner signer;
    private final Clock clock;
    private final Path directory;

    private MavenArtifactSigner(OpenPgpSigner signer, Clock clock, Path directory) {
        this.signer = signer;
        this.clock = clock;
        this.directory = directory;
    }

    static MavenArtifactSigner fromEnvironment(Map<String, String> environment, Clock clock) throws IOException {
        String keyValue = environment.get(KEY);
        if (keyValue == null || keyValue.isBlank()) {
            throw new IllegalArgumentException(KEY + " is not set");
        }
        byte[] keyMaterial = keyValue.getBytes(StandardCharsets.UTF_8);
        String passphraseValue = environment.get(PASSPHRASE);
        char[] passphrase = passphraseValue == null ? null : passphraseValue.toCharArray();
        try {
            OpenPgpSigner signer = OpenPgpSigner.load(keyMaterial, environment.get(FINGERPRINT), passphrase);
            return new MavenArtifactSigner(signer, clock, Files.createTempDirectory("jig-maven-signatures-"));
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    Collection<Artifact> sign(Collection<Artifact> artifacts) throws IOException {
        var result = new ArrayList<Artifact>(artifacts);
        Instant creationTime = clock.instant();
        int index = 0;
        for (Artifact artifact : artifacts) {
            Path source = artifact.getPath();
            if (source == null || !Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Maven artifact is not a file: " + artifact);
            }
            Path signature = directory.resolve(Integer.toString(index++) + ".asc");
            byte[] value;
            try (var input = Files.newInputStream(source)) {
                value = signer.sign(input, creationTime);
            }
            try {
                Files.write(signature, value);
            } finally {
                Arrays.fill(value, (byte) 0);
            }
            result.add(new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), artifact.getExtension() + ".asc", artifact.getVersion())
                    .setPath(signature));
        }
        return List.copyOf(result);
    }

    @Override
    public void close() throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
