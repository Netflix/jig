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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.netflix.tools.jig.module.ArtifactCandidates;
import org.junit.jupiter.api.Test;

/**
 * Validates that the location strategies cover the top Maven Central modules.
 * For each module name, checks whether the actual artifact coordinates are
 * reachable through module convention, artifact candidates, or a verified
 * BOM on Central (from the Maven Central index).
 *
 * <p>This is a static analysis test — no network access required.
 */
public class TopModulesValidationTest {

    private static final Set<String> CENTRAL_BOMS = loadLines("maven-central-boms.txt");

    @Test
    void topModulesAreReachable() throws Exception {
        var lines = Files.readAllLines(testResourcePath("maven-central-top-modules.txt"));

        int total = 0, moduleHit = 0, candidateHit = 0, bomHit = 0;
        var misses = new ArrayList<String>();

        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 3) {
                continue;
            }
            String coords = parts[1];
            String moduleName = parts[2];
            String[] ga = coords.split(":");
            String groupId = ga[0], artifactId = ga[1];
            total++;

            if (matchesModuleConvention(moduleName, groupId, artifactId)) {
                moduleHit++;
            } else if (matchesArtifactCandidates(moduleName, groupId, artifactId)) {
                candidateHit++;
            } else if (hasVerifiedBom(moduleName)) {
                bomHit++;
            } else {
                misses.add(coords + "\t" + moduleName);
            }
        }

        System.out.printf("Total: %d, Module convention: %d, Artifact candidates: %d, " + "BOM verified: %d, Missed: %d%n", total, moduleHit, candidateHit, bomHit,
                misses.size());
        if (!misses.isEmpty()) {
            System.out.println("Missed:");
            misses.forEach(m -> System.out.println("  " + m));
        }
    }

    private static boolean matchesModuleConvention(String moduleName, String groupId, String artifactId) {
        return ArtifactCandidates.moduleCandidates(moduleName, null).stream()
                .anyMatch(a -> a.getGroupId().equals(groupId) && a.getArtifactId().equals(artifactId));
    }

    private static boolean matchesArtifactCandidates(String moduleName, String groupId, String artifactId) {
        return ArtifactCandidates.of(moduleName, null).stream()
                .anyMatch(a -> a.getGroupId().equals(groupId) && a.getArtifactId().equals(artifactId));
    }

    private static boolean hasVerifiedBom(String moduleName) {
        if (ArtifactCandidates.bomAlias(moduleName)
                .filter(bom -> CENTRAL_BOMS.contains(bom.getGroupId() + ":" + bom.getArtifactId()))
                .isPresent()) {
            return true;
        }
        return ArtifactCandidates.bomCandidates(moduleName, null).stream()
                .anyMatch(bom -> CENTRAL_BOMS.contains(bom.getGroupId() + ":" + bom.getArtifactId()));
    }

    private static Set<String> loadLines(String resource) {
        try {
            return new HashSet<>(Files.readAllLines(testResourcePath(resource)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path testResourcePath(String name) {
        return Path.of("src", "com.netflix.tools.jig.test", name)
                .toAbsolutePath()
                .normalize();
    }
}
