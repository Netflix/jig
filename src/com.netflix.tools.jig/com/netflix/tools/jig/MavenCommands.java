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
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;

import com.netflix.tools.jig.MavenCentralPortal.Approval;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.module.MavenCentralBundle;
import com.netflix.tools.jig.module.MavenDeployment;
import com.netflix.tools.jig.module.ModuleRepositorySession;

/** Maven deployment operations over flat, module-named artifacts. */
final class MavenCommands {
    private MavenCommands() {}

    static int run(PrintWriter out, PrintWriter err, Supplier<ModuleRepositorySession> sessions,
                   String... arguments) {
        if (arguments.length == 0 || isHelp(arguments[0])) {
            printHelp(out);
            return arguments.length == 0 ? 2 : 0;
        }
        String operation = arguments[0];
        if (!operation.equals("install") && !operation.equals("deploy") && !operation.equals("deploy-central")) {
            err.println("jig: unknown Maven operation: " + operation);
            return 2;
        }
        try {
            Request request = parse(operation, Arrays.copyOfRange(arguments, 1, arguments.length));
            try (var session = sessions.get();
                 var deployment = MavenDeployment.create(request.artifacts(), session,
                         request.moduleVersion())) {
                if (operation.equals("install")) {
                    session.install(deployment.artifacts());
                } else if (operation.equals("deploy")) {
                    deploy(deployment, request, session);
                } else {
                    deployCentral(out, deployment, request, session);
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("jig: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("jig: " + e.getMessage());
            return 1;
        }
    }

    private static void deploy(MavenDeployment deployment, Request request, ModuleRepositorySession session) throws IOException {
        RemoteRepository repository = request.repository();
        if (repository == null) {
            throw new IllegalArgumentException("Maven deploy requires --repository");
        }
        MavenArtifactSigner signer = request.sign() ? MavenArtifactSigner.fromEnvironment(System.getenv(), Clock.systemUTC()) : null;
        try (signer) {
            Collection<Artifact> artifacts = signer == null ? deployment.artifacts() : signer.sign(deployment.artifacts());
            session.deploy(artifacts, repository);
        }
    }

    private static void deployCentral(PrintWriter out, MavenDeployment deployment, Request request,
            ModuleRepositorySession session)
            throws IOException {
        try (var portal = MavenCentralPortal.fromCredentials(System.getenv(), session.serverCredentials("central"));
             var signer = MavenArtifactSigner.fromEnvironment(System.getenv(), Clock.systemUTC())) {
            Collection<Artifact> artifacts = signer.sign(deployment.artifacts());
            try (var bundle = MavenCentralBundle.create(artifacts)) {
                Approval approval = request.manual() ? Approval.MANUAL : Approval.AUTOMATIC;
                out.println(portal.publish(bundle.path(), request.name(), approval));
            }
        }
    }

    private static Request parse(String operation, String[] arguments) {
        Path artifacts = null;
        RemoteRepository repository = null;
        String name = null;
        String moduleVersion = null;
        boolean sign = false;
        boolean manual = false;
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            if (argument.equals("--module-version")) {
                moduleVersion = moduleVersion(moduleVersion,
                        requireArgument(arguments, ++i, argument));
            } else if (argument.startsWith("--module-version=")) {
                moduleVersion = moduleVersion(moduleVersion,
                        argument.substring("--module-version=".length()));
            } else if (argument.equals("--repository")) {
                repository = repository(requireArgument(arguments, ++i, argument));
            } else if (argument.startsWith("--repository=")) {
                repository = repository(argument.substring("--repository=".length()));
            } else if (argument.equals("--name")) {
                name = requireArgument(arguments, ++i, argument);
            } else if (argument.startsWith("--name=")) {
                name = argument.substring("--name=".length());
            } else if (argument.equals("--sign")) {
                sign = true;
            } else if (argument.equals("--manual")) {
                manual = true;
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("unknown Maven " + operation + " option: " + argument);
            } else if (artifacts == null) {
                artifacts = Path.of(argument);
            } else {
                throw new IllegalArgumentException("Maven " + operation + " accepts one artifact directory");
            }
        }
        if (artifacts == null) {
            throw new IllegalArgumentException("Maven " + operation + " requires an artifact directory");
        }
        if (!operation.equals("deploy") && repository != null) {
            throw new IllegalArgumentException("--repository applies only to Maven deploy");
        }
        if (!operation.equals("deploy") && sign) {
            throw new IllegalArgumentException("--sign applies only to Maven deploy; deploy-central signs automatically");
        }
        if (!operation.equals("deploy-central") && name != null) {
            throw new IllegalArgumentException("--name applies only to Maven deploy-central");
        }
        if (!operation.equals("deploy-central") && manual) {
            throw new IllegalArgumentException("--manual applies only to Maven deploy-central");
        }
        return new Request(artifacts, repository, name, moduleVersion, sign, manual);
    }

    private static String moduleVersion(String current, String value) {
        if (current != null) {
            throw new IllegalArgumentException("--module-version may only be specified once");
        }
        return Jig.Options.parseModuleVersion(value);
    }

    private static String requireArgument(String[] arguments, int index, String option) {
        if (index >= arguments.length) {
            throw new IllegalArgumentException(option + " requires an argument");
        }
        return arguments[index];
    }

    private static RemoteRepository repository(String value) {
        int separator = value.indexOf('=');
        if (separator < 0 && !hasUriScheme(value)) {
            return repository("file",
                    Path.of(value)
                            .toAbsolutePath()
                            .normalize()
                            .toUri());
        }
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("repository must have the form ID=URI or PATH");
        }
        String id = value.substring(0, separator);
        String location = value.substring(separator + 1);
        URI uri = URI.create(location);
        if (uri.getScheme() == null) {
            uri = Path.of(location)
                    .toAbsolutePath()
                    .normalize()
                    .toUri();
        }
        return repository(id, uri);
    }

    private static RemoteRepository repository(String id, URI uri) {
        return new Builder(id, "default", uri.toString()).build();
    }

    private static boolean hasUriScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        if (colon == 1
                && Character.isLetter(value.charAt(0))
                && value.length() > 2
                && (value.charAt(2) == '/' || value.charAt(2) == '\\')) {
            return false;
        }
        if (!Character.isLetter(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < colon; i++) {
            char character = value.charAt(i);
            if (!Character.isLetterOrDigit(character)
                    && character != '+'
                    && character != '-'
                    && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHelp(String argument) {
        return argument.equals("-h") || argument.equals("--help");
    }

    private static void printHelp(PrintWriter out) {
        out.println("Usage: jig maven install [--module-version <version>] <artifact-directory>");
        out.println("       jig maven deploy [--module-version <version>] --repository <id=uri|path> [--sign] <artifact-directory>");
        out.println("       jig maven deploy-central [--module-version <version>] [--name <name>] [--manual] <artifact-directory>");
        out.println();
        out.println("Installs or deploys flat, module-named artifacts.");
    }

    private record Request(Path artifacts, RemoteRepository repository, String name,
                           String moduleVersion, boolean sign, boolean manual) {}
}
