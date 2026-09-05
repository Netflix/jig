/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.netflix.tools.jig.internal.org.eclipse.aether.supplier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.netflix.tools.jig.internal.org.apache.maven.model.building.DefaultModelBuilderFactory;
import com.netflix.tools.jig.internal.org.apache.maven.model.building.ModelBuilder;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.DefaultArtifactDescriptorReader;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.DefaultModelCacheFactory;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.DefaultVersionRangeResolver;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.DefaultVersionResolver;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.MavenArtifactRelocationSource;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.ModelCacheFactory;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.PluginsMetadataGeneratorFactory;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.SnapshotMetadataGeneratorFactory;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.VersionsMetadataGeneratorFactory;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.relocation.DistributionManagementArtifactRelocationSource;
import com.netflix.tools.jig.internal.org.apache.maven.repository.internal.relocation.UserPropertiesArtifactRelocationSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositoryListener;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.ArtifactDescriptorReader;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.ArtifactResolver;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.DependencyCollector;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.Deployer;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.Installer;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.LocalRepositoryProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.MetadataGeneratorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.MetadataResolver;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.NamedLockFactorySelector;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.OfflineController;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.RemoteRepositoryFilterManager;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.RemoteRepositoryManager;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.RepositoryConnectorProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.RepositoryEventDispatcher;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.RepositorySystemLifecycle;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.RepositorySystemValidator;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.UpdateCheckManager;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.UpdatePolicyAnalyzer;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.VersionRangeResolver;
import com.netflix.tools.jig.internal.org.eclipse.aether.impl.VersionResolver;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultArtifactPredicateFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultArtifactResolver;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultChecksumPolicyProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultChecksumProcessor;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultDeployer;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultInstaller;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultLocalPathComposer;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultLocalPathPrefixComposerFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultLocalRepositoryProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultMetadataResolver;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultOfflineController;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultPathProcessor;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRemoteRepositoryManager;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRepositoryConnectorProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRepositoryEventDispatcher;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRepositoryKeyFunctionFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRepositoryLayoutProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRepositorySystem;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRepositorySystemLifecycle;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultRepositorySystemValidator;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultTransporterProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultUpdateCheckManager;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.DefaultUpdatePolicyAnalyzer;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.EnhancedLocalRepositoryManagerFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.LocalPathComposer;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.LocalPathPrefixComposerFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.TrackingFileManager;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.TrackingFileManagerSupplier;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.DefaultChecksumAlgorithmFactorySelector;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.Md5ChecksumAlgorithmFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.Sha1ChecksumAlgorithmFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.Sha256ChecksumAlgorithmFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.Sha512ChecksumAlgorithmFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.SparseDirectoryTrustedChecksumsSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.SummaryFileTrustedChecksumsSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.checksum.TrustedToProvidedChecksumsSourceAdapter;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.collect.DefaultDependencyCollector;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.collect.DependencyCollectorDelegate;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.collect.bf.BfDependencyCollector;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.collect.df.DfDependencyCollector;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.filter.DefaultRemoteRepositoryFilterManager;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.filter.FilteringPipelineRepositoryConnectorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.filter.GroupIdRemoteRepositoryFilterSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.filter.PrefixesLockingInhibitorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.filter.PrefixesRemoteRepositoryFilterSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.named.DefaultNamedLockFactorySelector;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.offline.OfflinePipelineRepositoryConnectorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.resolution.TrustedChecksumsArtifactResolverPostProcessor;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.DefaultSyncContextFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.named.NameMapper;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.named.NameMappers;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.named.NamedLockFactoryAdapterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.synccontext.named.NamedLockFactoryAdapterFactoryImpl;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.transport.http.DefaultChecksumExtractor;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.transport.http.Nx2ChecksumExtractor;
import com.netflix.tools.jig.internal.org.eclipse.aether.internal.impl.transport.http.XChecksumExtractor;
import com.netflix.tools.jig.internal.org.eclipse.aether.named.NamedLockFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.named.providers.FileLockNamedLockFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.named.providers.LocalReadWriteLockNamedLockFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.named.providers.LocalSemaphoreNamedLockFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.named.providers.NoopNamedLockFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.artifact.ArtifactPredicateFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.artifact.decorator.ArtifactDecoratorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.artifact.generator.ArtifactGeneratorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.artifact.transformer.ArtifactTransformer;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.checksums.ProvidedChecksumsSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.checksums.TrustedChecksumsSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.PipelineRepositoryConnectorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactorySelector;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.checksum.ChecksumPolicyProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilterSource;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.layout.RepositoryLayoutFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.TransporterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.TransporterProvider;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.http.ChecksumExtractor;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.http.ChecksumExtractorStrategy;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.io.ChecksumProcessor;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.io.PathProcessor;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.localrepo.LocalRepositoryManagerFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.locking.LockingInhibitorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.remoterepo.RepositoryKeyFunctionFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.resolution.ArtifactResolverPostProcessor;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.synccontext.SyncContextFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.validator.ValidatorFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.transport.file.FileTransporterFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.version.GenericVersionScheme;
import com.netflix.tools.jig.internal.org.eclipse.aether.version.VersionScheme;

/**
 * A simple memorizing {@link Supplier} of {@link RepositorySystem} instance, that on first call
 * supplies lazily constructed instance, and on each subsequent call same instance. Hence, this instance should be
 * thrown away immediately once repository system was created and there is no need for more instances. If new
 * repository system instance needed, new instance of this class must be created. For proper shut down of returned
 * repository system instance(s) use {@link RepositorySystem#shutdown()} method on supplied instance(s).
 * <p>
 * Since Resolver 2.0 this class offers access to various components via public getters, and allows even partial object
 * graph construction.
 * <p>
 * Extend this class {@code createXXX()} methods and override to customize, if needed. The contract of this class makes
 * sure that these (potentially overridden) methods are invoked only once, and instance created by those methods are
 * memorized and kept as long as supplier instance is kept open.
 * <p>
 * This class is not thread safe and must be used from one thread only, while the constructed {@link RepositorySystem}
 * is thread safe.
 * <p>
 * Important: Given the instance of supplier memorizes the supplier {@link RepositorySystem} instance it supplies,
 * their lifecycle is shared as well: once supplied repository system is shut-down, this instance becomes closed as
 * well. Any subsequent {@code getXXX} method invocation attempt will fail with {@link IllegalStateException}.
 *
 * @since 2.0.0
 */
public class RepositorySystemSupplier implements Supplier<RepositorySystem> {
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RepositorySystemSupplier() {}

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Supplier is closed");
        }
    }

    private PathProcessor pathProcessor;

    public final PathProcessor getPathProcessor() {
        checkClosed();
        if (pathProcessor == null) {
            pathProcessor = createPathProcessor();
        }
        return pathProcessor;
    }

    protected PathProcessor createPathProcessor() {
        return new DefaultPathProcessor();
    }

    private ChecksumProcessor checksumProcessor;

    public final ChecksumProcessor getChecksumProcessor() {
        checkClosed();
        if (checksumProcessor == null) {
            checksumProcessor = createChecksumProcessor();
        }
        return checksumProcessor;
    }

    protected ChecksumProcessor createChecksumProcessor() {
        return new DefaultChecksumProcessor(getPathProcessor());
    }

    private TrackingFileManager trackingFileManager;

    public final TrackingFileManager getTrackingFileManager() {
        checkClosed();
        if (trackingFileManager == null) {
            trackingFileManager = createTrackingFileManager();
        }
        return trackingFileManager;
    }

    protected TrackingFileManager createTrackingFileManager() {
        return new TrackingFileManagerSupplier(getNamedLockFactorySelector()).get();
    }

    private LocalPathComposer localPathComposer;

    public final LocalPathComposer getLocalPathComposer() {
        checkClosed();
        if (localPathComposer == null) {
            localPathComposer = createLocalPathComposer();
        }
        return localPathComposer;
    }

    protected LocalPathComposer createLocalPathComposer() {
        return new DefaultLocalPathComposer();
    }

    private LocalPathPrefixComposerFactory localPathPrefixComposerFactory;

    public final LocalPathPrefixComposerFactory getLocalPathPrefixComposerFactory() {
        checkClosed();
        if (localPathPrefixComposerFactory == null) {
            localPathPrefixComposerFactory = createLocalPathPrefixComposerFactory();
        }
        return localPathPrefixComposerFactory;
    }

    protected LocalPathPrefixComposerFactory createLocalPathPrefixComposerFactory() {
        return new DefaultLocalPathPrefixComposerFactory(getRepositoryKeyFunctionFactory());
    }

    private RepositorySystemLifecycle repositorySystemLifecycle;

    public final RepositorySystemLifecycle getRepositorySystemLifecycle() {
        checkClosed();
        if (repositorySystemLifecycle == null) {
            repositorySystemLifecycle = createRepositorySystemLifecycle();
            repositorySystemLifecycle.addOnSystemEndedHandler(() -> closed.set(true));
        }
        return repositorySystemLifecycle;
    }

    protected RepositorySystemLifecycle createRepositorySystemLifecycle() {
        return new DefaultRepositorySystemLifecycle();
    }

    private OfflineController offlineController;

    public final OfflineController getOfflineController() {
        checkClosed();
        if (offlineController == null) {
            offlineController = createOfflineController();
        }
        return offlineController;
    }

    protected OfflineController createOfflineController() {
        return new DefaultOfflineController();
    }

    private UpdatePolicyAnalyzer updatePolicyAnalyzer;

    public final UpdatePolicyAnalyzer getUpdatePolicyAnalyzer() {
        checkClosed();
        if (updatePolicyAnalyzer == null) {
            updatePolicyAnalyzer = createUpdatePolicyAnalyzer();
        }
        return updatePolicyAnalyzer;
    }

    protected UpdatePolicyAnalyzer createUpdatePolicyAnalyzer() {
        return new DefaultUpdatePolicyAnalyzer();
    }

    private ChecksumPolicyProvider checksumPolicyProvider;

    public final ChecksumPolicyProvider getChecksumPolicyProvider() {
        checkClosed();
        if (checksumPolicyProvider == null) {
            checksumPolicyProvider = createChecksumPolicyProvider();
        }
        return checksumPolicyProvider;
    }

    protected ChecksumPolicyProvider createChecksumPolicyProvider() {
        return new DefaultChecksumPolicyProvider();
    }

    private UpdateCheckManager updateCheckManager;

    public final UpdateCheckManager getUpdateCheckManager() {
        checkClosed();
        if (updateCheckManager == null) {
            updateCheckManager = createUpdateCheckManager();
        }
        return updateCheckManager;
    }

    protected UpdateCheckManager createUpdateCheckManager() {
        return new DefaultUpdateCheckManager(getTrackingFileManager(), getUpdatePolicyAnalyzer(), getPathProcessor());
    }

    private RepositoryKeyFunctionFactory repositoriesKeyFunctionFactory;

    public final RepositoryKeyFunctionFactory getRepositoryKeyFunctionFactory() {
        checkClosed();
        if (repositoriesKeyFunctionFactory == null) {
            repositoriesKeyFunctionFactory = createRepositoryKeyFunctionFactory();
        }
        return repositoriesKeyFunctionFactory;
    }

    protected RepositoryKeyFunctionFactory createRepositoryKeyFunctionFactory() {
        return new DefaultRepositoryKeyFunctionFactory();
    }

    private Map<String, NamedLockFactory> namedLockFactories;

    public final Map<String, NamedLockFactory> getNamedLockFactories() {
        checkClosed();
        if (namedLockFactories == null) {
            namedLockFactories = createNamedLockFactories();
        }
        return namedLockFactories;
    }

    protected Map<String, NamedLockFactory> createNamedLockFactories() {
        HashMap<String, NamedLockFactory> result = new HashMap<>();
        result.put(NoopNamedLockFactory.NAME, new NoopNamedLockFactory());
        result.put(LocalReadWriteLockNamedLockFactory.NAME, new LocalReadWriteLockNamedLockFactory());
        result.put(LocalSemaphoreNamedLockFactory.NAME, new LocalSemaphoreNamedLockFactory());
        result.put(FileLockNamedLockFactory.NAME, new FileLockNamedLockFactory());
        return result;
    }

    private Map<String, NameMapper> nameMappers;

    public final Map<String, NameMapper> getNameMappers() {
        checkClosed();
        if (nameMappers == null) {
            nameMappers = createNameMappers();
        }
        return nameMappers;
    }

    protected Map<String, NameMapper> createNameMappers() {
        HashMap<String, NameMapper> result = new HashMap<>();
        result.put(NameMappers.STATIC_NAME, NameMappers.staticNameMapper());
        result.put(NameMappers.GAV_NAME, NameMappers.gavNameMapper());
        result.put(NameMappers.GAECV_NAME, NameMappers.gaecvNameMapper());
        result.put(NameMappers.DISCRIMINATING_NAME, NameMappers.discriminatingNameMapper());
        result.put(NameMappers.FILE_GAV_NAME, NameMappers.fileGavNameMapper());
        result.put(NameMappers.FILE_GAECV_NAME, NameMappers.fileGaecvNameMapper());
        result.put(NameMappers.FILE_HGAV_NAME, NameMappers.fileHashingGavNameMapper());
        result.put(NameMappers.FILE_HGAECV_NAME, NameMappers.fileHashingGaecvNameMapper());
        return result;
    }

    private Map<String, LockingInhibitorFactory> lockingInhibitorFactories;

    public final Map<String, LockingInhibitorFactory> getLockingInhibitorFactories() {
        checkClosed();
        if (lockingInhibitorFactories == null) {
            lockingInhibitorFactories = createLockingInhibitorFactories();
        }
        return lockingInhibitorFactories;
    }

    protected Map<String, LockingInhibitorFactory> createLockingInhibitorFactories() {
        HashMap<String, LockingInhibitorFactory> result = new HashMap<>();
        result.put(PrefixesLockingInhibitorFactory.NAME, new PrefixesLockingInhibitorFactory());
        return result;
    }

    private NamedLockFactorySelector namedLockFactorySelector;

    public final NamedLockFactorySelector getNamedLockFactorySelector() {
        checkClosed();
        if (namedLockFactorySelector == null) {
            namedLockFactorySelector = createNamedLockFactorySelector();
        }
        return namedLockFactorySelector;
    }

    protected NamedLockFactorySelector createNamedLockFactorySelector() {
        return new DefaultNamedLockFactorySelector(getNamedLockFactories(), getRepositorySystemLifecycle());
    }

    private NamedLockFactoryAdapterFactory namedLockFactoryAdapterFactory;

    public final NamedLockFactoryAdapterFactory getNamedLockFactoryAdapterFactory() {
        checkClosed();
        if (namedLockFactoryAdapterFactory == null) {
            namedLockFactoryAdapterFactory = createNamedLockFactoryAdapterFactory();
        }
        return namedLockFactoryAdapterFactory;
    }

    protected NamedLockFactoryAdapterFactory createNamedLockFactoryAdapterFactory() {
        return new NamedLockFactoryAdapterFactoryImpl(
                getNamedLockFactorySelector(), getNameMappers(), getLockingInhibitorFactories());
    }

    private SyncContextFactory syncContextFactory;

    public final SyncContextFactory getSyncContextFactory() {
        checkClosed();
        if (syncContextFactory == null) {
            syncContextFactory = createSyncContextFactory();
        }
        return syncContextFactory;
    }

    protected SyncContextFactory createSyncContextFactory() {
        return new DefaultSyncContextFactory(getNamedLockFactoryAdapterFactory());
    }

    private Map<String, ChecksumAlgorithmFactory> checksumAlgorithmFactories;

    public final Map<String, ChecksumAlgorithmFactory> getChecksumAlgorithmFactories() {
        checkClosed();
        if (checksumAlgorithmFactories == null) {
            checksumAlgorithmFactories = createChecksumAlgorithmFactories();
        }
        return checksumAlgorithmFactories;
    }

    protected Map<String, ChecksumAlgorithmFactory> createChecksumAlgorithmFactories() {
        HashMap<String, ChecksumAlgorithmFactory> result = new HashMap<>();
        result.put(Sha512ChecksumAlgorithmFactory.NAME, new Sha512ChecksumAlgorithmFactory());
        result.put(Sha256ChecksumAlgorithmFactory.NAME, new Sha256ChecksumAlgorithmFactory());
        result.put(Sha1ChecksumAlgorithmFactory.NAME, new Sha1ChecksumAlgorithmFactory());
        result.put(Md5ChecksumAlgorithmFactory.NAME, new Md5ChecksumAlgorithmFactory());
        return result;
    }

    private ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector;

    public final ChecksumAlgorithmFactorySelector getChecksumAlgorithmFactorySelector() {
        checkClosed();
        if (checksumAlgorithmFactorySelector == null) {
            checksumAlgorithmFactorySelector = createChecksumAlgorithmFactorySelector();
        }
        return checksumAlgorithmFactorySelector;
    }

    protected ChecksumAlgorithmFactorySelector createChecksumAlgorithmFactorySelector() {
        return new DefaultChecksumAlgorithmFactorySelector(getChecksumAlgorithmFactories());
    }

    private ArtifactPredicateFactory artifactPredicateFactory;

    public final ArtifactPredicateFactory getArtifactPredicateFactory() {
        checkClosed();
        if (artifactPredicateFactory == null) {
            artifactPredicateFactory = createArtifactPredicateFactory();
        }
        return artifactPredicateFactory;
    }

    protected ArtifactPredicateFactory createArtifactPredicateFactory() {
        return new DefaultArtifactPredicateFactory(getChecksumAlgorithmFactorySelector());
    }

    private Map<String, RepositoryLayoutFactory> repositoryLayoutFactories;

    public final Map<String, RepositoryLayoutFactory> getRepositoryLayoutFactories() {
        checkClosed();
        if (repositoryLayoutFactories == null) {
            repositoryLayoutFactories = createRepositoryLayoutFactories();
        }
        return repositoryLayoutFactories;
    }

    protected Map<String, RepositoryLayoutFactory> createRepositoryLayoutFactories() {
        HashMap<String, RepositoryLayoutFactory> result = new HashMap<>();
        result.put(
                Maven2RepositoryLayoutFactory.NAME,
                new Maven2RepositoryLayoutFactory(
                        getChecksumAlgorithmFactorySelector(), getArtifactPredicateFactory()));
        return result;
    }

    private RepositoryLayoutProvider repositoryLayoutProvider;

    public final RepositoryLayoutProvider getRepositoryLayoutProvider() {
        checkClosed();
        if (repositoryLayoutProvider == null) {
            repositoryLayoutProvider = createRepositoryLayoutProvider();
        }
        return repositoryLayoutProvider;
    }

    protected RepositoryLayoutProvider createRepositoryLayoutProvider() {
        return new DefaultRepositoryLayoutProvider(getRepositoryLayoutFactories());
    }

    private LocalRepositoryProvider localRepositoryProvider;

    public final LocalRepositoryProvider getLocalRepositoryProvider() {
        checkClosed();
        if (localRepositoryProvider == null) {
            localRepositoryProvider = createLocalRepositoryProvider();
        }
        return localRepositoryProvider;
    }

    protected LocalRepositoryProvider createLocalRepositoryProvider() {
        LocalPathComposer localPathComposer = getLocalPathComposer();
        RepositoryKeyFunctionFactory repositoryKeyFunctionFactory = getRepositoryKeyFunctionFactory();
        HashMap<String, LocalRepositoryManagerFactory> localRepositoryProviders = new HashMap<>(2);
        localRepositoryProviders.put(
                SimpleLocalRepositoryManagerFactory.NAME,
                new SimpleLocalRepositoryManagerFactory(localPathComposer, repositoryKeyFunctionFactory));
        localRepositoryProviders.put(
                EnhancedLocalRepositoryManagerFactory.NAME,
                new EnhancedLocalRepositoryManagerFactory(
                        localPathComposer,
                        getTrackingFileManager(),
                        getLocalPathPrefixComposerFactory(),
                        repositoryKeyFunctionFactory));
        return new DefaultLocalRepositoryProvider(localRepositoryProviders);
    }

    private RemoteRepositoryManager remoteRepositoryManager;

    public final RemoteRepositoryManager getRemoteRepositoryManager() {
        checkClosed();
        if (remoteRepositoryManager == null) {
            remoteRepositoryManager = createRemoteRepositoryManager();
        }
        return remoteRepositoryManager;
    }

    protected RemoteRepositoryManager createRemoteRepositoryManager() {
        return new DefaultRemoteRepositoryManager(
                getUpdatePolicyAnalyzer(), getChecksumPolicyProvider(), getRepositoryKeyFunctionFactory());
    }

    private Map<String, RemoteRepositoryFilterSource> remoteRepositoryFilterSources;

    public final Map<String, RemoteRepositoryFilterSource> getRemoteRepositoryFilterSources() {
        checkClosed();
        if (remoteRepositoryFilterSources == null) {
            remoteRepositoryFilterSources = createRemoteRepositoryFilterSources();
        }
        return remoteRepositoryFilterSources;
    }

    protected Map<String, RemoteRepositoryFilterSource> createRemoteRepositoryFilterSources() {
        HashMap<String, RemoteRepositoryFilterSource> result = new HashMap<>();
        result.put(
                GroupIdRemoteRepositoryFilterSource.NAME,
                new GroupIdRemoteRepositoryFilterSource(
                        getRepositoryKeyFunctionFactory(), getRepositorySystemLifecycle(), getPathProcessor()));
        result.put(
                PrefixesRemoteRepositoryFilterSource.NAME,
                new PrefixesRemoteRepositoryFilterSource(
                        getRepositoryKeyFunctionFactory(),
                        this::getMetadataResolver,
                        this::getRemoteRepositoryManager,
                        getRepositoryLayoutProvider()));
        return result;
    }

    private RemoteRepositoryFilterManager remoteRepositoryFilterManager;

    public final RemoteRepositoryFilterManager getRemoteRepositoryFilterManager() {
        checkClosed();
        if (remoteRepositoryFilterManager == null) {
            remoteRepositoryFilterManager = createRemoteRepositoryFilterManager();
        }
        return remoteRepositoryFilterManager;
    }

    protected RemoteRepositoryFilterManager createRemoteRepositoryFilterManager() {
        return new DefaultRemoteRepositoryFilterManager(getRemoteRepositoryFilterSources());
    }

    private Map<String, RepositoryListener> repositoryListeners;

    public final Map<String, RepositoryListener> getRepositoryListeners() {
        checkClosed();
        if (repositoryListeners == null) {
            repositoryListeners = createRepositoryListeners();
        }
        return repositoryListeners;
    }

    protected Map<String, RepositoryListener> createRepositoryListeners() {
        return new HashMap<>();
    }

    private RepositoryEventDispatcher repositoryEventDispatcher;

    public final RepositoryEventDispatcher getRepositoryEventDispatcher() {
        checkClosed();
        if (repositoryEventDispatcher == null) {
            repositoryEventDispatcher = createRepositoryEventDispatcher();
        }
        return repositoryEventDispatcher;
    }

    protected RepositoryEventDispatcher createRepositoryEventDispatcher() {
        return new DefaultRepositoryEventDispatcher(getRepositoryListeners());
    }

    private Map<String, TrustedChecksumsSource> trustedChecksumsSources;

    public final Map<String, TrustedChecksumsSource> getTrustedChecksumsSources() {
        checkClosed();
        if (trustedChecksumsSources == null) {
            trustedChecksumsSources = createTrustedChecksumsSources();
        }
        return trustedChecksumsSources;
    }

    protected Map<String, TrustedChecksumsSource> createTrustedChecksumsSources() {
        HashMap<String, TrustedChecksumsSource> result = new HashMap<>();
        result.put(
                SparseDirectoryTrustedChecksumsSource.NAME,
                new SparseDirectoryTrustedChecksumsSource(
                        getRepositoryKeyFunctionFactory(), getChecksumProcessor(), getLocalPathComposer()));
        result.put(
                SummaryFileTrustedChecksumsSource.NAME,
                new SummaryFileTrustedChecksumsSource(
                        getRepositoryKeyFunctionFactory(),
                        getLocalPathComposer(),
                        getRepositorySystemLifecycle(),
                        getPathProcessor()));
        return result;
    }

    private Map<String, ProvidedChecksumsSource> providedChecksumsSources;

    public final Map<String, ProvidedChecksumsSource> getProvidedChecksumsSources() {
        checkClosed();
        if (providedChecksumsSources == null) {
            providedChecksumsSources = createProvidedChecksumsSources();
        }
        return providedChecksumsSources;
    }

    protected Map<String, ProvidedChecksumsSource> createProvidedChecksumsSources() {
        HashMap<String, ProvidedChecksumsSource> result = new HashMap<>();
        result.put(
                TrustedToProvidedChecksumsSourceAdapter.NAME,
                new TrustedToProvidedChecksumsSourceAdapter(getTrustedChecksumsSources()));
        return result;
    }

    private Map<String, ChecksumExtractorStrategy> checksumExtractorStrategies;

    public final Map<String, ChecksumExtractorStrategy> getChecksumExtractorStrategies() {
        checkClosed();
        if (checksumExtractorStrategies == null) {
            checksumExtractorStrategies = createChecksumExtractorStrategies();
        }
        return checksumExtractorStrategies;
    }

    protected Map<String, ChecksumExtractorStrategy> createChecksumExtractorStrategies() {
        HashMap<String, ChecksumExtractorStrategy> result = new HashMap<>();
        result.put(XChecksumExtractor.NAME, new XChecksumExtractor());
        result.put(Nx2ChecksumExtractor.NAME, new Nx2ChecksumExtractor());
        return result;
    }

    private ChecksumExtractor checksumExtractor;

    public final ChecksumExtractor getChecksumExtractor() {
        checkClosed();
        if (checksumExtractor == null) {
            checksumExtractor = createChecksumExtractor();
        }
        return checksumExtractor;
    }

    protected ChecksumExtractor createChecksumExtractor() {
        return new DefaultChecksumExtractor(getChecksumExtractorStrategies());
    }

    private Map<String, TransporterFactory> transporterFactories;

    public final Map<String, TransporterFactory> getTransporterFactories() {
        checkClosed();
        if (transporterFactories == null) {
            transporterFactories = createTransporterFactories();
        }
        return transporterFactories;
    }

    protected Map<String, TransporterFactory> createTransporterFactories() {
        HashMap<String, TransporterFactory> result = new HashMap<>();
        result.put(FileTransporterFactory.NAME, new FileTransporterFactory());
        return result;
    }

    private TransporterProvider transporterProvider;

    public final TransporterProvider getTransporterProvider() {
        checkClosed();
        if (transporterProvider == null) {
            transporterProvider = createTransporterProvider();
        }
        return transporterProvider;
    }

    protected TransporterProvider createTransporterProvider() {
        return new DefaultTransporterProvider(getTransporterFactories());
    }

    private BasicRepositoryConnectorFactory basicRepositoryConnectorFactory;

    public final BasicRepositoryConnectorFactory getBasicRepositoryConnectorFactory() {
        checkClosed();
        if (basicRepositoryConnectorFactory == null) {
            basicRepositoryConnectorFactory = createBasicRepositoryConnectorFactory();
        }
        return basicRepositoryConnectorFactory;
    }

    protected BasicRepositoryConnectorFactory createBasicRepositoryConnectorFactory() {
        return new BasicRepositoryConnectorFactory(
                getTransporterProvider(),
                getRepositoryLayoutProvider(),
                getChecksumPolicyProvider(),
                getPathProcessor(),
                getChecksumProcessor(),
                getProvidedChecksumsSources());
    }

    private Map<String, RepositoryConnectorFactory> repositoryConnectorFactories;

    public final Map<String, RepositoryConnectorFactory> getRepositoryConnectorFactories() {
        checkClosed();
        if (repositoryConnectorFactories == null) {
            repositoryConnectorFactories = createRepositoryConnectorFactories();
        }
        return repositoryConnectorFactories;
    }

    protected Map<String, RepositoryConnectorFactory> createRepositoryConnectorFactories() {
        HashMap<String, RepositoryConnectorFactory> result = new HashMap<>();
        result.put(BasicRepositoryConnectorFactory.NAME, getBasicRepositoryConnectorFactory());
        return result;
    }

    private Map<String, PipelineRepositoryConnectorFactory> pipelineRepositoryConnectorFactories;

    public final Map<String, PipelineRepositoryConnectorFactory> getPipelineRepositoryConnectorFactories() {
        checkClosed();
        if (pipelineRepositoryConnectorFactories == null) {
            pipelineRepositoryConnectorFactories = createPipelineRepositoryConnectorFactories();
        }
        return pipelineRepositoryConnectorFactories;
    }

    protected Map<String, PipelineRepositoryConnectorFactory> createPipelineRepositoryConnectorFactories() {
        HashMap<String, PipelineRepositoryConnectorFactory> result = new HashMap<>();
        result.put(
                FilteringPipelineRepositoryConnectorFactory.NAME,
                new FilteringPipelineRepositoryConnectorFactory(getRemoteRepositoryFilterManager()));
        result.put(
                OfflinePipelineRepositoryConnectorFactory.NAME,
                new OfflinePipelineRepositoryConnectorFactory(getOfflineController()));
        return result;
    }

    private RepositoryConnectorProvider repositoryConnectorProvider;

    public final RepositoryConnectorProvider getRepositoryConnectorProvider() {
        checkClosed();
        if (repositoryConnectorProvider == null) {
            repositoryConnectorProvider = createRepositoryConnectorProvider();
        }
        return repositoryConnectorProvider;
    }

    protected RepositoryConnectorProvider createRepositoryConnectorProvider() {
        return new DefaultRepositoryConnectorProvider(
                getRepositoryConnectorFactories(), getPipelineRepositoryConnectorFactories());
    }

    private Installer installer;

    public final Installer getInstaller() {
        checkClosed();
        if (installer == null) {
            installer = createInstaller();
        }
        return installer;
    }

    protected Installer createInstaller() {
        return new DefaultInstaller(
                getPathProcessor(),
                getRepositoryEventDispatcher(),
                getArtifactGeneratorFactories(),
                getMetadataGeneratorFactories(),
                getArtifactTransformers(),
                getSyncContextFactory());
    }

    private Deployer deployer;

    public final Deployer getDeployer() {
        checkClosed();
        if (deployer == null) {
            deployer = createDeployer();
        }
        return deployer;
    }

    protected Deployer createDeployer() {
        return new DefaultDeployer(
                getPathProcessor(),
                getRepositoryEventDispatcher(),
                getRepositoryConnectorProvider(),
                getRemoteRepositoryManager(),
                getUpdateCheckManager(),
                getArtifactGeneratorFactories(),
                getMetadataGeneratorFactories(),
                getArtifactTransformers(),
                getSyncContextFactory(),
                getOfflineController());
    }

    private Map<String, DependencyCollectorDelegate> dependencyCollectorDelegates;

    public final Map<String, DependencyCollectorDelegate> getDependencyCollectorDelegates() {
        checkClosed();
        if (dependencyCollectorDelegates == null) {
            dependencyCollectorDelegates = createDependencyCollectorDelegates();
        }
        return dependencyCollectorDelegates;
    }

    protected Map<String, DependencyCollectorDelegate> createDependencyCollectorDelegates() {
        RemoteRepositoryManager remoteRepositoryManager = getRemoteRepositoryManager();
        ArtifactDescriptorReader artifactDescriptorReader = getArtifactDescriptorReader();
        VersionRangeResolver versionRangeResolver = getVersionRangeResolver();
        HashMap<String, DependencyCollectorDelegate> result = new HashMap<>();
        result.put(
                DfDependencyCollector.NAME,
                new DfDependencyCollector(
                        remoteRepositoryManager,
                        artifactDescriptorReader,
                        versionRangeResolver,
                        getArtifactDecoratorFactories()));
        result.put(
                BfDependencyCollector.NAME,
                new BfDependencyCollector(
                        remoteRepositoryManager,
                        artifactDescriptorReader,
                        versionRangeResolver,
                        getArtifactDecoratorFactories()));
        return result;
    }

    private DependencyCollector dependencyCollector;

    public final DependencyCollector getDependencyCollector() {
        checkClosed();
        if (dependencyCollector == null) {
            dependencyCollector = createDependencyCollector();
        }
        return dependencyCollector;
    }

    protected DependencyCollector createDependencyCollector() {
        return new DefaultDependencyCollector(getDependencyCollectorDelegates());
    }

    private Map<String, ArtifactResolverPostProcessor> artifactResolverPostProcessors;

    public final Map<String, ArtifactResolverPostProcessor> getArtifactResolverPostProcessors() {
        checkClosed();
        if (artifactResolverPostProcessors == null) {
            artifactResolverPostProcessors = createArtifactResolverPostProcessors();
        }
        return artifactResolverPostProcessors;
    }

    protected Map<String, ArtifactResolverPostProcessor> createArtifactResolverPostProcessors() {
        HashMap<String, ArtifactResolverPostProcessor> result = new HashMap<>();
        result.put(
                TrustedChecksumsArtifactResolverPostProcessor.NAME,
                new TrustedChecksumsArtifactResolverPostProcessor(
                        getChecksumAlgorithmFactorySelector(), getTrustedChecksumsSources()));
        return result;
    }

    private ArtifactResolver artifactResolver;

    public final ArtifactResolver getArtifactResolver() {
        checkClosed();
        if (artifactResolver == null) {
            artifactResolver = createArtifactResolver();
        }
        return artifactResolver;
    }

    protected ArtifactResolver createArtifactResolver() {
        return new DefaultArtifactResolver(
                getPathProcessor(),
                getRepositoryEventDispatcher(),
                getVersionResolver(),
                getUpdateCheckManager(),
                getRepositoryConnectorProvider(),
                getRemoteRepositoryManager(),
                getSyncContextFactory(),
                getOfflineController(),
                getArtifactResolverPostProcessors(),
                getRemoteRepositoryFilterManager());
    }

    private MetadataResolver metadataResolver;

    public final MetadataResolver getMetadataResolver() {
        checkClosed();
        if (metadataResolver == null) {
            metadataResolver = createMetadataResolver();
        }
        return metadataResolver;
    }

    protected MetadataResolver createMetadataResolver() {
        return new DefaultMetadataResolver(
                getRepositoryEventDispatcher(),
                getUpdateCheckManager(),
                getRepositoryConnectorProvider(),
                getRemoteRepositoryManager(),
                getSyncContextFactory(),
                getOfflineController(),
                getRemoteRepositoryFilterManager(),
                getPathProcessor());
    }

    private VersionScheme versionScheme;

    public final VersionScheme getVersionScheme() {
        checkClosed();
        if (versionScheme == null) {
            versionScheme = createVersionScheme();
        }
        return versionScheme;
    }

    protected VersionScheme createVersionScheme() {
        return new GenericVersionScheme();
    }

    private Map<String, ArtifactGeneratorFactory> artifactGeneratorFactories;

    public final Map<String, ArtifactGeneratorFactory> getArtifactGeneratorFactories() {
        checkClosed();
        if (artifactGeneratorFactories == null) {
            artifactGeneratorFactories = createArtifactGeneratorFactories();
        }
        return artifactGeneratorFactories;
    }

    protected Map<String, ArtifactGeneratorFactory> createArtifactGeneratorFactories() {
        // by default none, this is extension point
        return new HashMap<>();
    }

    private Map<String, ArtifactDecoratorFactory> artifactDecoratorFactories;

    public final Map<String, ArtifactDecoratorFactory> getArtifactDecoratorFactories() {
        checkClosed();
        if (artifactDecoratorFactories == null) {
            artifactDecoratorFactories = createArtifactDecoratorFactories();
        }
        return artifactDecoratorFactories;
    }

    protected Map<String, ArtifactDecoratorFactory> createArtifactDecoratorFactories() {
        // by default none, this is extension point
        return new HashMap<>();
    }

    // Maven provided

    private Map<String, ArtifactTransformer> artifactTransformers;

    public final Map<String, ArtifactTransformer> getArtifactTransformers() {
        checkClosed();
        if (artifactTransformers == null) {
            artifactTransformers = createArtifactTransformers();
        }
        return artifactTransformers;
    }

    protected Map<String, ArtifactTransformer> createArtifactTransformers() {
        return new HashMap<>();
    }

    private Map<String, MetadataGeneratorFactory> metadataGeneratorFactories;

    public final Map<String, MetadataGeneratorFactory> getMetadataGeneratorFactories() {
        checkClosed();
        if (metadataGeneratorFactories == null) {
            metadataGeneratorFactories = createMetadataGeneratorFactories();
        }
        return metadataGeneratorFactories;
    }

    protected Map<String, MetadataGeneratorFactory> createMetadataGeneratorFactories() {
        // from maven-resolver-provider
        HashMap<String, MetadataGeneratorFactory> result = new HashMap<>();
        result.put(PluginsMetadataGeneratorFactory.NAME, new PluginsMetadataGeneratorFactory());
        result.put(VersionsMetadataGeneratorFactory.NAME, new VersionsMetadataGeneratorFactory());
        result.put(SnapshotMetadataGeneratorFactory.NAME, new SnapshotMetadataGeneratorFactory());
        return result;
    }

    private LinkedHashMap<String, MavenArtifactRelocationSource> artifactRelocationSources;

    public final LinkedHashMap<String, MavenArtifactRelocationSource> getMavenArtifactRelocationSources() {
        checkClosed();
        if (artifactRelocationSources == null) {
            artifactRelocationSources = createMavenArtifactRelocationSources();
        }
        return artifactRelocationSources;
    }

    protected LinkedHashMap<String, MavenArtifactRelocationSource> createMavenArtifactRelocationSources() {
        // from maven-resolver-provider
        LinkedHashMap<String, MavenArtifactRelocationSource> result = new LinkedHashMap<>();
        result.put(UserPropertiesArtifactRelocationSource.NAME, new UserPropertiesArtifactRelocationSource());
        result.put(
                DistributionManagementArtifactRelocationSource.NAME,
                new DistributionManagementArtifactRelocationSource());
        return result;
    }

    private ArtifactDescriptorReader artifactDescriptorReader;

    public final ArtifactDescriptorReader getArtifactDescriptorReader() {
        checkClosed();
        if (artifactDescriptorReader == null) {
            artifactDescriptorReader = createArtifactDescriptorReader();
        }
        return artifactDescriptorReader;
    }

    protected ArtifactDescriptorReader createArtifactDescriptorReader() {
        // from maven-resolver-provider
        return new DefaultArtifactDescriptorReader(
                getRemoteRepositoryManager(),
                getVersionResolver(),
                getVersionRangeResolver(),
                getArtifactResolver(),
                getModelBuilder(),
                getRepositoryEventDispatcher(),
                getModelCacheFactory(),
                getMavenArtifactRelocationSources());
    }

    private VersionResolver versionResolver;

    public final VersionResolver getVersionResolver() {
        checkClosed();
        if (versionResolver == null) {
            versionResolver = createVersionResolver();
        }
        return versionResolver;
    }

    protected VersionResolver createVersionResolver() {
        // from maven-resolver-provider
        return new DefaultVersionResolver(
                getMetadataResolver(), getSyncContextFactory(), getRepositoryEventDispatcher());
    }

    private VersionRangeResolver versionRangeResolver;

    public final VersionRangeResolver getVersionRangeResolver() {
        checkClosed();
        if (versionRangeResolver == null) {
            versionRangeResolver = createVersionRangeResolver();
        }
        return versionRangeResolver;
    }

    protected VersionRangeResolver createVersionRangeResolver() {
        // from maven-resolver-provider
        return new DefaultVersionRangeResolver(
                getMetadataResolver(), getSyncContextFactory(), getRepositoryEventDispatcher(), getVersionScheme());
    }

    private ModelBuilder modelBuilder;

    public final ModelBuilder getModelBuilder() {
        checkClosed();
        if (modelBuilder == null) {
            modelBuilder = createModelBuilder();
        }
        return modelBuilder;
    }

    protected ModelBuilder createModelBuilder() {
        // from maven-model-builder
        return new DefaultModelBuilderFactory().newInstance();
    }

    private ModelCacheFactory modelCacheFactory;

    public final ModelCacheFactory getModelCacheFactory() {
        checkClosed();
        if (modelCacheFactory == null) {
            modelCacheFactory = createModelCacheFactory();
        }
        return modelCacheFactory;
    }

    protected ModelCacheFactory createModelCacheFactory() {
        // from maven-resolver-provider
        return new DefaultModelCacheFactory();
    }

    private List<ValidatorFactory> validatorFactories;

    public final List<ValidatorFactory> getValidatorFactories() {
        checkClosed();
        if (validatorFactories == null) {
            validatorFactories = createValidatorFactories();
        }
        return validatorFactories;
    }

    protected List<ValidatorFactory> createValidatorFactories() {
        return new ArrayList<>();
    }

    private RepositorySystemValidator repositorySystemValidator;

    public final RepositorySystemValidator getRepositorySystemValidator() {
        checkClosed();
        if (repositorySystemValidator == null) {
            repositorySystemValidator = createRepositorySystemValidator();
        }
        return repositorySystemValidator;
    }

    protected RepositorySystemValidator createRepositorySystemValidator() {
        return new DefaultRepositorySystemValidator(getValidatorFactories());
    }

    private RepositorySystem repositorySystem;

    public final RepositorySystem getRepositorySystem() {
        checkClosed();
        if (repositorySystem == null) {
            repositorySystem = createRepositorySystem();
        }
        return repositorySystem;
    }

    protected RepositorySystem createRepositorySystem() {
        return new DefaultRepositorySystem(
                getVersionResolver(),
                getVersionRangeResolver(),
                getArtifactResolver(),
                getMetadataResolver(),
                getArtifactDescriptorReader(),
                getDependencyCollector(),
                getInstaller(),
                getDeployer(),
                getLocalRepositoryProvider(),
                getSyncContextFactory(),
                getRemoteRepositoryManager(),
                getRepositorySystemLifecycle(),
                getArtifactDecoratorFactories(),
                getRepositorySystemValidator());
    }

    @Override
    public RepositorySystem get() {
        return getRepositorySystem();
    }
}
