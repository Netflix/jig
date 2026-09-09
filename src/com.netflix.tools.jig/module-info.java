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

/**
 * Resolves module paths, module source paths, module versions, and tool
 * arguments for modular Java projects.
 *
 * <p>Jig discovers source and compiled modules, reads dependency and version
 * opinions from module declarations, maps stable module names to artifacts in
 * Maven repositories, mediates versions, and validates the resulting module
 * graph. It can resolve binary modules, source JARs, JMODs, annotation
 * processors, target-platform variants, and module-content hashes.
 *
 * <p>The resolved graph is projected as standard arguments for {@code javac},
 * {@code java}, {@code javadoc}, {@code jar}, and {@code jlink}, or as
 * caller-selected standard options for other module-aware tools. Source
 * modules are compiled lazily when a requested projection requires binary
 * modules. Jig also provides module-name and version lookup, Maven install and
 * deployment, and an HTTP repository view of its canonical module namespace.
 * The command is available directly and through
 * {@link java.util.spi.ToolProvider}.
 *
 * @mainClass com.netflix.tools.jig.Jig
 */
module com.netflix.tools.jig {
    requires transitive java.compiler;
    requires java.net.http;
    requires java.xml;
    requires jdk.compiler;
    requires jdk.httpserver;

    exports com.netflix.module;
    exports com.netflix.module.compile;
    exports com.netflix.module.compile.internal to com.netflix.tools.jig.test;

    opens com.netflix.module.compile to com.netflix.tools.jig.test;

    exports com.netflix.tools.jig to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.logging to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.openpgp to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.apache.maven.api.metadata to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.apache.maven.api.model to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.apache.maven.metadata.v4 to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.apache.maven.model.v4 to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.apache.maven.settings to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.artifact to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.collection to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.graph to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.named to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.repository to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.resolution to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.scope to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.filter to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.supplier to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.transport.file to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.util.concurrency to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.util.repository to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.internal.org.eclipse.aether.version to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.module to com.netflix.tools.jig.test;
    exports com.netflix.tools.jig.module.maven.transport to com.netflix.tools.jig.test;

    provides java.util.spi.ToolProvider with com.netflix.tools.jig.Jig;
}
