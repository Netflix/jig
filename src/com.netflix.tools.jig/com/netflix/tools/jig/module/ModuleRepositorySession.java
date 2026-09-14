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
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

import com.netflix.module.compile.ModuleCompiler;
import com.netflix.tools.jig.internal.maven.MavenSettingsDecrypter;
import com.netflix.tools.jig.internal.org.apache.maven.settings.Settings;
import com.netflix.tools.jig.internal.org.apache.maven.settings.building.DefaultSettingsBuilder;
import com.netflix.tools.jig.internal.org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import com.netflix.tools.jig.internal.org.apache.maven.settings.building.SettingsBuildingException;
import com.netflix.tools.jig.internal.org.apache.maven.settings.io.DefaultSettingsReader;
import com.netflix.tools.jig.internal.org.apache.maven.settings.io.DefaultSettingsWriter;
import com.netflix.tools.jig.internal.org.apache.maven.settings.validation.DefaultSettingsValidator;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession.CloseableSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession.SessionBuilder;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.Artifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.artifact.DefaultArtifact;
import com.netflix.tools.jig.internal.org.eclipse.aether.deployment.DeployRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.deployment.DeploymentException;
import com.netflix.tools.jig.internal.org.eclipse.aether.installation.InstallRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.installation.InstallationException;
import com.netflix.tools.jig.internal.org.eclipse.aether.metadata.Metadata;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.Proxy;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository.Builder;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ArtifactResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ResolutionErrorPolicy;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.ResolutionErrorPolicyRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeRequest;
import com.netflix.tools.jig.internal.org.eclipse.aether.resolution.VersionRangeResolutionException;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilterSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.TransporterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.supplier.RepositorySystemSupplier;
import com.netflix.tools.jig.internal.org.eclipse.aether.supplier.SessionBuilderSupplier;
import com.netflix.tools.jig.internal.org.eclipse.aether.transport.file.FileTransporterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.concurrency.SmartExecutor;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.repository.AuthenticationBuilder;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.repository.DefaultMirrorSelector;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.repository.DefaultProxySelector;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import com.netflix.tools.jig.module.AetherModuleResolver.Result;
import com.netflix.tools.jig.module.maven.transport.BomLocationTransporterFactory;
import com.netflix.tools.jig.module.maven.transport.JdkTransporterFactory;
import com.netflix.tools.jig.module.maven.transport.ModuleLocationTransporterFactory;
import com.netflix.tools.jig.module.maven.transport.ModuleProbeRepositoryFilterSource;
import com.netflix.tools.jig.module.maven.transport.ModuleProxyTransporter;
import com.netflix.tools.jig.module.maven.transport.ModuleProxyTransporterFactory;

/** A module repository session backed by Maven Resolver. */
public final class ModuleRepositorySession implements AutoCloseable {

    private static final String APPLICATION_ID = "com.netflix.tools.jig";

    private final RepositorySystem system;
    private final CloseableSession backendSession;
    private final CloseableSession locationSession;
    private final CloseableSession consumerSession;
    private final Path moduleRepositoryPath;
    private final RemoteRepository moduleRepository;
    private final List<RemoteRepository> backendRepositories;
    private final ModuleLocationTransporterFactory locationFactory;
    private final BomLocationTransporterFactory bomLocationFactory;
    private final ModuleProxyTransporterFactory moduleProxyFactory;
    private final ModuleCompiler moduleCompiler;
    private final Settings settings;

    public static ModuleRepositorySession create() {
        return create(null);
    }

    public static ModuleRepositorySession create(DiagnosticListener<? super JavaFileObject> diagnosticListener) {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path userHome = Path.of(System.getProperty("user.home"));
        Path userSettings = userHome.resolve(".m2").resolve("settings.xml");
        Path localRepository = userHome.resolve(".m2").resolve("repository");
        Path cache = cacheDirectory(System.getProperty("os.name"), userHome, System.getenv());

        Settings settings = readSettings(distributionSettings(javaHome), userSettings);
        if (settings.getLocalRepository() != null) {
            localRepository = Path.of(settings.getLocalRepository());
        }
        return new ModuleRepositorySession(localRepository, cache.resolve("repository/locations"), cache.resolve("repository/modules"), cache,
                repositories(settings), settings, diagnosticListener);
    }

    public static ModuleRepositorySession create(Path localRepository, List<RemoteRepository> repositories) {
        return new ModuleRepositorySession(localRepository.resolve("repository/maven"), localRepository.resolve("repository/locations"), localRepository.resolve("repository/modules"),
                localRepository, repositories, new Settings(), null);
    }

    public static Path cacheDirectory(String operatingSystem, Path userHome, Map<String, String> environment) {
        if (operatingSystem.toLowerCase(Locale.ROOT).contains("win")) {
            String configured = environment.get("LOCALAPPDATA");
            Path root = configured == null || configured.isBlank()
                    ? userHome.resolve("AppData/Local")
                    : Path.of(configured);
            return root.resolve(APPLICATION_ID);
        }
        String configured = environment.get("XDG_CACHE_HOME");
        Path root = configured == null || configured.isBlank()
                ? userHome.resolve(".cache")
                : Path.of(configured);
        return root.resolve(APPLICATION_ID);
    }

    private ModuleRepositorySession(
            Path backendRepository,
            Path locationRepository,
            Path consumerRepository,
            Path cacheRoot,
            List<RemoteRepository> repositories,
            Settings settings,
            DiagnosticListener<? super JavaFileObject> diagnosticListener) {
        this.settings = settings;
        this.locationFactory = new ModuleLocationTransporterFactory();
        this.bomLocationFactory = new BomLocationTransporterFactory();
        this.moduleProxyFactory = new ModuleProxyTransporterFactory();
        try {
            this.moduleCompiler = new ModuleCompiler(cacheRoot, diagnosticListener);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open compiled module outputs", e);
        }
        this.system = createRepositorySystem(locationFactory, bomLocationFactory, moduleProxyFactory);

        // Backend misses are repository facts. Virtual repository misses are not
        // cached because later dependency inspection can make them discoverable.
        var backendBuilder = newSessionBuilder(backendRepository).setResolutionErrorPolicy(MISSING_RESOURCE_CACHE_POLICY);
        configureSettings(backendBuilder, settings);
        this.backendSession = backendBuilder.build();
        seedVirtualThreadExecutor(backendSession);

        this.backendRepositories = system.newResolutionRepositories(backendSession, repositories);
        var bomLocationRepository = virtualRepository("jig-location-bom", "jig+location-bom://virtual");
        var bomRepositories = new ArrayList<>(backendRepositories);
        bomRepositories.add(bomLocationRepository);

        var locationBuilder = newSessionBuilder(locationRepository);
        this.locationSession = locationBuilder.build();
        seedVirtualThreadExecutor(locationSession);

        locationFactory.configure(system, backendSession, backendRepositories, bomRepositories);
        bomLocationFactory.configure(system, backendSession, backendRepositories);
        moduleProxyFactory.configure(system, backendSession, locationSession, backendRepositories);

        this.moduleRepositoryPath = consumerRepository.toAbsolutePath().normalize();
        this.consumerSession = newSessionBuilder(consumerRepository).build();
        seedVirtualThreadExecutor(consumerSession);
        this.moduleRepository = virtualRepository("jig-modules", "jig+module://virtual");
    }

    public Path moduleRepositoryPath() {
        return moduleRepositoryPath;
    }

    public PasswordAuthentication serverCredentials(String id) {
        for (var server : settings.getServers()) {
            if (id.equals(server.getId())
                    && server.getUsername() != null
                    && !server.getUsername().isBlank()
                    && server.getPassword() != null
                    && !server.getPassword().isBlank()) {
                return new PasswordAuthentication(server.getUsername(),
                        server.getPassword().toCharArray());
            }
        }
        return null;
    }

    public Result resolveModules(Collection<ModuleDescriptor> roots, boolean includeStatics) {
        return resolveModules(roots, includeStatics, false);
    }

    public Result resolveModules(Collection<ModuleDescriptor> roots, boolean includeStatics, boolean includeSources) {
        try (var proxy = newModuleTransporter()) {
            return new AetherModuleResolver(system, consumerSession, List.of(moduleRepository), proxy::moduleDescriptor)
                    .resolve(roots, includeStatics, includeSources);
        }
    }

    public ModuleCompiler moduleCompiler() {
        return moduleCompiler;
    }

    public ModuleProxyTransporter newModuleTransporter() {
        return new ModuleProxyTransporter(system, backendSession, locationSession, backendRepositories);
    }

    public Artifact locateModule(String moduleName, String version) throws IOException {
        try (var transporter = newModuleTransporter()) {
            return transporter.locateModule(moduleName, version);
        }
    }

    List<RemoteRepository> pomRepositories() {
        return List.copyOf(backendRepositories);
    }

    RemoteRepository configurePomRepository(RemoteRepository repository) {
        return system.newResolutionRepositories(backendSession, List.of(repository)).getFirst();
    }

    Path resolvePom(String groupId, String artifactId, String version,
                    List<RemoteRepository> repositories)
            throws IOException {
        Artifact pom = new DefaultArtifact(groupId, artifactId, "pom", version);
        try {
            return system.resolveArtifact(backendSession, new ArtifactRequest(pom, repositories, null))
                         .getArtifact()
                         .getPath();
        } catch (ArtifactResolutionException e) {
            throw new IOException("Failed to resolve Maven POM " + pom, e);
        }
    }

    String resolvePomVersion(String groupId, String artifactId, String version,
            List<RemoteRepository> repositories)
            throws IOException {
        Artifact pom = new DefaultArtifact(groupId, artifactId, "pom", version);
        try {
            var result = system.resolveVersionRange(backendSession, new VersionRangeRequest(pom, repositories, null));
            if (result.getHighestVersion() == null) {
                throw new IOException("No versions match Maven POM " + pom);
            }
            var constraint = result.getVersionConstraint();
            if (constraint != null && constraint.getRange() != null && constraint.getRange().getUpperBound() == null) {
                throw new IOException("Maven POM version range has no upper bound: " + pom);
            }
            return result.getHighestVersion().toString();
        } catch (VersionRangeResolutionException e) {
            throw new IOException("Failed to resolve Maven POM version " + pom, e);
        }
    }

    public void install(Collection<Artifact> artifacts) throws IOException {
        try {
            system.install(backendSession, new InstallRequest().setArtifacts(artifacts));
        } catch (InstallationException e) {
            throw new IOException("Failed to install Maven deployment", e);
        }
    }

    public void deploy(Collection<Artifact> artifacts, RemoteRepository repository) throws IOException {
        RemoteRepository configured = system.newDeploymentRepository(backendSession, repository);
        try {
            system.deploy(backendSession, new DeployRequest()
                    .setArtifacts(artifacts)
                    .setRepository(configured));
        } catch (DeploymentException e) {
            throw new IOException("Failed to deploy Maven artifacts to " + repository.getUrl(), e);
        }
    }

    public Map<String, Path> resolveJmodPath(Map<String, String> modules, String targetClassifier) {
        var paths = new LinkedHashMap<String, Path>();
        for (var entry : modules.entrySet()) {
            Artifact canonical = ArtifactCandidates.locationCoordinate(entry.getKey(), entry.getValue());
            Artifact artifact = targetClassifier == null ? null : resolveOptional(
                    backendSession,
                    new DefaultArtifact(canonical.getGroupId(), canonical.getArtifactId(), targetClassifier, "jmod",
                            entry.getValue()),
                    backendRepositories);
            if (artifact == null) {
                artifact = resolveOptional(
                        backendSession,
                        new DefaultArtifact(canonical.getGroupId(), canonical.getArtifactId(), "jmod",
                                entry.getValue()),
                        backendRepositories);
            }
            if (artifact == null || artifact.getPath() == null) {
                continue;
            }
            paths.put(entry.getKey(), artifact.getPath());
        }
        return Map.copyOf(paths);
    }

    public Map<String, Path> resolveTargetJarPath(Map<String, String> modules, String targetClassifier) {
        if (targetClassifier == null) {
            return Map.of();
        }
        var paths = new LinkedHashMap<String, Path>();
        for (var entry : modules.entrySet()) {
            Artifact canonical = ArtifactCandidates.locationCoordinate(entry.getKey(), entry.getValue());
            Artifact artifact = resolveOptional(
                    consumerSession,
                    new DefaultArtifact(canonical.getGroupId(), canonical.getArtifactId(), targetClassifier, "jar",
                            entry.getValue()),
                    List.of(moduleRepository));
            if (artifact != null && artifact.getPath() != null) {
                paths.put(entry.getKey(), artifact.getPath());
            }
        }
        return Map.copyOf(paths);
    }

    private Artifact resolveOptional(RepositorySystemSession session, Artifact artifact, List<RemoteRepository> repositories) {
        try {
            return system.resolveArtifact(session, new ArtifactRequest(artifact, repositories, null)).getArtifact();
        } catch (ArtifactResolutionException e) {
            if (e.getResult() != null && e.getResult().isMissing()) {
                return null;
            }
            throw new FindException("Failed to resolve " + artifact, e);
        }
    }

    public String lookupModule(ModuleOrigin origin) {
        return switch (origin) {
            case MavenArtifactOrigin maven -> lookupMavenModule(maven);
        };
    }

    private String lookupMavenModule(MavenArtifactOrigin origin) {
        List<RemoteRepository> repositories = origin.repositoryUrl()
                .map(this::configuredRepository)
                .map(List::of)
                .orElse(backendRepositories);
        Artifact requested = new DefaultArtifact(origin.groupId(), origin.artifactId(), "jar",
                origin.version());
        if (origin.latest()) {
            try {
                var versions = system.resolveVersionRange(backendSession, new VersionRangeRequest(requested, repositories, null)).getVersions();
                if (versions.isEmpty()) {
                    throw new FindException("No versions found for Maven package " + origin);
                }
                requested = requested.setVersion(versions.getLast()
                        .toString());
            } catch (VersionRangeResolutionException e) {
                throw new FindException("Failed to find latest Maven package " + origin, e);
            }
        }
        try (var proxy = new ModuleProxyTransporter(system, backendSession, locationSession, repositories)) {
            String moduleName = proxy.lookupModuleName(requested);
            String version = requested.getVersion();
            Artifact located = proxy.locateModule(moduleName, version);
            if (!sameCoordinates(requested, located)) {
                throw new FindException("Canonical module "
                        + moduleName
                        + "@"
                        + version
                        + " maps to "
                        + located
                        + ", not "
                        + requested);
            }
            return moduleName;
        } catch (IOException | FindException e) {
            throw new FindException("Maven package " + origin.toString() + " is not locatable through the configured module repositories", e);
        }
    }

    private static boolean sameCoordinates(Artifact left, Artifact right) {
        return left.getGroupId().equals(right.getGroupId())
                && left.getArtifactId().equals(right.getArtifactId())
                && left.getVersion().equals(right.getVersion())
                && left.getExtension().equals(right.getExtension())
                && left.getClassifier().equals(right.getClassifier());
    }

    private RemoteRepository configuredRepository(URI requested) {
        return backendRepositories.stream()
                .filter(repository -> repositoryMatches(repository, requested))
                .findFirst()
                .orElseThrow(() -> new FindException("Repository " + requested + " is not configured in an active Maven settings profile"));
    }

    private static boolean repositoryMatches(RemoteRepository repository, URI requested) {
        if (sameRepository(repository.getUrl(), requested)) {
            return true;
        }
        return repository.getMirroredRepositories().stream()
                .anyMatch(mirrored -> sameRepository(mirrored.getUrl(), requested));
    }

    private static boolean sameRepository(String configured, URI requested) {
        String left = repositoryKey(URI.create(configured));
        String right = repositoryKey(requested);
        return left.equals(right);
    }

    private static String repositoryKey(URI repository) {
        String value = repository.normalize().toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public SequencedSet<String> listVersions(String moduleName) {
        var canonical = ArtifactCandidates.locationCoordinate(moduleName, "[0,)");
        var release = moduleRepository.getPolicy(false);
        var freshRepository = new Builder(moduleRepository).setReleasePolicy(new com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy(release.isEnabled(), com.netflix.tools.jig.internal.org.eclipse.aether.repository.RepositoryPolicy.UPDATE_POLICY_ALWAYS,
                release.getChecksumPolicy()))
                .build();
        try {
            var result = system.resolveVersionRange(consumerSession, new VersionRangeRequest(canonical, List.of(freshRepository), null));
            var versions = new LinkedHashSet<String>();
            result.getVersions().forEach(version -> versions.add(version.toString()));
            return Collections.unmodifiableSequencedSet(versions);
        } catch (VersionRangeResolutionException e) {
            throw new FindException("Failed to list versions for module " + moduleName, e);
        }
    }

    @Override
    public void close() {
        consumerSession.close();
        locationSession.close();
        backendSession.close();
        try {
            moduleCompiler.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to expire old compiled module outputs", e);
        }
    }

    private SessionBuilder newSessionBuilder(Path localRepository) {
        return new SessionBuilderSupplier(system)
                .get()
                .withLocalRepositoryBaseDirectories(localRepository)
                .setConfigProperty("aether.updateCheckManager.sessionState", "bypass")
                .setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false));
    }

    private static final ResolutionErrorPolicy MISSING_RESOURCE_CACHE_POLICY = new ResolutionErrorPolicy() {
        @Override
        public int getArtifactPolicy(RepositorySystemSession session, ResolutionErrorPolicyRequest<Artifact> request) {
            return CACHE_NOT_FOUND;
        }

        @Override
        public int getMetadataPolicy(RepositorySystemSession session, ResolutionErrorPolicyRequest<Metadata> request) {
            return CACHE_NOT_FOUND;
        }
    };

    private static RepositorySystem createRepositorySystem(ModuleLocationTransporterFactory locationFactory, BomLocationTransporterFactory bomLocationFactory, ModuleProxyTransporterFactory moduleProxyFactory) {
        return new RepositorySystemSupplier() {
            @Override
            protected Map<String, TransporterFactory> createTransporterFactories() {
                return Map.of(
                        "file",
                        new FileTransporterFactory(),
                        JdkTransporterFactory.NAME,
                        new JdkTransporterFactory(headers -> {
                            var checksums = new HashMap<String, String>();
                            for (var algorithm : List.of("sha1", "sha256", "sha512", "md5")) {
                                var value = headers.apply("x-checksum-" + algorithm);
                                if (value != null) {
                                    checksums.put(algorithm.toUpperCase().replace("SHA", "SHA-"), value);
                                }
                            }
                            return checksums.isEmpty() ? null : checksums;
                        }),
                        ModuleLocationTransporterFactory.NAME,
                        locationFactory,
                        BomLocationTransporterFactory.NAME,
                        bomLocationFactory,
                        ModuleProxyTransporterFactory.NAME,
                        moduleProxyFactory);
            }

            @Override
            protected Map<String, RemoteRepositoryFilterSource> createRemoteRepositoryFilterSources() {
                return Map.of(ModuleProbeRepositoryFilterSource.NAME, new ModuleProbeRepositoryFilterSource());
            }
        }.get();
    }

    public static SmartExecutor virtualThreadExecutor() {
        var backing = Executors.newVirtualThreadPerTaskExecutor();
        return new SmartExecutor() {
            @Override
            public void submit(Runnable runnable) {
                backing.submit(runnable);
            }

            @Override
            public <T> Future<T> submit(Callable<T> callable) {
                return backing.submit(callable);
            }

            @Override
            public void close() {
                backing.close();
            }
        };
    }

    private static void seedVirtualThreadExecutor(RepositorySystemSession session) {
        SmartExecutor executor = virtualThreadExecutor();
        SmartExecutor nonClosing = new SmartExecutor() {
            @Override
            public void submit(Runnable runnable) {
                executor.submit(runnable);
            }

            @Override
            public <T> Future<T> submit(Callable<T> callable) {
                return executor.submit(callable);
            }

            @Override
            public void close() {}
        };
        session.getData().computeIfAbsent("SmartExecutor-BfDependencyCollector-", () -> {
            session.addOnSessionEndedHandler(executor::close);
            return nonClosing;
        });
    }

    private static void configureSettings(SessionBuilder builder, Settings settings) {
        var mirrors = new DefaultMirrorSelector();
        for (var mirror : settings.getMirrors()) {
            mirrors.add(
                    mirror.getId(),
                    mirror.getUrl(),
                    mirror.getLayout() == null ? "default" : mirror.getLayout(),
                    false,
                    mirror.isBlocked(),
                    mirror.getMirrorOf(),
                    mirror.getMirrorOfLayouts());
        }
        builder.setMirrorSelector(mirrors);

        var authentications = new DefaultAuthenticationSelector();
        for (var server : settings.getServers()) {
            var authentication = new AuthenticationBuilder()
                    .addUsername(server.getUsername())
                    .addPassword(server.getPassword())
                    .addPrivateKey(server.getPrivateKey(), server.getPassphrase())
                    .build();
            authentications.add(server.getId(), authentication);
        }
        builder.setAuthenticationSelector(authentications);

        var proxies = new DefaultProxySelector();
        for (var proxy : settings.getProxies()) {
            if (!proxy.isActive()) {
                continue;
            }
            var authentication = new AuthenticationBuilder()
                    .addUsername(proxy.getUsername())
                    .addPassword(proxy.getPassword())
                    .build();
            proxies.add(
                    new Proxy(proxy.getProtocol(), proxy.getHost(), proxy.getPort(),
                            authentication),
                    proxy.getNonProxyHosts());
        }
        builder.setProxySelector(proxies);
    }

    private static List<RemoteRepository> repositories(Settings settings) {
        var repositories = new LinkedHashMap<String, RemoteRepository>();
        var central = centralRepository();
        repositories.put(central.getId(), central);

        Set<String> activeProfiles = new HashSet<>(settings.getActiveProfiles());
        for (var profile : settings.getProfiles()) {
            boolean active = activeProfiles.contains(profile.getId()) || (profile.getActivation() != null && profile.getActivation().isActiveByDefault());
            if (!active) {
                continue;
            }
            for (var repository : profile.getRepositories()) {
                var remote = new Builder(
                        repository.getId(),
                        repository.getLayout() == null ? "default" : repository.getLayout(),
                        repository.getUrl())
                        .setReleasePolicy(repositoryPolicy(repository.getReleases(), true))
                        .setSnapshotPolicy(repositoryPolicy(repository.getSnapshots(), false))
                        .build();
                repositories.put(remote.getId(), remote);
            }
        }
        return List.copyOf(repositories.values());
    }

    public static RepositoryPolicy repositoryPolicy(com.netflix.tools.jig.internal.org.apache.maven.settings.RepositoryPolicy policy, boolean enabledByDefault) {
        if (policy == null) {
            return new RepositoryPolicy(enabledByDefault, RepositoryPolicy.UPDATE_POLICY_DAILY, RepositoryPolicy.CHECKSUM_POLICY_WARN);
        }
        return new RepositoryPolicy(policy.isEnabled(), valueOrDefault(policy.getUpdatePolicy(), RepositoryPolicy.UPDATE_POLICY_DAILY),
                valueOrDefault(policy.getChecksumPolicy(), RepositoryPolicy.CHECKSUM_POLICY_WARN));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank()
                ? defaultValue
                : value;
    }

    public static Path distributionSettings(Path javaHome) {
        return javaHome.resolve("conf/com.netflix.tools.jig/settings.xml");
    }

    public static Settings readSettings(Path distributionSettings, Path userSettings) {
        boolean hasDistributionSettings = distributionSettings.toFile().isFile();
        boolean hasUserSettings = userSettings.toFile().isFile();
        if (!hasDistributionSettings && !hasUserSettings) {
            return new Settings();
        }
        var builder = new DefaultSettingsBuilder(new DefaultSettingsReader(), new DefaultSettingsWriter(), new DefaultSettingsValidator());
        var request = new DefaultSettingsBuildingRequest();
        if (hasDistributionSettings) {
            request.setGlobalSettingsFile(distributionSettings.toFile());
        }
        if (hasUserSettings) {
            request.setUserSettingsFile(userSettings.toFile());
        }
        request.setSystemProperties(System.getProperties());
        try {
            Settings settings = builder.build(request).getEffectiveSettings();
            String configuredSecurity = System.getProperty("settings.security");
            Path securitySettings = configuredSecurity == null || configuredSecurity.isBlank()
                    ? userSettings.resolveSibling("settings-security.xml")
                    : Path.of(configuredSecurity);
            MavenSettingsDecrypter.decrypt(settings, securitySettings);
            return settings;
        } catch (SettingsBuildingException e) {
            throw new IllegalStateException("Failed to read Maven settings from " + distributionSettings + " and " + userSettings, e);
        }
    }

    private static RemoteRepository virtualRepository(String id, String url) {
        return new Builder(id, "default", url)
                .setReleasePolicy(RELEASE_POLICY)
                .setSnapshotPolicy(SNAPSHOT_POLICY)
                .build();
    }

    public static RemoteRepository centralRepository() {
        return new Builder("central", "default", "https://repo.maven.apache.org/maven2/")
                .setReleasePolicy(RELEASE_POLICY)
                .setSnapshotPolicy(SNAPSHOT_POLICY)
                .build();
    }

    static final RepositoryPolicy RELEASE_POLICY = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL);
    static final RepositoryPolicy SNAPSHOT_POLICY = new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL);
}
