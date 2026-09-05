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

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.module.ArtifactCandidates;
import com.netflix.tools.jig.module.Trace;
import com.netflix.tools.jig.module.maven.transport.ModuleLocationTransporter;
import com.netflix.tools.jig.test.module.TestRepositorySystem.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModuleLocationTransporterTest {

    private static Context context;
    private static ModuleLocationTransporter transporter;

    @TempDir
    private static Path localRepo;

    @BeforeAll
    static void setUp() {
        context = TestRepositorySystem.create(localRepo, List.of(TestRepositorySystem.centralRepository()), Map.of());
        transporter = new ModuleLocationTransporter(context.system(), context.session(), context.session(),
                context.repositories(), context.repositories());
    }

    @AfterAll
    static void tearDown() {
        transporter.close();
        context.close();
    }

    @Test
    @DisplayName("org.hibernate.orm.core serves relocation to org.hibernate.orm:hibernate-core")
    void hibernateCoreRelocatesToHibernateOrm() throws Exception {
        assertLocation("org.hibernate.orm.core", "7.4.5.Final", "org.hibernate.orm", "hibernate-core");
    }

    @Test
    @DisplayName("com.oracle.database.jdbc serves relocation to com.oracle.database.jdbc:ojdbc11")
    void oracleJdbcRelocatesToOjdbc11() throws Exception {
        assertLocation("com.oracle.database.jdbc", "23.26.3.0.0", "com.oracle.database.jdbc", "ojdbc11");
    }

    @Test
    @DisplayName("com.oracle.database.jdbc.rsi serves relocation to com.oracle.database.jdbc:rsi")
    void oracleRsiRelocatesToRsi() throws Exception {
        assertLocation("com.oracle.database.jdbc.rsi", "23.26.3.0.0", "com.oracle.database.jdbc", "rsi");
    }

    @Test
    @DisplayName("org.eclipse.angus.mail serves relocation to org.eclipse.angus:angus-core")
    void angusMailRelocatesToAngusCore() throws Exception {
        assertLocation("org.eclipse.angus.mail", "2.0.5", "org.eclipse.angus", "angus-core");
    }

    @Test
    @DisplayName("org.eclipse.angus.mail.gimap serves relocation to org.eclipse.angus:gimap")
    void angusGimapRelocatesToGimap() throws Exception {
        assertLocation("org.eclipse.angus.mail.gimap", "2.0.5", "org.eclipse.angus", "gimap");
    }

    @Test
    @DisplayName("org.apache.derby.client serves relocation to org.apache.derby:derbyclient")
    void derbyClientRelocatesToDerbyClient() throws Exception {
        assertLocation("org.apache.derby.client", "10.16.1.1", "org.apache.derby", "derbyclient");
    }

    @Test
    @DisplayName("com.sun.xml.bind serves relocation to com.sun.xml.bind:jaxb-impl")
    void jaxbImplementationRelocatesToJaxbImpl() throws Exception {
        assertLocation("com.sun.xml.bind", "4.0.9", "com.sun.xml.bind", "jaxb-impl");
    }

    @Test
    @DisplayName("r2dbc.pool serves relocation to io.r2dbc:r2dbc-pool")
    void r2dbcPoolRelocatesToR2dbcPool() throws Exception {
        assertLocation("r2dbc.pool", "1.0.2.RELEASE", "io.r2dbc", "r2dbc-pool");
    }

    @Test
    @DisplayName("org.slf4j.jul serves relocation to org.slf4j:slf4j-jdk14")
    void slf4jJulRelocatesToSlf4jJdk14() throws Exception {
        assertLocation("org.slf4j.jul", "2.0.18", "org.slf4j", "slf4j-jdk14");
    }

    @Test
    @DisplayName("com.sun.xml.txw2 serves relocation to org.glassfish.jaxb:txw2")
    void txw2RelocatesToGlassfishJaxb() throws Exception {
        assertLocation("com.sun.xml.txw2", "4.0.9", "org.glassfish.jaxb", "txw2");
    }

    @Test
    @DisplayName("org.slf4j serves relocation to org.slf4j:slf4j-api")
    void slf4jRelocatesToSlf4jApi() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("org.slf4j", "2.0.17");
        String pom = getPomString(location);
        assertRelocation(pom, "org.slf4j", "slf4j-api");
    }

    @Test
    @DisplayName("org.junit.jupiter.api serves relocation to org.junit.jupiter:junit-jupiter-api")
    void junitRelocatesToJupiterApi() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("org.junit.jupiter.api", "5.12.2");
        String pom = getPomString(location);
        assertRelocation(pom, "org.junit.jupiter", "junit-jupiter-api");
    }

    @Test
    @DisplayName("org.objectweb.asm serves relocation to org.ow2.asm:asm")
    void asmRelocatesToOwAsm() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("org.objectweb.asm", "9.7.1");
        String pom = getPomString(location);
        assertRelocation(pom, "org.ow2.asm", "asm");
    }

    @Test
    @DisplayName("com.fasterxml.jackson.databind serves relocation to jackson-databind")
    void jacksonDatabindRelocation() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("com.fasterxml.jackson.databind", "2.18.3");
        String pom = getPomString(location);
        assertRelocation(pom, "com.fasterxml.jackson.core", "jackson-databind");
    }

    @Test
    @DisplayName("spring.core serves relocation to org.springframework:spring-core")
    void springCoreRelocation() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("spring.core", "6.2.7");
        String pom = getPomString(location);
        assertRelocation(pom, "org.springframework", "spring-core");
    }

    @Test
    @DisplayName("org.yaml.snakeyaml serves relocation to org.yaml:snakeyaml")
    void snakeyamlRelocation() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("org.yaml.snakeyaml", "2.3");
        assertEquals("org.yaml.module", location.getGroupId());
        assertEquals("org.yaml.snakeyaml", location.getArtifactId());

        String pom = getPomString(location);
        assertRelocation(pom, "org.yaml", "snakeyaml");
    }

    @Test
    void generatedLocationPomProvidesChecksums() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("org.yaml.snakeyaml", "2.3");
        var task = new GetTask(pomUri(location));

        transporter.get(task);

        assertEquals(Set.of("SHA-1", "MD5"),
                task.getChecksums().keySet());
    }

    @Test
    @DisplayName("version in POM matches requested version")
    void versionPassesThrough() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("org.slf4j", "2.0.17");
        String pom = getPomString(location);
        assertTrue(pom.contains("<version>2.0.17</version>"), "POM version should match requested version:\n" + pom);
    }

    @Test
    @DisplayName("relocation omits version for pass-through")
    void relocationOmitsVersion() throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("org.slf4j", "2.0.17");
        String pom = getPomString(location);
        // The relocation block should not contain a <version> element
        int relocationStart = pom.indexOf("<relocation>");
        int relocationEnd = pom.indexOf("</relocation>");
        String relocationBlock = pom.substring(relocationStart, relocationEnd);
        assertFalse(relocationBlock.contains("<version>"), "relocation should omit version for pass-through:\n" + relocationBlock);
    }

    @Test
    void failedDiscoveryIsSharedAcrossTransporterInstances() throws Exception {
        Path repository = Files.createDirectories(localRepo.resolve("missing-repository"));
        var remote = new Builder("missing", "default",
                repository.toUri().toString()).build();
        var output = new StringWriter();
        Artifact location = ArtifactCandidates.moduleLocationCoordinate("com.example.missing", "1.0");

        try (var missingContext = TestRepositorySystem.create(localRepo.resolve("missing-local"), List.of(remote), Map.of());
             var first = new ModuleLocationTransporter(missingContext.system(), missingContext.session(), missingContext.session(),
                     missingContext.repositories(), missingContext.repositories());
             var second = new ModuleLocationTransporter(missingContext.system(), missingContext.session(), missingContext.session(),
                     missingContext.repositories(), missingContext.repositories())) {
            Trace.withOutput(new PrintWriter(output, true),
                    () -> {
                        assertThrows(FileNotFoundException.class, () -> first.get(new GetTask(pomUri(location))));
                        assertThrows(FileNotFoundException.class, () -> second.get(new GetTask(pomUri(location))));
                    });
        }

        assertEquals(
                1L,
                output.toString()
                      .lines()
                      .filter(line -> line.endsWith("locate module com.example.missing -> not found]"))
                      .count(),
                output.toString());
    }

    @Test
    @DisplayName("non-location POM path returns 404")
    void nonLocationPomReturns404() {
        GetTask task = new GetTask(URI.create("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.pom"));
        assertThrows(FileNotFoundException.class, () -> transporter.get(task));
    }

    @Test
    @DisplayName("non-POM path returns 404")
    void nonPomPathReturns404() {
        GetTask task = new GetTask(URI.create("org/slf4j/module/org.slf4j/2.0.17/org.slf4j-2.0.17.jar"));
        assertThrows(FileNotFoundException.class, () -> transporter.get(task));
    }

    private void assertLocation(String moduleName, String version, String groupId,
            String artifactId)
            throws Exception {
        Artifact location = ArtifactCandidates.moduleLocationCoordinate(moduleName, version);
        assertRelocation(getPomString(location), groupId, artifactId);
    }

    private String getPomString(Artifact location) throws Exception {
        GetTask task = new GetTask(pomUri(location));
        transporter.get(task);
        return new String(task.getDataBytes());
    }

    private URI pomUri(Artifact artifact) {
        String path = artifact.getGroupId().replace('.', '/')
                + "/"
                + artifact.getArtifactId()
                + "/"
                + artifact.getVersion()
                + "/"
                + artifact.getArtifactId()
                + "-"
                + artifact.getVersion()
                + ".pom";
        return URI.create(path);
    }

    private void assertRelocation(String pomXml, String expectedGroupId, String expectedArtifactId) {
        assertTrue(pomXml.contains("<packaging>pom</packaging>"), "should have pom packaging, got:\n" + pomXml);
        assertTrue(pomXml.contains("<distributionManagement>"), "should contain distributionManagement, got:\n" + pomXml);
        assertTrue(pomXml.contains("<relocation>"), "should contain relocation, got:\n" + pomXml);
        assertTrue(pomXml.contains("<groupId>" + expectedGroupId + "</groupId>"), "relocation should point to " + expectedGroupId + ", got:\n" + pomXml);
        assertTrue(pomXml.contains("<artifactId>" + expectedArtifactId + "</artifactId>"), "relocation should point to " + expectedArtifactId + ", got:\n" + pomXml);
    }

    private static RemoteRepository centralRepository() {
        return new Builder("central", "default", "https://repo.maven.apache.org/maven2/")
                .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                .setSnapshotPolicy(new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                .build();
    }
}
