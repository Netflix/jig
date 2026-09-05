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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.stream.IntStream;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;

/**
 * Maps a Java module name to candidate Maven artifact coordinates.
 *
 * <p>Module names follow reverse-DNS conventions, but the split between group ID
 * and artifact ID is ambiguous. This class generates all plausible candidates,
 * ordered so that the most likely match comes first.
 *
 * <p>Module names that don't follow reverse-DNS (e.g. {@code spring.core}) are
 * handled via prefix aliases loaded from {@code maven-module-prefix-aliases.properties}.
 */
public final class ArtifactCandidates {

    private ArtifactCandidates() {}

    /**
     * Returns the location coordinate for a module name. The groupId is the
     * module namespace, and the artifactId is the module name
     * itself. Extension is {@code pom} since this coordinate serves
     * relocation metadata.
     *
     * <p>For non-reverse-DNS module names, the prefix alias is resolved first
     * to establish the namespace.
     */
    public static Artifact locationCoordinate(String moduleName, String version) {
        return new DefaultArtifact(moduleNamespace(moduleName), moduleName, "pom", version);
    }

    /** Returns the coordinate used by the virtual module location repository. */
    public static Artifact moduleLocationCoordinate(String moduleName, String version) {
        return new DefaultArtifact(moduleNamespace(moduleName) + ".module", moduleName, "pom", version);
    }

    /**
     * Returns the BOM location coordinate for a namespace level. The groupId
     * is the module namespace with a {@code .module.bom} suffix, and the
     * artifactId is the namespace level being queried.
     *
     * <p>Used by the BOM location transport to cache namespace-to-BOM
     * mappings per version.
     */
    public static Artifact bomLocationCoordinate(String namespace, String version) {
        String topNamespace = moduleNamespace(namespace);
        return new DefaultArtifact(topNamespace + ".module.bom", namespace, "pom", version);
    }

    /**
     * Returns the module namespace for a module name — the top private domain
     * of the module's namespace, accounting for code host aliases.
     * For prefix-aliased names, resolves the alias first.
     */
    public static String moduleNamespace(String moduleName) {
        Optional<Entry<String, String>> alias = findPrefixAlias(moduleName);
        if (alias.isPresent()) {
            String target = alias.get().getValue();
            String groupId = target.contains(":") ? target.split(":", 2)[0] : target;
            List<String> parts = moduleParts(groupId);
            return String.join(".", parts.subList(0, namespaceStart(parts)));
        }
        List<String> parts = moduleParts(moduleName);
        return mavenName(String.join(".", parts.subList(0, namespaceStart(parts))));
    }

    /**
     * Returns whether an artifact follows the OSGi/module convention — the
     * artifactId is the full module name and the groupId is a prefix of it.
     * For example, {@code org.eclipse.sisu:org.eclipse.sisu.plexus} follows
     * the convention for module {@code org.eclipse.sisu.plexus}.
     */
    public static boolean isModuleConvention(Artifact artifact, String moduleName) {
        return artifact.getArtifactId().equals(moduleName) && mavenName(moduleName).startsWith(artifact.getGroupId());
    }

    /**
     * Returns whether an artifact's groupId is within the namespace expected
     * for a given module name. An artifact is authoritative for a module name
     * only if its groupId matches or is a child of the module's
     * namespace.
     *
     * <p>For example, {@code com.fasterxml.jackson.core} is authoritative for
     * {@code com.fasterxml.jackson.databind} (namespace
     * {@code com.fasterxml}), but {@code io.grpc} is not authoritative for
     * {@code io.netty.internal.tcnative}.
     */
    public static boolean isAuthoritative(String groupId, String moduleName) {
        String namespace = moduleNamespace(moduleName);
        if (groupId.equals(namespace) || groupId.startsWith(namespace + ".")) {
            return true;
        }
        Optional<Entry<String, String>> alias = findPrefixAlias(moduleName);
        if (alias.isPresent()) {
            String target = alias.get().getValue();
            String aliasGroupId = target.contains(":") ? target.split(":", 2)[0] : target;
            String normalized = groupId.replace('-', '.');
            String normalizedAlias = aliasGroupId.replace('-', '.');
            return normalized.equals(normalizedAlias) || normalized.startsWith(normalizedAlias + ".");
        }
        return false;
    }

    /**
     * Returns candidate Maven artifacts for a given module name and optional version.
     */
    public static SequencedCollection<Artifact> of(String moduleName, String version) {
        Optional<Entry<String, String>> alias = findPrefixAlias(moduleName);
        if (alias.isPresent()) {
            return aliasCandidates(moduleName, alias.get(), version);
        }
        List<String> parts = Arrays.asList(moduleName.split("\\."));
        return reverseDnsCandidates(parts, version);
    }

    /**
     * Returns the namespace start index for a reverse-DNS module name — the
     * first level past the TLD (or code host alias). For example:
     *
     * <ul>
     *   <li>{@code org.junit.jupiter.api} → 2 (past {@code org.junit})</li>
     *   <li>{@code io.github.mavenplugins.base} → 4 (past
     *       {@code io.github.mavenplugins})</li>
     * </ul>
     *
     * <p>
     * Maven Central namespaces are always two parts deep (TLD + org), except
     * for code host aliases (three parts: TLD + host + user) and bare names
     * without a dot (one part).
     *
     * @param parts the module name parts in forward order (e.g.
     *     {@code [org, junit, jupiter, api]})
     */
    public static int namespaceStart(List<String> parts) {
        if (parts.size() < 2) {
            return 1;
        }
        if (!IANA_TLDS.contains(parts.get(0))) {
            return 1;
        }
        String codeHostKey = parts.get(0) + "." + parts.get(1);
        if (CODEHOST_PREFIXES.contains(codeHostKey)) {
            return Math.min(3, parts.size());
        }

        return 2;
    }

    private static SequencedCollection<Artifact> reverseDnsCandidates(List<String> parts, String version) {
        int start = namespaceStart(parts);
        int nameStart = start - 1;
        int nameLimit = parts.size() - 1;
        var seen = new LinkedHashSet<String>();
        var result = new ArrayList<Artifact>();

        // Preferred fold: try the most common artifactId pattern at all depths first
        if (parts.size() > start + 1) {
            String fold1 = mavenName(String.join("-", parts.subList(start, parts.size())));
            for (int i = start; i <= parts.size(); i++) {
                String group = mavenName(String.join(".", parts.subList(0, i)));
                if (seen.add(group + ":" + fold1)) {
                    result.add(new DefaultArtifact(group, fold1, "jar", version));
                }
            }
            // Second fold includes the namespace segment as a prefix
            if (start > 0) {
                String fold2 = mavenName(String.join("-",
                        parts.subList(start - 1, parts.size())));
                if (!fold2.equals(fold1)) {
                    for (int i = start; i <= parts.size(); i++) {
                        String group = mavenName(String.join(".", parts.subList(0, i)));
                        if (seen.add(group + ":" + fold2)) {
                            result.add(new DefaultArtifact(group, fold2, "jar", version));
                        }
                    }
                }
            }
        }

        // Full permutation walk at each depth
        IntStream.rangeClosed(start, parts.size()).forEach(
                i -> {
                    String namespace = mavenName(String.join(".", parts.subList(0, i)));
                    var names = new ArrayList<String>();
                    IntStream.rangeClosed(nameStart, Math.min(i, nameLimit))
                            .mapToObj(j -> mavenName(String.join("-", parts.subList(j, parts.size()))))
                            .toList()
                            .reversed()
                            .forEach(names::add);
                    if (i < parts.size()) {
                        IntStream.rangeClosed(nameStart, Math.min(i - 1, nameLimit)).filter(j -> j < i).mapToObj(j -> mavenName(String.join("-", parts.subList(j, i)))).toList().reversed().stream()
                                .filter(name -> !name.isEmpty() && !names.contains(name))
                                .forEach(names::add);
                    }
                    names.stream()
                            .filter(name -> seen.add(namespace + ":" + name))
                            .map(name -> (Artifact) new DefaultArtifact(namespace, name, "jar", version))
                            .forEach(result::add);
                });

        // Module convention: dotted artifactId (OSGi). Root checked by locator first.
        String moduleName = String.join(".", parts);
        for (int i = start; i <= parts.size(); i++) {
            String group = mavenName(String.join(".", parts.subList(0, i)));
            if (seen.add(group + ":" + moduleName)) {
                result.add(new DefaultArtifact(group, moduleName, "jar", version));
            }
        }

        return result;
    }

    /**
     * Generates candidates for a prefix-aliased module name.
     * E.g. {@code spring.boot.starter.web} with alias {@code spring.boot -> org.springframework.boot}
     * produces candidates like {@code org.springframework.boot:spring-boot-starter-web}.
     */
    private static String mavenName(String moduleName) {
        return moduleName.replace('_', '-');
    }

    private static SequencedCollection<Artifact> aliasCandidates(String moduleName, Entry<String, String> alias, String version) {
        String prefix = alias.getKey();
        String target = alias.getValue();
        if (target.contains(":")) {
            String[] ga = target.split(":", 2);
            return List.of(new DefaultArtifact(ga[0], ga[1], "jar", version));
        }
        String baseGroupId = target;
        String[] moduleParts = moduleName.split("\\.");
        String[] prefixParts = prefix.split("\\.");
        String[] suffixParts = Arrays.stream(Arrays.copyOfRange(moduleParts, prefixParts.length, moduleParts.length))
                .map(ArtifactCandidates::mavenName)
                .toArray(String[]::new);
        if (suffixParts.length == 0 && baseGroupId.contains(".") && moduleName.contains(".")) {
            return reverseDnsCandidates(moduleParts(baseGroupId), version);
        }
        String groupSep = baseGroupId.contains(".") ? "." : "-";
        List<Artifact> candidates = new ArrayList<>();
        for (int depth = 0; depth <= suffixParts.length; depth++) {
            String groupId = depth == 0 ? baseGroupId : baseGroupId + groupSep + String.join(groupSep, Arrays.copyOfRange(suffixParts, 0, depth));
            for (int nameStart = 0;
                 nameStart <= prefixParts.length && nameStart < moduleParts.length;
                 nameStart++) {
                int effectiveStart = Math.max(nameStart, prefixParts.length + depth);
                if (effectiveStart >= moduleParts.length) {
                    String artifactName = groupSep.equals("-") ? groupId : mavenName(String.join("-", moduleParts));
                    candidates.add(new DefaultArtifact(groupId, artifactName, "jar", version));
                    continue;
                }
                String artifactName = mavenName(String.join("-", Arrays.copyOfRange(moduleParts, effectiveStart, moduleParts.length)));
                candidates.add(new DefaultArtifact(groupId, artifactName, "jar", version));
                if (effectiveStart > 0) {
                    String fullName = mavenName(String.join("-", Arrays.copyOfRange(moduleParts, nameStart, moduleParts.length)));
                    if (!fullName.equals(artifactName)) {
                        candidates.add(new DefaultArtifact(groupId, fullName, "jar", version));
                    }
                }
            }
        }
        // Deduplicate preserving order
        var seen = new LinkedHashSet<String>();
        return candidates.stream()
                .filter(a -> seen.add(a.getGroupId() + ":" + a.getArtifactId()))
                .toList();
    }

    /**
     * Derives a module name from Maven coordinates. Numeric suffixes in the
     * artifact ID are collapsed ({@code jsr-275} → {@code jsr275}), the group
     * and artifact are normalized, then overlapping prefixes are deduplicated.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code com.netflix.spectator:spectator-ext-jvm} → {@code com.netflix.spectator.ext.jvm}</li>
     *   <li>{@code com.github.ricksbrown:cowsay} → {@code com.github.ricksbrown.cowsay}</li>
     *   <li>{@code javax.annotation:jsr-275} → {@code javax.annotation.jsr275}</li>
     *   <li>{@code org.ow2.asm:asm} → {@code org.ow2.asm}</li>
     * </ul>
     */
    public static String deriveModuleName(String groupId, String artifactId) {
        String key = groupId + ":" + artifactId;
        String relocated = ARTIFACT_RELOCATIONS.get(key);
        if (relocated != null) {
            String[] ga = relocated.split(":", 2);
            groupId = ga[0];
            artifactId = ga[1];
            key = relocated;
        }

        String aliasName = ARTIFACT_TO_MODULE.get(key);
        if (aliasName != null) {
            return aliasName;
        }

        String normalizedGroupId = ModuleNames.normalize(groupId);
        String[] groupParts = normalizedGroupId.split("\\.");
        String collapsedArtifactId = ModuleNames.collapseNumericSuffixes(artifactId);
        String[] artifactParts = ModuleNames.normalize(collapsedArtifactId).split("\\.");
        int overlap = 0;
        for (int candidate = Math.min(groupParts.length, artifactParts.length);
             candidate > 0;
             candidate--) {
            boolean matches = true;
            for (int i = 0; i < candidate; i++) {
                if (!groupParts[groupParts.length - candidate + i].equals(artifactParts[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                overlap = candidate;
                break;
            }
        }
        if (overlap == artifactParts.length) {
            return normalizedGroupId;
        }
        if (overlap > 0) {
            String suffix = String.join(".", Arrays.copyOfRange(artifactParts, overlap, artifactParts.length));
            return normalizedGroupId + "." + suffix;
        }
        return normalizedGroupId + "." + String.join(".", artifactParts);
    }

    /**
     * Returns a comparator that orders artifacts by similarity to a target
     * module name, most similar first. Similarity is computed by
     * comparing the identity portions of each name — the target's suffix
     * after the shared namespace prefix, and the artifactId's
     * distinguishing suffix after stripping the namespace overlap — using
     * Levenshtein distance.
     *
     * <p>This allows BOM batch-probing to check the most promising
     * entries first, typically finding the correct artifact in one or
     * two probes instead of scanning every BOM entry.
     *
     * @param moduleName the target module name to match against
     * @return comparator ordering most-similar artifacts first
     */
    public static Comparator<Artifact> bomSimilarityOrder(String moduleName) {
        String[] targetParts = moduleName.split("\\.");
        return Comparator.comparingDouble((Artifact a) -> bomSimilarity(targetParts, a)).reversed();
    }

    /**
     * Similarity score between a target module name and a BOM entry.
     *
     * <p>The score isolates the identity-bearing portions of each name
     * and compares them with normalized Levenshtein distance:
     * <ol>
     *   <li>Find the common namespace prefix between the target and
     *       the entry's groupId.</li>
     *   <li>The target's identity is the remaining suffix, concatenated
     *       without separators (e.g. {@code annotation}).</li>
     *   <li>The artifact's identity is the normalized artifactId parts
     *       after stripping the overlap with the shared namespace,
     *       concatenated (e.g. {@code annotations}).</li>
     *   <li>Score = 1 - levenshtein(targetId, artifactId) / max(lengths),
     *       with a namespace-match tiebreaker.</li>
     * </ol>
     */
    public static double bomSimilarity(String[] targetParts, Artifact artifact) {
        String[] groupParts = artifact.getGroupId().split("\\.");

        int commonPrefix = 0;
        for (int i = 0; i < Math.min(targetParts.length, groupParts.length); i++) {
            if (targetParts[i].equals(groupParts[i])) {
                commonPrefix++;
            } else {
                break;
            }
        }

        String targetId = joinFrom(targetParts, commonPrefix);

        String normalizedArtifact = ModuleNames.normalize(ModuleNames.collapseNumericSuffixes(artifact.getArtifactId()));
        String[] artParts = normalizedArtifact.split("\\.");
        int nsOverlap = suffixPrefixOverlap(Arrays.copyOf(targetParts, commonPrefix), artParts);
        String artId = joinFrom(artParts, nsOverlap);

        double identityScore;
        if (targetId.isEmpty()) {
            // The groupId fully matches the target module name — strongest
            // signal. Perfect when the artifactId is also absorbed (artId
            // empty); still strong when there are remaining artId parts
            // like "api" in slf4j-api at org.slf4j.
            identityScore = artId.isEmpty() ? 1.0 : 0.9;
        } else if (artId.isEmpty()) {
            // ArtifactId absorbed by groupId but target has unmatched parts
            identityScore = 0.0;
        } else {
            int dist = levenshtein(targetId, artId);
            identityScore = 1.0 - (double) dist / Math.max(targetId.length(), artId.length());
        }

        double namespaceScore = (double) commonPrefix / Math.max(targetParts.length, groupParts.length);

        return identityScore + namespaceScore * 0.01;
    }

    private static String joinFrom(String[] parts, int from) {
        var sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static int suffixPrefixOverlap(String[] namespace, String[] artParts) {
        for (int len = Math.min(namespace.length, artParts.length);
             len > 0;
             len--) {
            boolean match = true;
            for (int i = 0; i < len; i++) {
                if (!namespace[namespace.length - len + i].equals(artParts[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return len;
            }
        }
        return 0;
    }

    static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                curr[j] = a.charAt(i - 1) == b.charAt(j - 1) ? prev[j - 1] : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            var tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }

    private static int commonPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return n;
    }

    /**
     * Returns the module name for an artifact if it matches a known alias
     * target. This is the reverse of prefix alias lookup — given Maven
     * coordinates, returns the module name that maps to them.
     *
     * @return the module name, or empty if no alias targets this artifact
     */
    public static Optional<String> moduleNameForArtifact(String groupId, String artifactId) {
        return Optional.ofNullable(ARTIFACT_TO_MODULE.get(groupId + ":" + artifactId));
    }

    /**
     * Returns predecessor coordinates for a canonical artifact — older
     * {@code groupId:artifactId} pairs that were relocated to it. Used
     * to fall back to older coordinates when the requested version
     * doesn't exist at the canonical coordinate.
     *
     * @return predecessor artifacts, or empty if no relocations target this artifact
     */
    public static List<Artifact> predecessors(Artifact artifact) {
        var keys = RELOCATION_PREDECESSORS.get(artifact.getGroupId() + ":" + artifact.getArtifactId());
        if (keys == null) {
            return List.of();
        }
        return keys.stream()
                .map(key -> {
                    String[] ga = key.split(":", 2);
                    return (Artifact) new DefaultArtifact(ga[0], ga[1], artifact.getExtension(), artifact.getVersion());
                })
                .toList();
    }

    /**
     * Finds the longest matching prefix alias for a module name.
     */
    private static Optional<Entry<String, String>> findPrefixAlias(String moduleName) {
        return PREFIX_ALIASES.entrySet().stream()
                .filter(entry ->
                        moduleName.equals(entry.getKey()) || moduleName.startsWith(entry.getKey() + "."))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()));
    }

    /**
     * Returns module convention candidates for a module name — the module name
     * as a dotted artifactId at each namespace level, following the OSGi
     * convention where the bundle symbolic name (and Java module name) is
     * used as the Maven artifactId.
     *
     * <p>For prefix-aliased module names, the namespace walk uses the resolved
     * alias target so that probes hit real Maven groupIds.
     *
     * @return candidates ordered from namespace root to deepest level
     */
    public static List<Artifact> moduleCandidates(String moduleName, String version) {
        List<String> parts = namespaceParts(moduleName);
        int start = namespaceStart(parts);
        return IntStream.rangeClosed(start, parts.size())
                .mapToObj(i -> (Artifact) new DefaultArtifact(mavenName(String.join(".", parts.subList(0, i))), moduleName, "jar", version))
                .toList();
    }

    /**
     * Returns the preferred-fold candidates for a module name — the two
     * most common artifact naming patterns walked across all namespace
     * depths. Returns empty for prefix-aliased or short module names.
     */
    public static List<Artifact> preferredFoldCandidates(String moduleName, String version) {
        if (findPrefixAlias(moduleName).isPresent()) {
            return List.of();
        }
        List<String> parts = moduleParts(moduleName);
        int start = namespaceStart(parts);
        if (parts.size() <= start + 1) {
            return List.of();
        }

        String fold1 = mavenName(String.join("-", parts.subList(start, parts.size())));
        String fold2 = start > 0 ? mavenName(String.join("-",
                parts.subList(start - 1, parts.size())))
                : null;

        var candidates = new ArrayList<Artifact>();
        for (int i = start; i <= parts.size(); i++) {
            String group = mavenName(String.join(".", parts.subList(0, i)));
            candidates.add(new DefaultArtifact(group, fold1, "jar", version));
        }
        if (fold2 != null && !fold2.equals(fold1)) {
            for (int i = start; i <= parts.size(); i++) {
                String group = mavenName(String.join(".", parts.subList(0, i)));
                candidates.add(new DefaultArtifact(group, fold2, "jar", version));
            }
        }
        return candidates;
    }

    /**
     * Returns BOM candidates for a module name — {@code <namespace>:bom} and
     * {@code <namespace>:<lastPart>-bom} at each namespace level.
     *
     * @return candidates, or empty if the module name isn't reverse-DNS
     */
    /**
     * Returns the BOM artifact for a module name from the BOM prefix alias
     * table, or empty if no alias matches. Matches against the canonical
     * coordinate ({@code moduleNamespace/moduleName}) using longest prefix.
     */
    public static Optional<Artifact> bomAlias(String moduleName) {
        String groupId = moduleNamespace(moduleName);
        String canonicalKey = groupId + "/" + moduleName;
        return BOM_PREFIX_ALIASES.entrySet().stream()
                .filter(entry -> {
                    String key = entry.getKey();
                    if (!key.contains("/")) {
                        return groupId.equals(key) || groupId.startsWith(key + ".");
                    }
                    return canonicalKey.equals(key) || canonicalKey.startsWith(key + ".");
                })
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(entry -> {
                    String[] ga = entry.getValue().split(":");
                    return (Artifact) new DefaultArtifact(ga[0], ga[1], "pom", null);
                });
    }

    /**
     * Returns BOM candidates for a single namespace level. Generates
     * {@code <namespace>:bom} and {@code <namespace>:<lastPart>-bom}.
     */
    public static List<Artifact> bomCandidatesForNamespace(String namespace, String version) {
        List<String> parts = moduleParts(namespace);
        String mavenNamespace = mavenName(namespace);
        String lastPart = mavenName(parts.getLast());
        return List.of(new DefaultArtifact(mavenNamespace, "bom", "pom", version), new DefaultArtifact(mavenNamespace, lastPart + "-bom", "pom", version));
    }

    public static List<Artifact> bomCandidates(String moduleName, String version) {
        List<String> parts = namespaceParts(moduleName);
        int start = namespaceStart(parts);
        return IntStream.rangeClosed(start, parts.size())
                .boxed()
                .<Artifact>mapMulti(
                        (i, downstream) -> {
                            String namespace = mavenName(String.join(".", parts.subList(0, i)));
                            downstream.accept(new DefaultArtifact(namespace, "bom", "pom", version));
                            String lastPart = mavenName(parts.get(i - 1));
                            downstream.accept(new DefaultArtifact(namespace, lastPart + "-bom", "pom", version));
                        })
                .toList();
    }

    /**
     * Returns the forward-order parts of a module name.
     */
    public static List<String> moduleParts(String moduleName) {
        return Arrays.asList(moduleName.split("\\."));
    }

    /**
     * Returns the namespace parts for a module name, resolving prefix aliases
     * to their Maven groupId targets. For aliased names like {@code spring.core},
     * returns the alias target expanded with the suffix:
     * {@code [org, springframework, core]}. For non-aliased names, returns
     * the raw module name parts.
     */
    public static List<String> namespaceParts(String moduleName) {
        Optional<Entry<String, String>> alias = findPrefixAlias(moduleName);
        if (alias.isEmpty()) {
            return moduleParts(moduleName);
        }
        String target = alias.get().getValue();
        if (target.contains(":")) {
            return moduleParts(moduleName);
        }
        String[] prefixParts = alias.get()
                .getKey()
                .split("\\.");
        String[] targetParts = target.split("\\.");
        String[] allParts = moduleName.split("\\.");
        List<String> resolved = new ArrayList<>(Arrays.asList(targetParts));
        for (int i = prefixParts.length;
             i < allParts.length;
             i++) {
            resolved.add(allParts[i]);
        }
        return resolved;
    }

    // Sonatype supported code host aliases.
    // See https://central.sonatype.org/register/namespace/#for-dns
    // Keys are domain names, values are their DNS aliases.
    public static final Map<String, String> PUBLIC_CODEHOST_ALIASES = Map.of("github.com", "github.io", "gitlab.com", "gitlab.io", "gitee.com", "gitee.io",
            "bitbucket.com", "bitbucket.io");

    // Forward-order module name prefixes for code host namespaces.
    // Three-part namespaces: TLD + host + user (e.g. io.github.user).
    private static final Set<String> CODEHOST_PREFIXES = Set.of("io.github", "com.github", "io.gitlab", "com.gitlab", "io.gitee", "com.gitee",
            "io.bitbucket", "com.bitbucket");

    /**
     * Module name prefix to Maven group ID mappings for modules that don't follow
     * reverse-DNS naming. Loaded from {@code maven-module-prefix-aliases.properties}.
     */
    static final Map<String, String> PREFIX_ALIASES;

    /**
     * Canonical coordinate prefix to BOM coordinate mappings for artifact
     * families. Loaded from {@code maven-bom-prefix-aliases.properties}.
     * Keys are {@code moduleNamespace/moduleNamePrefix}, values are
     * {@code bomGroupId:bomArtifactId}.
     */
    static final Map<String, String> BOM_PREFIX_ALIASES;

    /**
     * IANA top-level domains. Used to distinguish reverse-DNS module names
     * (where the first segment is a TLD like {@code com} or {@code org})
     * from non-reverse-DNS namespace owners (like {@code jakarta}).
     * Loaded from {@code iana-tlds.txt}.
     */
    static final Set<String> IANA_TLDS;

    /**
     * Reverse index from {@code groupId:artifactId} to module name for
     * aliases with explicit {@code g:a} targets. Built from
     * {@link #PREFIX_ALIASES} entries whose values contain a colon.
     */
    private static final Map<String, String> ARTIFACT_TO_MODULE;

    /**
     * Artifact relocations from old coordinates to canonical successors.
     * Keys and values are {@code groupId:artifactId}. Applied in
     * {@link #deriveModuleName} before any other derivation so that old
     * coordinates produce the same module name as their canonical
     * replacements. Loaded from {@code maven-artifact-relocations.properties}.
     */
    private static final Map<String, String> ARTIFACT_RELOCATIONS;

    /**
     * Reverse relocation index: canonical {@code groupId:artifactId} to
     * the list of predecessor coordinates that relocated to it. Used by
     * the location transport to fall back to older coordinates when the
     * requested version doesn't exist at the canonical coordinate.
     */
    private static final Map<String, List<String>> RELOCATION_PREDECESSORS;

    static {
        PREFIX_ALIASES = Map.copyOf(loadProperties("maven-module-prefix-aliases.properties"));
        BOM_PREFIX_ALIASES = Map.copyOf(loadProperties("maven-bom-prefix-aliases.properties"));
        ARTIFACT_RELOCATIONS = Map.copyOf(loadProperties("maven-artifact-relocations.properties"));
        IANA_TLDS = Set.copyOf(loadTlds("iana-tlds.txt"));

        var predecessors = new LinkedHashMap<String, List<String>>();
        for (var entry : ARTIFACT_RELOCATIONS.entrySet()) {
            predecessors.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        RELOCATION_PREDECESSORS = Map.copyOf(predecessors);

        var reverse = new LinkedHashMap<String, String>();
        for (var entry : PREFIX_ALIASES.entrySet()) {
            String target = entry.getValue();
            if (target.contains(":")) {
                reverse.put(target, entry.getKey());
            }
        }
        ARTIFACT_TO_MODULE = Map.copyOf(reverse);
    }

    private static List<String> loadTlds(String resource) {
        try (var in = ArtifactCandidates.class.getResourceAsStream(resource)) {
            if (in == null) {
                return List.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> loadProperties(String resource) {
        Properties props = new Properties();
        try (var in = ArtifactCandidates.class.getResourceAsStream(resource)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        return map;
    }
}
