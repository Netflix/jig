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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.jar.Attributes.Name;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy;
import com.netflix.tools.jig.module.ModuleRepositorySession;

final class AotWarmup {
    private static final String VERSION = "1.0";
    private static final String APPLICATION_MODULE = "com.netflix.tools.jig.warmup";
    private static final String LIBRARY_MODULE = "com.example.library";
    private static final String CHILD_MODULE = "com.example.child";

    private AotWarmup() {}

    static int run() throws IOException {
        Path work = Files.createTempDirectory("jig-aot-warmup-");
        try {
            initializeConfiguredSession();
            Path repository = Files.createDirectories(work.resolve("repository"));
            publishModule(repository, CHILD_MODULE, "");
            publishModule(repository, LIBRARY_MODULE,
                    """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>com.example.child</artifactId>
                        <version>1.0</version>
                      </dependency>
                    </dependencies>
                    """);
            writeMetadata(repository, LIBRARY_MODULE);

            var policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
            var remote = new Builder("warmup", "default", repository.toUri()
                    .toString())
                    .setReleasePolicy(policy)
                    .build();
            Path cache = work.resolve("cache");
            Supplier<ModuleRepositorySession> sessions = () -> ModuleRepositorySession.create(cache, List.of(remote));

            Path sourceRoot = writeApplicationSources(work.resolve("src"));
            Path arguments = Files.createDirectories(work.resolve("arguments"));
            runJig(
                    sessions,
                    "--module-source-path",
                    sourceRoot.toString(),
                    "--module",
                    APPLICATION_MODULE,
                    "--module-version",
                    VERSION,
                    "--prefer-jmod",
                    "--generate-consumer-pom",
                    work.resolve("publication").toString(),
                    "--resolve-options",
                    "module-path,processor-module-path,upgrade-module-path," + "module-source-path,source-path,module=list,module-version," + "patch-module,release,enable-preview,add-exports",
                    "--write-argfile",
                    arguments.resolve("compile.args").toString());
            runJig(
                    sessions,
                    "--module-source-path",
                    sourceRoot.toString(),
                    "--module",
                    APPLICATION_MODULE,
                    "--prefer-jmod",
                    "--resolve-options",
                    "module-path,upgrade-module-path,patch-module,add-modules," + "module=main,multi-release,enable-preview,enable-native-access," + "enable-final-field-mutation,add-opens,add-exports",
                    "--validate-runtime-access");
            runJig(sessions, "--lookup-module", "pkg:maven/com.example/com.example.library@" + VERSION);
            runJig(sessions, "--list-module-versions", LIBRARY_MODULE);
            return 0;
        } finally {
            deleteTree(work);
        }
    }

    private static void initializeConfiguredSession() {
        try (var _ = ModuleRepositorySession.create()) {}
    }

    private static Path writeApplicationSources(Path sourceRoot) throws IOException {
        Path module = Files.createDirectories(sourceRoot.resolve(APPLICATION_MODULE));
        Files.writeString(module.resolve("module-info.java"),
                """
                /**
                 * @release %d
                 * @mainClass com.netflix.tools.jig.warmup.Main
                 */
                module com.netflix.tools.jig.warmup {
                    requires java.compiler;
                    requires java.net.http;
                    requires java.xml;
                    requires jdk.compiler;
                    requires jdk.httpserver;
                    requires com.example.library; // @1.0
                }
                """
                        .formatted(Runtime.version().feature()));
        Path packageDirectory = Files.createDirectories(module.resolve("com/netflix/tools/jig/warmup"));
        Files.writeString(packageDirectory.resolve("Main.java"),
                """
                package com.netflix.tools.jig.warmup;

                import com.sun.net.httpserver.HttpServer;
                import com.sun.source.tree.Tree;
                import java.net.http.HttpClient;
                import javax.tools.JavaCompiler;
                import javax.xml.parsers.DocumentBuilderFactory;

                public final class Main {
                    private Main() {}

                    public static void main(String[] arguments) {
                        Class<?>[] types = {
                            HttpServer.class,
                            Tree.class,
                            HttpClient.class,
                            JavaCompiler.class,
                            DocumentBuilderFactory.class
                        };
                        if (types.length != 5) throw new AssertionError();
                    }
                }
                """);
        Files.writeString(packageDirectory.resolve("warmup.properties"), "name=jig\n");
        return sourceRoot;
    }

    private static void publishModule(Path repository, String moduleName, String pomContent) throws IOException {
        String groupId = "com.example";
        Path version = Files.createDirectories(repository.resolve(groupId.replace('.', '/') + "/" + moduleName + "/" + VERSION));
        String file = moduleName + "-" + VERSION;
        Files.writeString(version.resolve(file + ".pom"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  %s
                </project>
                """
                        .formatted(groupId, moduleName, VERSION, pomContent));
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(version.resolve(file + ".jar")), manifest)) {}
    }

    private static void writeMetadata(Path repository, String moduleName) throws IOException {
        Path artifact = repository.resolve("com/example/" + moduleName + "/maven-metadata.xml");
        Files.writeString(artifact,
                """
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <latest>%s</latest>
                    <release>%s</release>
                    <versions><version>%s</version></versions>
                  </versioning>
                </metadata>
                """
                        .formatted(moduleName, VERSION, VERSION, VERSION));
    }

    private static void runJig(Supplier<ModuleRepositorySession> sessions, String... arguments) {
        var output = new StringWriter();
        var errors = new StringWriter();
        int result = new Jig().runWithSessions(new PrintWriter(output, true), new PrintWriter(errors, true), sessions, arguments);
        if (result != 0) {
            throw new IllegalStateException("Jig AOT warmup operation failed:\n" + errors);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isDirectory).toList()) {
                path.toFile().setWritable(true, true);
            }
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
