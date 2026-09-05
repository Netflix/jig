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

import java.util.List;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.module.ArtifactCandidates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.netflix.tools.jig.module.ArtifactCandidates.isAuthoritative;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArtifactCandidatesTest {

    @Test
    @DisplayName("bare single-segment module name")
    public void bare() {
        assertEquals(List.of("junit:junit"), candidates("junit"));
    }

    @Test
    @DisplayName("top domain with one segment (org.yaml.snakeyaml)")
    public void topDomain() {
        assertEquals(
                List.of("org.yaml:snakeyaml", // actual
                "org.yaml:yaml-snakeyaml", "org.yaml:yaml", "org.yaml.snakeyaml:snakeyaml", "org.yaml.snakeyaml:yaml-snakeyaml", "org.yaml:org.yaml.snakeyaml", // convention (appended)
                        "org.yaml.snakeyaml:org.yaml.snakeyaml"),
                candidates("org.yaml.snakeyaml"));
    }

    @Test
    @DisplayName("namespaced reverse-DNS (com.netflix.spectator.api)")
    public void namespaced() {
        assertEquals(
                List.of(
                        "com.netflix:spectator-api",
                        "com.netflix.spectator:spectator-api", // actual (preferred fold 1)
                        "com.netflix.spectator.api:spectator-api",
                        "com.netflix:netflix-spectator-api", // preferred fold 2
                        "com.netflix.spectator:netflix-spectator-api",
                        "com.netflix.spectator.api:netflix-spectator-api",
                        "com.netflix:netflix",
                        "com.netflix.spectator:api",
                        "com.netflix.spectator:spectator",
                        "com.netflix.spectator:netflix-spectator",
                        "com.netflix.spectator.api:api",
                        "com.netflix:com.netflix.spectator.api", // convention (appended)
                        "com.netflix.spectator:com.netflix.spectator.api",
                        "com.netflix.spectator.api:com.netflix.spectator.api"),
                candidates("com.netflix.spectator.api"));
    }

    @Test
    @DisplayName("namespaced nested (com.netflix.spectator.ext.jvm)")
    public void namespacedNested() {
        assertEquals(
                List.of(
                        "com.netflix:spectator-ext-jvm", // preferred fold 1
                        "com.netflix.spectator:spectator-ext-jvm", // actual (preferred fold 1)
                        "com.netflix.spectator.ext:spectator-ext-jvm",
                        "com.netflix.spectator.ext.jvm:spectator-ext-jvm",
                        "com.netflix:netflix-spectator-ext-jvm", // preferred fold 2
                        "com.netflix.spectator:netflix-spectator-ext-jvm",
                        "com.netflix.spectator.ext:netflix-spectator-ext-jvm",
                        "com.netflix.spectator.ext.jvm:netflix-spectator-ext-jvm",
                        "com.netflix:netflix",
                        "com.netflix.spectator:ext-jvm",
                        "com.netflix.spectator:spectator",
                        "com.netflix.spectator:netflix-spectator",
                        "com.netflix.spectator.ext:jvm",
                        "com.netflix.spectator.ext:ext-jvm",
                        "com.netflix.spectator.ext:ext",
                        "com.netflix.spectator.ext:spectator-ext",
                        "com.netflix.spectator.ext:netflix-spectator-ext",
                        "com.netflix.spectator.ext.jvm:jvm",
                        "com.netflix.spectator.ext.jvm:ext-jvm",
                        "com.netflix:com.netflix.spectator.ext.jvm", // convention (appended)
                        "com.netflix.spectator:com.netflix.spectator.ext.jvm",
                        "com.netflix.spectator.ext:com.netflix.spectator.ext.jvm",
                        "com.netflix.spectator.ext.jvm:com.netflix.spectator.ext.jvm"),
                candidates("com.netflix.spectator.ext.jvm"));
    }

    @Test
    @DisplayName("code host alias (com.github.ricksbrown.cowsay)")
    public void codeHostAlias() {
        assertEquals(
                List.of("com.github.ricksbrown:cowsay", // actual
                "com.github.ricksbrown:ricksbrown-cowsay", "com.github.ricksbrown:ricksbrown", "com.github.ricksbrown.cowsay:cowsay",
                        "com.github.ricksbrown.cowsay:ricksbrown-cowsay", "com.github.ricksbrown:com.github.ricksbrown.cowsay", // convention (appended)
                        "com.github.ricksbrown.cowsay:com.github.ricksbrown.cowsay"),
                candidates("com.github.ricksbrown.cowsay"));
    }

    @Test
    @DisplayName("public code host deep nesting (io.github.mavenplugins.base.maven.plugins)")
    public void publicCodeHost() {
        assertEquals(
                List.of(
                        "io.github.mavenplugins:base-maven-plugins", // actual (preferred fold 1)
                        "io.github.mavenplugins.base:base-maven-plugins",
                        "io.github.mavenplugins.base.maven:base-maven-plugins",
                        "io.github.mavenplugins.base.maven.plugins:base-maven-plugins",
                        "io.github.mavenplugins:mavenplugins-base-maven-plugins", // preferred fold 2
                        "io.github.mavenplugins.base:mavenplugins-base-maven-plugins",
                        "io.github.mavenplugins.base.maven:mavenplugins-base-maven-plugins",
                        "io.github.mavenplugins.base.maven.plugins:mavenplugins-base-maven-plugins",
                        "io.github.mavenplugins:mavenplugins",
                        "io.github.mavenplugins.base:maven-plugins",
                        "io.github.mavenplugins.base:base",
                        "io.github.mavenplugins.base:mavenplugins-base",
                        "io.github.mavenplugins.base.maven:plugins",
                        "io.github.mavenplugins.base.maven:maven-plugins",
                        "io.github.mavenplugins.base.maven:maven",
                        "io.github.mavenplugins.base.maven:base-maven",
                        "io.github.mavenplugins.base.maven:mavenplugins-base-maven",
                        "io.github.mavenplugins.base.maven.plugins:plugins",
                        "io.github.mavenplugins.base.maven.plugins:maven-plugins",
                        "io.github.mavenplugins:io.github.mavenplugins.base.maven.plugins", // convention (appended)
                        "io.github.mavenplugins.base:io.github.mavenplugins.base.maven.plugins",
                        "io.github.mavenplugins.base.maven:io.github.mavenplugins.base.maven.plugins",
                        "io.github.mavenplugins.base.maven.plugins:io.github.mavenplugins.base.maven.plugins"),
                candidates("io.github.mavenplugins.base.maven.plugins"));
    }

    @Test
    @DisplayName("top level simple (org.junit.jupiter)")
    public void topLevelSimple() {
        assertEquals(
                List.of("org.junit:jupiter", "org.junit:junit-jupiter", "org.junit:junit", "org.junit.jupiter:jupiter", "org.junit.jupiter:junit-jupiter", // actual
                "org.junit:org.junit.jupiter", // convention (appended)
                        "org.junit.jupiter:org.junit.jupiter"),
                candidates("org.junit.jupiter"));
    }

    @Test
    @DisplayName("top level with name (org.junit.jupiter.api)")
    public void topLevelName() {
        assertEquals(
                List.of(
                        "org.junit:jupiter-api", // preferred fold 1
                        "org.junit.jupiter:jupiter-api",
                        "org.junit.jupiter.api:jupiter-api",
                        "org.junit:junit-jupiter-api", // preferred fold 2
                        "org.junit.jupiter:junit-jupiter-api", // actual
                        "org.junit.jupiter.api:junit-jupiter-api",
                        "org.junit:junit",
                        "org.junit.jupiter:api",
                        "org.junit.jupiter:jupiter",
                        "org.junit.jupiter:junit-jupiter",
                        "org.junit.jupiter.api:api",
                        "org.junit:org.junit.jupiter.api", // convention (appended)
                        "org.junit.jupiter:org.junit.jupiter.api",
                        "org.junit.jupiter.api:org.junit.jupiter.api"),
                candidates("org.junit.jupiter.api"));
    }

    @Test
    @DisplayName("prefix alias: spring.core -> org.springframework:spring-core")
    public void prefixAliasedSpringCore() {
        assertEquals(
                List.of("org.springframework:core", "org.springframework:spring-core", // actual
                "org.springframework.core:spring-core"),
                candidates("spring.core"));
    }

    @Test
    @DisplayName("prefix alias: spring.boot -> org.springframework.boot:spring-boot")
    public void prefixAliasedSpringBoot() {
        assertEquals(
                List.of("org.springframework:boot", "org.springframework:spring-boot", "org.springframework.boot:spring-boot" // actual
                ),
                candidates("spring.boot"));
    }

    @Test
    @DisplayName("prefix alias: spring.boot.starter.web -> org.springframework.boot:spring-boot-starter-web")
    public void prefixAliasedSpringBootStarterWeb() {
        assertEquals(
                List.of("org.springframework:boot-starter-web", "org.springframework:spring-boot-starter-web", "org.springframework.boot:starter-web", "org.springframework.boot:spring-boot-starter-web", // actual
                "org.springframework.boot:boot-starter-web", "org.springframework.boot.starter:web",
                        "org.springframework.boot.starter:spring-boot-starter-web", "org.springframework.boot.starter:boot-starter-web", "org.springframework.boot.starter.web:spring-boot-starter-web"),
                candidates("spring.boot.starter.web"));
    }

    @Test
    @DisplayName("prefix alias: spring.security.core -> org.springframework.security:spring-security-core")
    public void prefixAliasedSpringSecurityCore() {
        assertEquals(
                List.of("org.springframework:security-core", "org.springframework:spring-security-core", "org.springframework.security:core", "org.springframework.security:spring-security-core", // actual
                "org.springframework.security:security-core", "org.springframework.security.core:spring-security-core"),
                candidates("spring.security.core"));
    }

    @Test
    @DisplayName("prefix alias: lombok -> org.projectlombok:lombok")
    public void prefixAliasedLombok() {
        assertEquals(
                List.of("org.projectlombok:lombok" // actual
                ),
                candidates("lombok"));
    }

    @Test
    @DisplayName("explicit alias: jopt.simple -> net.sf.jopt-simple:jopt-simple")
    public void aliasedJoptSimple() {
        assertEquals(List.of("net.sf.jopt-simple:jopt-simple"), candidates("jopt.simple"));
    }

    @Test
    @DisplayName("explicit aliases: Error Prone underscore artifacts")
    public void aliasedErrorProne() {
        assertEquals(List.of("com.google.errorprone:error_prone_core"), candidates("com.google.errorprone.core"));
        assertEquals(List.of("com.google.errorprone:error_prone_annotation"), candidates("com.google.errorprone.annotation"));
        assertEquals(List.of("com.google.errorprone:error_prone_annotations"), candidates("com.google.errorprone.annotations"));
        assertEquals(List.of("com.google.errorprone:error_prone_check_api"), candidates("com.google.errorprone.check.api"));
    }

    @Test
    @DisplayName("explicit alias: Checker Framework dataflow Error Prone fork")
    public void aliasedDataflowErrorProne() {
        assertEquals(List.of("io.github.eisop:dataflow-errorprone"), candidates("org.checkerframework.dataflow"));
    }

    @Test
    @DisplayName("explicit alias: java-diff-utils automatic module name")
    public void aliasedJavaDiffUtils() {
        assertEquals(List.of("io.github.java-diff-utils:java-diff-utils"), candidates("io.github.javadiffutils"));
    }

    @Test
    @DisplayName("underscore encodes a Maven hyphen")
    public void encodedMavenHyphen() {
        String moduleName = "com.github.ben_manes.caffeine";

        assertEquals("com.github.ben-manes", ArtifactCandidates.moduleNamespace(moduleName));
        Artifact location = ArtifactCandidates.locationCoordinate(moduleName, "1.0");
        assertEquals("com.github.ben-manes:" + moduleName, location.getGroupId() + ":" + location.getArtifactId());
        assertTrue(ArtifactCandidates.isModuleConvention(location, moduleName));
        assertEquals("com.github.ben-manes:caffeine", candidates(moduleName).getFirst());
    }

    @Test
    @DisplayName("legacy code-host author alias: benmanes -> ben-manes")
    public void aliasedHyphenatedCodeHostAuthor() {
        assertEquals("com.github.ben-manes", ArtifactCandidates.moduleNamespace("com.github.benmanes.caffeine"));
        assertTrue(isAuthoritative("com.github.ben-manes", "com.github.benmanes.caffeine"));
        assertEquals("com.github.ben-manes:caffeine", candidates("com.github.benmanes.caffeine").getFirst());
    }

    // -- Legacy aliases --

    @Test
    @DisplayName("legacy alias: org.apache.commons.io -> commons-io:commons-io")
    public void legacyCommonsIo() {
        assertEquals(List.of("commons-io:commons-io" // actual
        ),
                candidates("org.apache.commons.io"));
    }

    @Test
    @DisplayName("legacy alias: org.joda.time -> joda-time:joda-time")
    public void legacyJodaTime() {
        assertEquals(List.of("joda-time:joda-time" // actual
        ),
                candidates("org.joda.time"));
    }

    @Test
    @DisplayName("legacy alias: org.jaxen -> jaxen:jaxen")
    public void legacyJaxen() {
        assertEquals(List.of("jaxen:jaxen" // actual
        ),
                candidates("org.jaxen"));
    }

    // -- Relocation aliases --

    @Test
    @DisplayName("relocation alias: org.objectweb.asm -> org.ow2.asm:asm (no suffix, delegates to reverse-DNS)")
    public void relocationAsm() {
        assertEquals(
                List.of("org.ow2:asm", "org.ow2:ow2-asm", "org.ow2:ow2", "org.ow2.asm:asm", // actual
                "org.ow2.asm:ow2-asm", "org.ow2:org.ow2.asm", // convention (appended)
                        "org.ow2.asm:org.ow2.asm"),
                candidates("org.objectweb.asm"));
    }

    @Test
    @DisplayName("relocation alias: org.objectweb.asm.commons -> org.ow2.asm:asm-commons")
    public void relocationAsmCommons() {
        assertEquals(
                List.of("org.ow2.asm:commons", "org.ow2.asm:org-objectweb-asm-commons", "org.ow2.asm:objectweb-asm-commons", "org.ow2.asm:asm-commons", // actual
                "org.ow2.asm.commons:org-objectweb-asm-commons"),
                candidates("org.objectweb.asm.commons"));
    }

    @Test
    @DisplayName("relocation alias: com.sun.jna -> net.java.dev.jna:jna (no suffix, delegates to reverse-DNS)")
    public void relocationJna() {
        assertEquals(
                List.of(
                        "net.java:dev-jna", // preferred fold 1
                        "net.java.dev:dev-jna",
                        "net.java.dev.jna:dev-jna",
                        "net.java:java-dev-jna", // preferred fold 2
                        "net.java.dev:java-dev-jna",
                        "net.java.dev.jna:java-dev-jna",
                        "net.java:java",
                        "net.java.dev:jna",
                        "net.java.dev:dev",
                        "net.java.dev:java-dev",
                        "net.java.dev.jna:jna", // actual
                        "net.java:net.java.dev.jna", // convention (appended)
                        "net.java.dev:net.java.dev.jna",
                        "net.java.dev.jna:net.java.dev.jna"),
                candidates("com.sun.jna"));
    }

    @Test
    @DisplayName("relocation alias: com.sun.jna.platform -> net.java.dev.jna:jna-platform")
    public void relocationJnaPlatform() {
        assertEquals(
                List.of("net.java.dev.jna:platform", "net.java.dev.jna:com-sun-jna-platform", "net.java.dev.jna:sun-jna-platform", "net.java.dev.jna:jna-platform", // actual
                "net.java.dev.jna.platform:com-sun-jna-platform"),
                candidates("com.sun.jna.platform"));
    }

    // -- Preferred fold candidates --

    @Test
    @DisplayName("preferred fold: org.apache.tomcat.embed.core (hit at fold 1, depth 4)")
    public void preferredFoldTomcatEmbedCore() {
        assertEquals(
                List.of("org.apache:tomcat-embed-core", "org.apache.tomcat:tomcat-embed-core", "org.apache.tomcat.embed:tomcat-embed-core", // actual
                "org.apache.tomcat.embed.core:tomcat-embed-core",
                        "org.apache:apache-tomcat-embed-core", "org.apache.tomcat:apache-tomcat-embed-core", "org.apache.tomcat.embed:apache-tomcat-embed-core", "org.apache.tomcat.embed.core:apache-tomcat-embed-core"),
                preferredFoldCandidates("org.apache.tomcat.embed.core"));
    }

    @Test
    @DisplayName("preferred fold: org.junit.jupiter.api (hit at fold 2, depth 3)")
    public void preferredFoldJunitJupiterApi() {
        assertEquals(
                List.of("org.junit:jupiter-api", "org.junit.jupiter:jupiter-api", "org.junit.jupiter.api:jupiter-api", "org.junit:junit-jupiter-api", "org.junit.jupiter:junit-jupiter-api", // actual
                "org.junit.jupiter.api:junit-jupiter-api"),
                preferredFoldCandidates("org.junit.jupiter.api"));
    }

    @Test
    @DisplayName("preferred fold: code host (io.github.mavenplugins.base.maven.plugins)")
    public void preferredFoldCodeHost() {
        assertEquals(
                List.of("io.github.mavenplugins:base-maven-plugins", // actual
                "io.github.mavenplugins.base:base-maven-plugins", "io.github.mavenplugins.base.maven:base-maven-plugins", "io.github.mavenplugins.base.maven.plugins:base-maven-plugins",
                        "io.github.mavenplugins:mavenplugins-base-maven-plugins", "io.github.mavenplugins.base:mavenplugins-base-maven-plugins", "io.github.mavenplugins.base.maven:mavenplugins-base-maven-plugins", "io.github.mavenplugins.base.maven.plugins:mavenplugins-base-maven-plugins"),
                preferredFoldCandidates("io.github.mavenplugins.base.maven.plugins"));
    }

    @Test
    @DisplayName("preferred fold: empty for prefix-aliased modules")
    public void preferredFoldSkipsPrefixAlias() {
        assertEquals(List.of(), preferredFoldCandidates("spring.boot.starter.web"));
    }

    @Test
    @DisplayName("preferred fold: empty for short module names")
    public void preferredFoldSkipsShort() {
        assertEquals(List.of(), preferredFoldCandidates("org.yaml.snakeyaml"));
    }

    // -- Module convention candidates --

    @Test
    @DisplayName("module convention: spring.core (prefix alias)")
    public void moduleConventionSpringCore() {
        assertEquals(
                List.of("org.springframework:spring.core", "org.springframework.core:spring.core"),
                moduleCandidates("spring.core"));
    }

    @Test
    @DisplayName("module convention: spring.boot.starter.web (prefix alias, deep)")
    public void moduleConventionSpringBootStarterWeb() {
        assertEquals(
                List.of("org.springframework:spring.boot.starter.web", "org.springframework.boot:spring.boot.starter.web", "org.springframework.boot.starter:spring.boot.starter.web", "org.springframework.boot.starter.web:spring.boot.starter.web"),
                moduleCandidates("spring.boot.starter.web"));
    }

    @Test
    @DisplayName("module convention: kotlin.stdlib (prefix alias)")
    public void moduleConventionKotlin() {
        assertEquals(
                List.of("org.jetbrains:kotlin.stdlib", "org.jetbrains.kotlin:kotlin.stdlib", "org.jetbrains.kotlin.stdlib:kotlin.stdlib"),
                moduleCandidates("kotlin.stdlib"));
    }

    @Test
    @DisplayName("module convention: org.objectweb.asm (relocation alias)")
    public void moduleConventionAsmAlias() {
        assertEquals(List.of("org.ow2:org.objectweb.asm", "org.ow2.asm:org.objectweb.asm"), moduleCandidates("org.objectweb.asm"));
    }

    @Test
    @DisplayName("module convention: org.junit.jupiter.api")
    public void moduleConventionJunit() {
        assertEquals(
                List.of("org.junit:org.junit.jupiter.api", "org.junit.jupiter:org.junit.jupiter.api", "org.junit.jupiter.api:org.junit.jupiter.api"),
                moduleCandidates("org.junit.jupiter.api"));
    }

    @Test
    @DisplayName("module convention: com.fasterxml.jackson.databind")
    public void moduleConventionJackson() {
        assertEquals(
                List.of("com.fasterxml:com.fasterxml.jackson.databind", "com.fasterxml.jackson:com.fasterxml.jackson.databind", "com.fasterxml.jackson.databind:com.fasterxml.jackson.databind"),
                moduleCandidates("com.fasterxml.jackson.databind"));
    }

    @Test
    @DisplayName("module convention: org.eclipse.jgit (OSGi actual at level 2)")
    public void moduleConventionEclipse() {
        assertEquals(
                List.of("org.eclipse:org.eclipse.jgit", "org.eclipse.jgit:org.eclipse.jgit"),
                moduleCandidates("org.eclipse.jgit"));
    }

    @Test
    @DisplayName("module convention: io.github.classgraph (code host, module = namespace)")
    public void moduleConventionCodeHostMinimal() {
        assertEquals(
                List.of("io.github.classgraph:io.github.classgraph" // actual
                ),
                moduleCandidates("io.github.classgraph"));
    }

    @Test
    @DisplayName("module convention: io.github.openfeign.feign.core (code host)")
    public void moduleConventionCodeHost() {
        assertEquals(
                List.of("io.github.openfeign:io.github.openfeign.feign.core", "io.github.openfeign.feign:io.github.openfeign.feign.core", "io.github.openfeign.feign.core:io.github.openfeign.feign.core"),
                moduleCandidates("io.github.openfeign.feign.core"));
    }

    // -- BOM candidates --

    @Test
    @DisplayName("BOM candidates: spring.core (prefix alias)")
    public void bomCandidatesSpringCore() {
        assertEquals(
                List.of("org.springframework:bom", "org.springframework:springframework-bom", "org.springframework.core:bom", "org.springframework.core:core-bom"),
                bomCandidates("spring.core"));
    }

    @Test
    @DisplayName("BOM candidates: spring.security.core (prefix alias, deep)")
    public void bomCandidatesSpringSecurityCore() {
        assertEquals(
                List.of("org.springframework:bom", "org.springframework:springframework-bom", "org.springframework.security:bom", "org.springframework.security:security-bom", "org.springframework.security.core:bom", "org.springframework.security.core:core-bom"),
                bomCandidates("spring.security.core"));
    }

    @Test
    @DisplayName("BOM candidates: kotlin.stdlib (prefix alias)")
    public void bomCandidatesKotlin() {
        assertEquals(
                List.of("org.jetbrains:bom", "org.jetbrains:jetbrains-bom", "org.jetbrains.kotlin:bom", "org.jetbrains.kotlin:kotlin-bom", "org.jetbrains.kotlin.stdlib:bom", "org.jetbrains.kotlin.stdlib:stdlib-bom"),
                bomCandidates("kotlin.stdlib"));
    }

    @Test
    @DisplayName("BOM candidates: org.objectweb.asm.commons (relocation alias)")
    public void bomCandidatesAsmAlias() {
        assertEquals(
                List.of("org.ow2:bom", "org.ow2:ow2-bom", "org.ow2.asm:bom", "org.ow2.asm:asm-bom", "org.ow2.asm.commons:bom", "org.ow2.asm.commons:commons-bom"),
                bomCandidates("org.objectweb.asm.commons"));
    }

    @Test
    @DisplayName("BOM candidates: org.junit.jupiter.api")
    public void bomCandidatesJunit() {
        assertEquals(
                List.of("org.junit:bom", "org.junit:junit-bom", // actual
                "org.junit.jupiter:bom", "org.junit.jupiter:jupiter-bom", "org.junit.jupiter.api:bom", "org.junit.jupiter.api:api-bom"),
                bomCandidates("org.junit.jupiter.api"));
    }

    @Test
    @DisplayName("BOM candidates: com.fasterxml.jackson.databind")
    public void bomCandidatesJackson() {
        assertEquals(
                List.of("com.fasterxml:bom", "com.fasterxml:fasterxml-bom", "com.fasterxml.jackson:bom", "com.fasterxml.jackson:jackson-bom", // actual
                "com.fasterxml.jackson.databind:bom", "com.fasterxml.jackson.databind:databind-bom"),
                bomCandidates("com.fasterxml.jackson.databind"));
    }

    @Test
    @DisplayName("BOM candidates: software.amazon.awssdk.services.s3")
    public void bomCandidatesAwsSdk() {
        assertEquals(
                List.of("software.amazon:bom", "software.amazon:amazon-bom", "software.amazon.awssdk:bom", // actual
                "software.amazon.awssdk:awssdk-bom", "software.amazon.awssdk.services:bom", "software.amazon.awssdk.services:services-bom",
                        "software.amazon.awssdk.services.s3:bom", "software.amazon.awssdk.services.s3:s3-bom"),
                bomCandidates("software.amazon.awssdk.services.s3"));
    }

    // -- Location coordinates --

    @Test
    @DisplayName("location: reverse-DNS")
    public void locationReverseDns() {
        assertLocation("com.fasterxml.jackson.databind", "com.fasterxml", "com.fasterxml.jackson.databind");
    }

    @Test
    @DisplayName("location: top domain equals module")
    public void locationTopDomain() {
        assertLocation("org.slf4j", "org.slf4j", "org.slf4j");
    }

    @Test
    @DisplayName("location: code host alias")
    public void locationCodeHost() {
        assertLocation("io.github.openfeign.feign.core", "io.github.openfeign", "io.github.openfeign.feign.core");
    }

    @Test
    @DisplayName("location: prefix alias (spring)")
    public void locationPrefixAlias() {
        assertLocation("spring.core", "org.springframework", "spring.core");
    }

    @Test
    @DisplayName("location: prefix alias (kotlin)")
    public void locationKotlin() {
        assertLocation("kotlin.stdlib", "org.jetbrains", "kotlin.stdlib");
    }

    @Test
    @DisplayName("location: bare name with alias")
    public void locationLombok() {
        assertLocation("lombok", "org.projectlombok", "lombok");
    }

    @Test
    @DisplayName("location: bare name without alias")
    public void locationJunit() {
        assertLocation("junit", "junit", "junit");
    }

    private static List<String> candidates(String moduleName) {
        return ArtifactCandidates.of(moduleName, null).stream()
                .map(a -> a.getGroupId() + ":" + a.getArtifactId())
                .toList();
    }

    private static List<String> preferredFoldCandidates(String moduleName) {
        return ArtifactCandidates.preferredFoldCandidates(moduleName, null).stream()
                .map(a -> a.getGroupId() + ":" + a.getArtifactId())
                .toList();
    }

    @Test
    @DisplayName("authoritative: same namespace")
    public void authoritativeSameNamespace() {
        assertTrue(isAuthoritative("com.fasterxml.jackson.core", "com.fasterxml.jackson.databind"));
    }

    @Test
    @DisplayName("authoritative: exact namespace match")
    public void authoritativeExactNamespace() {
        assertTrue(isAuthoritative("org.slf4j", "org.slf4j"));
    }

    @Test
    @DisplayName("not authoritative: different namespace")
    public void notAuthoritativeDifferentNamespace() {
        assertFalse(isAuthoritative("io.grpc", "io.netty.internal.tcnative"));
    }

    @Test
    @DisplayName("not authoritative: shaded jar claiming other project's module")
    public void notAuthoritativeShadedJar() {
        assertFalse(isAuthoritative("org.ops4j.pax.url", "org.apache.commons.codec"));
    }

    @Test
    @DisplayName("authoritative: alias target (org.ow2.asm for org.objectweb.asm)")
    public void authoritativeViaAlias() {
        assertTrue(isAuthoritative("org.ow2.asm", "org.objectweb.asm"));
    }

    @Test
    @DisplayName("authoritative: hyphenated alias child (commons-cli for org.apache.commons.cli)")
    public void authoritativeViaHyphenatedAlias() {
        assertTrue(isAuthoritative("commons-cli", "org.apache.commons.cli"));
    }

    @Test
    @DisplayName("not authoritative: wrong namespace for aliased module")
    public void notAuthoritativeWrongNamespaceForAlias() {
        assertFalse(isAuthoritative("io.grpc", "org.objectweb.asm"));
    }

    private static List<String> moduleCandidates(String moduleName) {
        return ArtifactCandidates.moduleCandidates(moduleName, null).stream()
                .map(a -> a.getGroupId() + ":" + a.getArtifactId())
                .toList();
    }

    private static List<String> bomCandidates(String moduleName) {
        return ArtifactCandidates.bomCandidates(moduleName, null).stream()
                .map(a -> a.getGroupId() + ":" + a.getArtifactId())
                .toList();
    }

    private static void assertLocation(String moduleName, String expectedGroup, String expectedArtifact) {
        var location = ArtifactCandidates.locationCoordinate(moduleName, null);
        assertEquals(expectedGroup, location.getGroupId());
        assertEquals(expectedArtifact, location.getArtifactId());
    }

    // -- deriveModuleName with relocations --

    @Test
    @DisplayName("deriveModuleName: relocated artifact uses canonical coordinates")
    public void deriveRelocatedElApi() {
        assertEquals("javax.el", ArtifactCandidates.deriveModuleName("javax.el", "el-api"));
    }

    @Test
    @DisplayName("deriveModuleName: canonical artifact uses alias reverse lookup")
    public void deriveCanonicalElApi() {
        assertEquals("javax.el", ArtifactCandidates.deriveModuleName("javax.el", "javax.el-api"));
    }

    @Test
    @DisplayName("deriveModuleName: relocated servlet-api")
    public void deriveRelocatedServletApi() {
        assertEquals("javax.servlet", ArtifactCandidates.deriveModuleName("javax.servlet", "servlet-api"));
    }

    @Test
    @DisplayName("deriveModuleName: google-collections relocates to guava coordinates")
    public void deriveGoogleCollections() {
        assertEquals("com.google.guava", ArtifactCandidates.deriveModuleName("com.google.collections", "google-collections"));
    }

    @Test
    @DisplayName("deriveModuleName: non-relocated artifact derives normally")
    public void deriveNonRelocated() {
        assertEquals("com.netflix.spectator.api", ArtifactCandidates.deriveModuleName("com.netflix.spectator", "spectator-api"));
    }

    // -- BOM similarity ordering --

    @Test
    @DisplayName("bomSimilarity: jackson-databind ranks first for com.fasterxml.jackson.databind")
    public void bomSimilarityJacksonDatabind() {
        var sorted = jacksonBomEntries().stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder("com.fasterxml.jackson.databind"))
                .toList();
        assertArtifact("com.fasterxml.jackson.core", "jackson-databind", sorted.getFirst());
    }

    @Test
    @DisplayName("bomSimilarity: jackson-core ranks first for com.fasterxml.jackson.core")
    public void bomSimilarityJacksonCore() {
        var sorted = jacksonBomEntries().stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder("com.fasterxml.jackson.core"))
                .toList();
        assertArtifact("com.fasterxml.jackson.core", "jackson-core", sorted.getFirst());
    }

    @Test
    @DisplayName("bomSimilarity: jackson-annotations ranks first for com.fasterxml.jackson.annotation")
    public void bomSimilarityJacksonAnnotation() {
        var sorted = jacksonBomEntries().stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder("com.fasterxml.jackson.annotation"))
                .toList();
        assertArtifact("com.fasterxml.jackson.core", "jackson-annotations", sorted.getFirst());
    }

    @Test
    @DisplayName("bomSimilarity: jackson-datatype-jdk8 ranks first for com.fasterxml.jackson.datatype.jdk8")
    public void bomSimilarityJacksonDatatypeJdk8() {
        var sorted = jacksonBomEntries().stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder("com.fasterxml.jackson.datatype.jdk8"))
                .toList();
        assertArtifact("com.fasterxml.jackson.datatype", "jackson-datatype-jdk8", sorted.getFirst());
    }

    @Test
    @DisplayName("bomSimilarity: jackson-datatype-jsr310 ranks first for com.fasterxml.jackson.datatype.jsr310")
    public void bomSimilarityJacksonDatatypeJsr310() {
        var sorted = jacksonBomEntries().stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder("com.fasterxml.jackson.datatype.jsr310"))
                .toList();
        assertArtifact("com.fasterxml.jackson.datatype", "jackson-datatype-jsr310", sorted.getFirst());
    }

    @Test
    @DisplayName("bomSimilarity: jackson-module-parameter-names ranks top 2 for com.fasterxml.jackson.module.paramnames")
    public void bomSimilarityJacksonModuleParamnames() {
        // 'paramnames' is a contraction of 'parameter-names' that is closer in
        // edit distance to 'paranamer' (2 edits) than 'parameternames' (4 edits).
        // Both rank well ahead of the bulk of the BOM.
        var target = "com.fasterxml.jackson.module.paramnames";
        var sorted = jacksonBomEntries().stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder(target))
                .toList();
        int rank = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if ("jackson-module-parameter-names".equals(sorted.get(i)
                    .getArtifactId())) {
                rank = i + 1;
                break;
            }
        }
        assertTrue(rank <= 2, "jackson-module-parameter-names should be rank 1 or 2, was " + rank);
    }

    @Test
    @DisplayName("bomSimilarity: full namespace match scores high even with artId suffix")
    public void bomSimilarityFullNamespaceMatch() {
        // org.slf4j:slf4j-api — the groupId IS the module name, artId has
        // a remaining 'api' part after stripping the namespace overlap.
        var target = "org.slf4j";
        var slf4jApi = artifact("org.slf4j", "slf4j-api");
        var slf4jSimple = artifact("org.slf4j", "slf4j-simple");
        var unrelated = artifact("org.apache.logging", "log4j-api");

        double apiScore = bomScore(target, slf4jApi);
        double simpleScore = bomScore(target, slf4jSimple);
        double unrelatedScore = bomScore(target, unrelated);

        assertTrue(apiScore >= 0.5, "slf4j-api should be above threshold, was " + apiScore);
        assertTrue(simpleScore >= 0.5, "slf4j-simple should be above threshold, was " + simpleScore);
        assertTrue(unrelatedScore < apiScore, "unrelated should score below slf4j-api");
    }

    @Test
    @DisplayName("bomSimilarity: exact derived name match scores highest")
    public void bomSimilarityExactMatch() {
        var entries = List.of(artifact("org.junit.jupiter", "junit-jupiter-api"), artifact("org.junit.jupiter", "junit-jupiter-engine"),
                artifact("org.junit.jupiter", "junit-jupiter-params"));
        var sorted = entries.stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder("org.junit.jupiter.api"))
                .toList();
        assertArtifact("org.junit.jupiter", "junit-jupiter-api", sorted.getFirst());
    }

    @Test
    @DisplayName("bomSimilarity: identity match differentiates shared namespaces")
    public void bomSimilarityIdentityMatch() {
        var target = "com.fasterxml.jackson.databind";
        var databind = artifact("com.fasterxml.jackson.core", "jackson-databind");
        var datatype = artifact("com.fasterxml.jackson.datatype", "jackson-datatype-jdk8");
        var core = artifact("com.fasterxml.jackson.core", "jackson-core");

        var sorted = List.of(datatype, core, databind).stream()
                .sorted(ArtifactCandidates.bomSimilarityOrder(target))
                .toList();
        assertArtifact("com.fasterxml.jackson.core", "jackson-databind", sorted.getFirst());
    }

    @Test
    @DisplayName("bomSimilarity: parameter-names and paranamer both score well for paramnames")
    public void bomSimilarityParamnamesVsUnrelated() {
        var paramnames = "com.fasterxml.jackson.module.paramnames".split("\\.");
        var parameterNames = artifact("com.fasterxml.jackson.module", "jackson-module-parameter-names");
        var paranamer = artifact("com.fasterxml.jackson.module", "jackson-module-paranamer");
        var kotlin = artifact("com.fasterxml.jackson.module", "jackson-module-kotlin");

        double parameterNamesScore = ArtifactCandidates.bomSimilarity(paramnames, parameterNames);
        double paranamerScore = ArtifactCandidates.bomSimilarity(paramnames, paranamer);
        double kotlinScore = ArtifactCandidates.bomSimilarity(paramnames, kotlin);

        assertTrue(parameterNamesScore > kotlinScore, "parameter-names (" + parameterNamesScore + ") should beat kotlin (" + kotlinScore + ")");
        assertTrue(paranamerScore > kotlinScore, "paranamer (" + paranamerScore + ") should beat kotlin (" + kotlinScore + ")");
    }

    private static List<Artifact> jacksonBomEntries() {
        return List.of(
                artifact("com.fasterxml.jackson.core", "jackson-annotations"),
                artifact("com.fasterxml.jackson.core", "jackson-core"),
                artifact("com.fasterxml.jackson.core", "jackson-databind"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-avro"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-cbor"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-csv"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-ion"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-properties"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-protobuf"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-smile"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-toml"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-xml"),
                artifact("com.fasterxml.jackson.dataformat", "jackson-dataformat-yaml"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-eclipse-collections"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-guava"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-hibernate4"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-hibernate5"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-hibernate5-jakarta"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-hibernate6"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-hppc"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-jakarta-jsonp"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-jaxrs"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-jdk8"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-joda"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-joda-money"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-json-org"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-jsr310"),
                artifact("com.fasterxml.jackson.datatype", "jackson-datatype-pcollections"),
                artifact("com.fasterxml.jackson.jakarta.rs", "jackson-jakarta-rs-base"),
                artifact("com.fasterxml.jackson.jakarta.rs", "jackson-jakarta-rs-cbor-provider"),
                artifact("com.fasterxml.jackson.jakarta.rs", "jackson-jakarta-rs-json-provider"),
                artifact("com.fasterxml.jackson.jakarta.rs", "jackson-jakarta-rs-smile-provider"),
                artifact("com.fasterxml.jackson.jakarta.rs", "jackson-jakarta-rs-xml-provider"),
                artifact("com.fasterxml.jackson.jakarta.rs", "jackson-jakarta-rs-yaml-provider"),
                artifact("com.fasterxml.jackson.jaxrs", "jackson-jaxrs-base"),
                artifact("com.fasterxml.jackson.jaxrs", "jackson-jaxrs-cbor-provider"),
                artifact("com.fasterxml.jackson.jaxrs", "jackson-jaxrs-json-provider"),
                artifact("com.fasterxml.jackson.jaxrs", "jackson-jaxrs-smile-provider"),
                artifact("com.fasterxml.jackson.jaxrs", "jackson-jaxrs-xml-provider"),
                artifact("com.fasterxml.jackson.jaxrs", "jackson-jaxrs-yaml-provider"),
                artifact("com.fasterxml.jackson.jr", "jackson-jr-all"),
                artifact("com.fasterxml.jackson.jr", "jackson-jr-annotation-support"),
                artifact("com.fasterxml.jackson.jr", "jackson-jr-extension-javatime"),
                artifact("com.fasterxml.jackson.jr", "jackson-jr-objects"),
                artifact("com.fasterxml.jackson.jr", "jackson-jr-retrofit2"),
                artifact("com.fasterxml.jackson.jr", "jackson-jr-stree"),
                artifact("com.fasterxml.jackson.module", "jackson-module-afterburner"),
                artifact("com.fasterxml.jackson.module", "jackson-module-android-record"),
                artifact("com.fasterxml.jackson.module", "jackson-module-blackbird"),
                artifact("com.fasterxml.jackson.module", "jackson-module-guice"),
                artifact("com.fasterxml.jackson.module", "jackson-module-guice7"),
                artifact("com.fasterxml.jackson.module", "jackson-module-jakarta-xmlbind-annotations"),
                artifact("com.fasterxml.jackson.module", "jackson-module-jaxb-annotations"),
                artifact("com.fasterxml.jackson.module", "jackson-module-jsonSchema"),
                artifact("com.fasterxml.jackson.module", "jackson-module-jsonSchema-jakarta"),
                artifact("com.fasterxml.jackson.module", "jackson-module-kotlin"),
                artifact("com.fasterxml.jackson.module", "jackson-module-mrbean"),
                artifact("com.fasterxml.jackson.module", "jackson-module-no-ctor-deser"),
                artifact("com.fasterxml.jackson.module", "jackson-module-osgi"),
                artifact("com.fasterxml.jackson.module", "jackson-module-paranamer"),
                artifact("com.fasterxml.jackson.module", "jackson-module-parameter-names"),
                artifact("com.fasterxml.jackson.module", "jackson-module-scala_2.11"),
                artifact("com.fasterxml.jackson.module", "jackson-module-scala_2.12"),
                artifact("com.fasterxml.jackson.module", "jackson-module-scala_2.13"),
                artifact("com.fasterxml.jackson.module", "jackson-module-scala_3"));
    }

    private static Artifact artifact(String groupId, String artifactId) {
        return new DefaultArtifact(groupId, artifactId, "jar", null);
    }

    private static double bomScore(String target, Artifact artifact) {
        return ArtifactCandidates.bomSimilarity(target.split("\\."), artifact);
    }

    private static void assertArtifact(String expectedGroupId, String expectedArtifactId, Artifact actual) {
        assertEquals(expectedGroupId, actual.getGroupId(), "groupId of " + actual.getGroupId() + ":" + actual.getArtifactId());
        assertEquals(expectedArtifactId, actual.getArtifactId(), "artifactId of " + actual.getGroupId() + ":" + actual.getArtifactId());
    }

    // -- Predecessor lookup --

    @Test
    @DisplayName("predecessors: canonical javax.el-api has el-api as predecessor")
    public void predecessorElApi() {
        var artifact = new DefaultArtifact("javax.el", "javax.el-api", "jar", "3.0.0");
        var predecessors = ArtifactCandidates.predecessors(artifact);
        assertEquals(1, predecessors.size());
        assertEquals("javax.el", predecessors.getFirst()
                .getGroupId());
        assertEquals("el-api", predecessors.getFirst()
                .getArtifactId());
        assertEquals("3.0.0", predecessors.getFirst()
                .getVersion());
    }

    @Test
    @DisplayName("predecessors: artifact with no relocations returns empty")
    public void predecessorNone() {
        var artifact = new DefaultArtifact("org.slf4j", "slf4j-api", "jar", "2.0.17");
        assertTrue(ArtifactCandidates.predecessors(artifact)
                .isEmpty());
    }

    @Test
    @DisplayName("predecessors: guava has google-collections and guava-jdk5")
    public void predecessorGuava() {
        var artifact = new DefaultArtifact("com.google.guava", "guava", "jar", "33.0.0-jre");
        var predecessors = ArtifactCandidates.predecessors(artifact).stream()
                .map(a -> a.getGroupId() + ":" + a.getArtifactId())
                .sorted()
                .toList();
        assertEquals(List.of("com.google.collections:google-collections", "com.google.guava:guava-jdk5"), predecessors);
    }
}
