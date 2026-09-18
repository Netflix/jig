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
 *//*
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
    *//*
          * Originally from Apache Maven Resolver, maven-resolver-transport-jdk-11 2.0.8.
          * Modified: package relocated, adapted for resolver 1.9.x API
          * (HttpTransporter marker removed, PathProcessor replaced with direct NIO,
          * HttpConstants inlined, ChecksumExtractor repackaged, service-unavailable retries added).
          */
package com.netflix.tools.jig.module.maven.transport;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.Authenticator.RequestorType;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import com.netflix.tools.jig.internal.logging.Logger;
import com.netflix.tools.jig.internal.logging.LoggerFactory;
import com.netflix.tools.jig.internal.org.eclipse.aether.ConfigurationProperties;
import com.netflix.tools.jig.internal.org.eclipse.aether.RepositorySystemSession;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.AuthenticationContext;
import com.netflix.tools.jig.internal.org.eclipse.aether.repository.RemoteRepository;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.GetTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.PeekTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.PutTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.spi.connector.transport.TransportTask;
import com.netflix.tools.jig.internal.org.eclipse.aether.transfer.NoTransporterException;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.ConfigUtils;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.FileUtils;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.FileUtils.CollocatedTempFile;
import com.netflix.tools.jig.internal.org.eclipse.aether.util.FileUtils.TempFile;
import com.netflix.tools.jig.module.ModuleIdentity;
import com.netflix.tools.jig.module.maven.transport.JdkHttpClientState.AuthenticationScope;
import com.netflix.tools.jig.module.maven.transport.JdkHttpClientState.ClientProfile;
import com.netflix.tools.jig.module.maven.transport.JdkHttpClientState.ProxyProfile;
import com.netflix.tools.jig.module.maven.transport.MavenModuleProbe.Complete;
import com.netflix.tools.jig.module.maven.transport.ZipCentralDirectory.Entry;

import static com.netflix.tools.jig.internal.org.eclipse.aether.util.connector.transport.http.HttpTransporterUtils.getHttpRetryHandlerCount;
import static com.netflix.tools.jig.internal.org.eclipse.aether.util.connector.transport.http.HttpTransporterUtils.getHttpRetryHandlerInterval;
import static com.netflix.tools.jig.internal.org.eclipse.aether.util.connector.transport.http.HttpTransporterUtils.getHttpRetryHandlerIntervalMax;
import static com.netflix.tools.jig.internal.org.eclipse.aether.util.connector.transport.http.HttpTransporterUtils.getHttpServiceUnavailableCodes;
import static com.netflix.tools.jig.module.maven.transport.JdkTransporterConfigurationKeys.CONFIG_PROP_CACHE_STATE;
import static com.netflix.tools.jig.module.maven.transport.JdkTransporterConfigurationKeys.CONFIG_PROP_HTTP_VERSION;
import static com.netflix.tools.jig.module.maven.transport.JdkTransporterConfigurationKeys.CONFIG_PROP_MAX_CONCURRENT_REQUESTS;
import static com.netflix.tools.jig.module.maven.transport.JdkTransporterConfigurationKeys.DEFAULT_CACHE_STATE;
import static com.netflix.tools.jig.module.maven.transport.JdkTransporterConfigurationKeys.DEFAULT_HTTP_VERSION;
import static com.netflix.tools.jig.module.maven.transport.JdkTransporterConfigurationKeys.DEFAULT_MAX_CONCURRENT_REQUESTS;
import static com.netflix.tools.jig.module.maven.transport.TransportTrace.trace;

/**
 * JDK Transport using {@link HttpClient}.
 * <p>
 * Known issues:
 * <ul>
 *     <li>Does not support {@link ConfigurationProperties#REQUEST_TIMEOUT}, see <a href="https://bugs.openjdk.org/browse/JDK-8258397">JDK-8258397</a></li>
 * </ul>
 *
 * @since 2.0.0
 */
@SuppressWarnings({"checkstyle:magicnumber"})
final class JdkTransporter extends AbstractTransporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdkTransporter.class);

    private static final DateTimeFormatter RFC7231 = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));

    private static final long MODIFICATION_THRESHOLD = 60L * 1000L;

    // HTTP constants (inlined from 2.x HttpConstants)
    private static final int MULTIPLE_CHOICES = 300;
    private static final int NOT_FOUND = 404;
    private static final int PRECONDITION_FAILED = 412;
    private static final String ACCEPT_ENCODING = "Accept-Encoding";
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String CONTENT_RANGE = "Content-Range";
    private static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";
    private static final String LAST_MODIFIED = "Last-Modified";
    private static final String RANGE = "Range";
    private static final String RETRY_AFTER = "Retry-After";
    private static final String USER_AGENT = "User-Agent";
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/\\d+");

    private final ChecksumExtractor checksumExtractor;

    private final URI baseUri;

    private final HttpClient client;

    private final boolean closeClient;

    private final Map<String, String> headers;

    private final int connectTimeout;

    private final int requestTimeout;

    private final Boolean expectContinue;

    private final Semaphore maxConcurrentRequests;

    private final int retryCount;

    private final long retryInterval;

    private final long retryIntervalMax;

    private final Set<Integer> retryStatusCodes;

    JdkTransporter(RepositorySystemSession session, RemoteRepository repository, int javaVersion,
                   ChecksumExtractor checksumExtractor)
            throws NoTransporterException {
        this.checksumExtractor = checksumExtractor;
        try {
            URI uri = new URI(repository.getUrl()).parseServerAuthority();
            if (uri.isOpaque()) {
                throw new URISyntaxException(repository.getUrl(), "URL must not be opaque");
            }
            if (uri.getRawFragment() != null || uri.getRawQuery() != null) {
                throw new URISyntaxException(repository.getUrl(), "URL must not have fragment or query");
            }
            String path = uri.getPath();
            if (path == null) {
                path = "/";
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            this.baseUri = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path);
        } catch (URISyntaxException e) {
            throw new NoTransporterException(repository, e.getMessage(), e);
        }

        HashMap<String, String> headers = new HashMap<>();
        String userAgent = ConfigUtils.getString(session, ConfigurationProperties.DEFAULT_USER_AGENT, ConfigurationProperties.USER_AGENT);
        if (userAgent != null) {
            headers.put(USER_AGENT, userAgent);
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> configuredHeaders = (Map<Object, Object>) ConfigUtils.getMap(session, Collections.emptyMap(), ConfigurationProperties.HTTP_HEADERS + "." + repository.getId(), ConfigurationProperties.HTTP_HEADERS);
        if (configuredHeaders != null) {
            configuredHeaders.forEach(
                    (k, v) -> headers.put(String.valueOf(k),
                            v != null ? String.valueOf(v) : null));
        }
        headers.put(CACHE_CONTROL, "no-cache, no-store");

        this.connectTimeout = ConfigUtils.getInteger(session, ConfigurationProperties.DEFAULT_CONNECT_TIMEOUT,
                ConfigurationProperties.CONNECT_TIMEOUT + "." + repository.getId(), ConfigurationProperties.CONNECT_TIMEOUT);
        this.requestTimeout = ConfigUtils.getInteger(session, ConfigurationProperties.DEFAULT_REQUEST_TIMEOUT,
                ConfigurationProperties.REQUEST_TIMEOUT + "." + repository.getId(), ConfigurationProperties.REQUEST_TIMEOUT);
        String expectContinueConf = ConfigUtils.getString(session, null, ConfigurationProperties.HTTP_EXPECT_CONTINUE + "." + repository.getId(), ConfigurationProperties.HTTP_EXPECT_CONTINUE);
        if (javaVersion > 19) {
            this.expectContinue = expectContinueConf == null ? null : Boolean.parseBoolean(expectContinueConf);
        } else {
            this.expectContinue = null;
            if (expectContinueConf != null) {
                LOGGER.warn(
                        "Configuration for Expect-Continue set but is ignored on Java versions below 20 (current java version is {}) due https://bugs.openjdk.org/browse/JDK-8286171",
                        javaVersion);
            }
        }
        final String httpsSecurityMode = ConfigUtils.getString(session, ConfigurationProperties.HTTPS_SECURITY_MODE_DEFAULT,
                ConfigurationProperties.HTTPS_SECURITY_MODE + "." + repository.getId(), ConfigurationProperties.HTTPS_SECURITY_MODE);

        if (!ConfigurationProperties.HTTPS_SECURITY_MODE_DEFAULT.equals(httpsSecurityMode) && !ConfigurationProperties.HTTPS_SECURITY_MODE_INSECURE.equals(httpsSecurityMode)) {
            throw new IllegalArgumentException("Unsupported '" + httpsSecurityMode + "' HTTPS security mode.");
        }
        final boolean insecure = ConfigurationProperties.HTTPS_SECURITY_MODE_INSECURE.equals(httpsSecurityMode);

        this.maxConcurrentRequests = new Semaphore(ConfigUtils.getInteger(session, DEFAULT_MAX_CONCURRENT_REQUESTS, CONFIG_PROP_MAX_CONCURRENT_REQUESTS + "." + repository.getId(), CONFIG_PROP_MAX_CONCURRENT_REQUESTS));
        this.retryCount = getHttpRetryHandlerCount(session, repository);
        this.retryInterval = getHttpRetryHandlerInterval(session, repository);
        this.retryIntervalMax = getHttpRetryHandlerIntervalMax(session, repository);
        this.retryStatusCodes = getHttpServiceUnavailableCodes(session, repository);

        this.headers = headers;

        JdkHttpClientState clientState = JdkHttpClientState.get(session);
        ClientProfile clientProfile = clientProfile(session, repository, insecure);
        boolean cacheState = ConfigUtils.getBoolean(session, DEFAULT_CACHE_STATE, CONFIG_PROP_CACHE_STATE);
        if (cacheState) {
            this.client = clientState.client(clientProfile, () -> createClient(session, repository, clientProfile, clientState.executor()));
            this.closeClient = false;
        } else {
            this.client = createClient(session, repository, clientProfile, clientState.executor());
            this.closeClient = true;
        }
    }

    private URI resolve(TransportTask task) {
        return baseUri.resolve(task.getLocation());
    }

    private ConnectException enhance(ConnectException connectException) {
        ConnectException result = new ConnectException("Connection to " + baseUri.toASCIIString() + " refused");
        result.initCause(connectException);
        return result;
    }

    @Override
    public int classify(Throwable error) {
        if (error instanceof HttpTransporterException && ((HttpTransporterException) error).getStatusCode() == NOT_FOUND) {
            return ERROR_NOT_FOUND;
        }
        return ERROR_OTHER;
    }

    @Override
    protected void implPeek(PeekTask task) throws Exception {
        URI uri = resolve(task);
        trace("http head %s", uri);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .method("HEAD", BodyPublishers.noBody());
        headers.forEach(request::setHeader);
        try {
            HttpResponse<Void> response = send(request.build(), BodyHandlers.discarding());
            if (response.statusCode() >= MULTIPLE_CHOICES) {
                throw new HttpTransporterException(response.statusCode());
            }
        } catch (ConnectException e) {
            throw enhance(e);
        }
    }

    private static final int INITIAL_SUFFIX_SIZE = 16384;
    private static final int JMOD_HEADER_SIZE = 4;
    private static final int PARTIAL_CONTENT = 206;

    @Override
    protected void implGet(GetTask task) throws Exception {
        if (MavenModuleProbe.isActive() && isModuleArtifact(task.getLocation()
                .getPath())) {
            trace("http probe %s", resolve(task));
            probeModule(task);
            return;
        }
        trace("http get %s", resolve(task));

        boolean resume = task.getResumeOffset() > 0L && task.getDataFile() != null;
        HttpResponse<InputStream> response = null;

        try {
            while (true) {
                HttpRequest.Builder request = HttpRequest.newBuilder()
                        .uri(resolve(task))
                        .method("GET", BodyPublishers.noBody());
                headers.forEach(request::setHeader);

                if (resume) {
                    long resumeOffset = task.getResumeOffset();
                    Path dataPath = task.getDataFile().toPath();
                    long lastModified = Files.exists(dataPath) ? Files.getLastModifiedTime(dataPath).toMillis() : 0L;
                    request.header(RANGE, "bytes=" + resumeOffset + '-');
                    request.header(IF_UNMODIFIED_SINCE, RFC7231.format(Instant.ofEpochMilli(lastModified - MODIFICATION_THRESHOLD)));
                    request.header(ACCEPT_ENCODING, "identity");
                }

                try {
                    response = send(request.build(), BodyHandlers.ofInputStream());
                    if (response.statusCode() >= MULTIPLE_CHOICES) {
                        if (resume && response.statusCode() == PRECONDITION_FAILED) {
                            closeBody(response);
                            resume = false;
                            continue;
                        }
                        closeBody(response);
                        throw new HttpTransporterException(response.statusCode());
                    }
                } catch (ConnectException e) {
                    closeBody(response);
                    throw enhance(e);
                }
                break;
            }

            long offset = 0L, length = response.headers()
                    .firstValueAsLong(CONTENT_LENGTH)
                    .orElse(-1L);
            if (resume) {
                String range = response.headers()
                                       .firstValue(CONTENT_RANGE)
                                       .orElse(null);
                if (range != null) {
                    Matcher m = CONTENT_RANGE_PATTERN.matcher(range);
                    if (!m.matches()) {
                        throw new IOException("Invalid Content-Range header for partial download: " + range);
                    }
                    offset = Long.parseLong(m.group(1));
                    length = Long.parseLong(m.group(2)) + 1L;
                    if (offset < 0L
                            || offset >= length
                            || (offset > 0L && offset != task.getResumeOffset())) {
                        throw new IOException("Invalid Content-Range header for partial download from offset " + task.getResumeOffset() + ": " + range);
                    }
                }
            }

            final boolean downloadResumed = offset > 0L;
            final File dataFile = task.getDataFile();
            if (dataFile == null) {
                try (InputStream is = response.body()) {
                    utilGet(task, is, true, length, downloadResumed);
                }
            } else {
                try (CollocatedTempFile tempFile = FileUtils.newTempFile(dataFile.toPath())) {
                    task.setDataFile(tempFile.getPath()
                            .toFile(),
                            downloadResumed);
                    if (downloadResumed && Files.isRegularFile(dataFile.toPath())) {
                        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(dataFile.toPath()))) {
                            Files.copy(inputStream, tempFile.getPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    try (InputStream is = response.body()) {
                        utilGet(task, is, true, length, downloadResumed);
                    }
                    tempFile.move();
                } finally {
                    task.setDataFile(dataFile);
                }
            }
            if (task.getDataFile() != null) {
                String lastModifiedHeader = response.headers()
                        .firstValue(LAST_MODIFIED)
                        .orElse(null); // note: Wagon also does first not last
                if (lastModifiedHeader != null) {
                    try {
                        Files.setLastModifiedTime(task.getDataFile().toPath(),
                                FileTime.from(ZonedDateTime.parse(lastModifiedHeader, RFC7231).toInstant()));
                    } catch (DateTimeParseException e) {
                        // fall through
                    }
                }
            }
            Map<String, String> checksums = checksumExtractor.extractChecksums(headerGetter(response));
            if (checksums != null && !checksums.isEmpty()) {
                checksums.forEach(task::setChecksum);
            }
        } finally {
            closeBody(response);
        }
    }

    private static boolean isModuleArtifact(String path) {
        return path.endsWith(".jar") || path.endsWith(".jmod");
    }

    private static Function<String, String> headerGetter(HttpResponse<?> response) {
        return s -> response.headers()
                            .firstValue(s)
                            .orElse(null);
    }

    private record SuffixResult(byte[] data, long totalSize) {
        long suffixStart() {
            return totalSize - data.length;
        }
    }

    private void probeModule(GetTask task) throws Exception {
        URI jarUri = resolve(task);

        SuffixResult suffix = suffixRead(jarUri, INITIAL_SUFFIX_SIZE);
        if (suffix == null) {
            fullDownload(task, jarUri);
            return;
        }

        ZipCentralDirectory cd = parseCdFromSuffix(jarUri, suffix);
        if (cd == null) {
            fullDownload(task, jarUri);
            return;
        }

        MavenModuleProbe.setResult(identityFromCd(jarUri, cd));
        throw new Complete();
    }

    /**
     * Parses the central directory from a suffix, fetching more bytes if
     * the initial suffix was too small.
     */
    private ZipCentralDirectory parseCdFromSuffix(URI jarUri, SuffixResult suffix) throws Exception {
        int archivePrefixLength = jarUri.getPath().endsWith(".jmod") ? JMOD_HEADER_SIZE : 0;
        var cd = ZipCentralDirectory.parse(suffix.data(), suffix.suffixStart(), archivePrefixLength);
        if (cd.isPresent()) {
            return cd.get();
        }

        var eocd = ZipCentralDirectory.readEocd(suffix.data());
        if (eocd.isEmpty()) {
            return null;
        }
        long cdSize = eocd.get()[1];

        SuffixResult larger = suffixRead(jarUri, cdSize + 22);
        if (larger == null) {
            return null;
        }
        return ZipCentralDirectory.parse(larger.data(), larger.suffixStart(), archivePrefixLength).orElse(null);
    }

    private ModuleIdentity identityFromCd(URI jarUri, ZipCentralDirectory cd) throws Exception {
        Function<String, byte[]> reader = name -> {
            var entry = cd.find(name).orElseThrow();
            return cd.tryExtract(entry).orElseGet(() -> {
                try {
                    return rangeReadEntry(jarUri, entry);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        };
        return jarUri.getPath().endsWith(".jmod") ? ModuleIdentity.parseJmodEntries(cd.entryNames(), reader) : ModuleIdentity.parseJarEntries(cd.entryNames(), reader);
    }

    private void fullDownload(GetTask task, URI jarUri) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(jarUri)
                .method("GET", BodyPublishers.noBody());
        headers.forEach(request::setHeader);

        HttpResponse<InputStream> response;
        try {
            response = send(request.build(), BodyHandlers.ofInputStream());
        } catch (ConnectException e) {
            throw enhance(e);
        }
        if (response.statusCode() >= MULTIPLE_CHOICES) {
            closeBody(response);
            throw new HttpTransporterException(response.statusCode());
        }
        long length = response.headers()
                              .firstValueAsLong(CONTENT_LENGTH)
                              .orElse(-1L);
        try (var body = response.body()) {
            utilGet(task, body, false, length, false);
        }
        if (task.getDataFile() != null) {
            MavenModuleProbe.setResult(jarUri.getPath().endsWith(".jmod") ? ModuleIdentity.parseJmod(task.getDataFile()
                    .toPath())
                    : ModuleIdentity.parseJar(task.getDataFile()
                    .toPath()));
        }
    }

    /**
     * Reads the last {@code size} bytes of a remote jar. Returns null
     * if the server doesn't support range requests (200 instead of 206).
     */
    private SuffixResult suffixRead(URI jarUri, long size) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(jarUri)
                .method("GET", BodyPublishers.noBody());
        headers.forEach(request::setHeader);
        request.header(RANGE, "bytes=-" + size);
        request.header(ACCEPT_ENCODING, "identity");

        HttpResponse<byte[]> response;
        try {
            response = send(request.build(), BodyHandlers.ofByteArray());
        } catch (ConnectException e) {
            throw enhance(e);
        }
        if (response.statusCode() >= MULTIPLE_CHOICES) {
            throw new HttpTransporterException(response.statusCode());
        }
        if (response.statusCode() != PARTIAL_CONTENT) {
            return null;
        }
        return new SuffixResult(response.body(), parseTotalSize(response));
    }

    private byte[] rangeReadEntry(URI jarUri, Entry entry) throws Exception {
        long[] range = ZipCentralDirectory.entryByteRange(entry);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(jarUri)
                .method("GET", BodyPublishers.noBody());
        headers.forEach(request::setHeader);
        request.header(RANGE, "bytes=" + range[0] + "-" + (range[1] - 1));
        request.header(ACCEPT_ENCODING, "identity");

        HttpResponse<byte[]> response = send(request.build(), BodyHandlers.ofByteArray());
        if (response.statusCode() != PARTIAL_CONTENT) {
            throw new IOException("Expected 206 for range read, got " + response.statusCode());
        }
        return ZipCentralDirectory.extractEntry(entry, response.body());
    }

    private static long parseTotalSize(HttpResponse<?> response) {
        return response.headers()
                       .firstValue(CONTENT_RANGE)
                       .map(range -> {
                           int slash = range.lastIndexOf('/');
                           return slash >= 0 ? Long.parseLong(range.substring(slash + 1)) : -1L;
                       })
                       .orElse(-1L);
    }

    private void closeBody(HttpResponse<?> response) throws IOException {
        if (response != null && response.body() instanceof InputStream body) {
            body.close();
        }
    }

    @Override
    protected void implPut(PutTask task) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(resolve(task));
        if (expectContinue != null) {
            request = request.expectContinue(expectContinue);
        }
        headers.forEach(request::setHeader);
        try (TempFile tempFile = FileUtils.newTempFile()) {
            utilPut(task, Files.newOutputStream(tempFile.getPath()), true);
            request.method("PUT", BodyPublishers.ofFile(tempFile.getPath()));

            try {
                HttpResponse<Void> response = send(request.build(), BodyHandlers.discarding());
                if (response.statusCode() >= MULTIPLE_CHOICES) {
                    throw new HttpTransporterException(response.statusCode());
                }
            } catch (ConnectException e) {
                throw enhance(e);
            }
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler) throws Exception {
        BodyHandler<T> drainingBodyHandler = responseInfo -> retryStatusCodes.contains(responseInfo.statusCode()) ? BodySubscribers.replacing(null) : responseBodyHandler.apply(responseInfo);
        for (int execution = 1; ; execution++) {
            HttpResponse<T> response;
            maxConcurrentRequests.acquire();
            try {
                response = client.send(request, drainingBodyHandler);
            } finally {
                maxConcurrentRequests.release();
            }
            if (execution > retryCount || !retryStatusCodes.contains(response.statusCode())) {
                return response;
            }
            long delay = retryDelay(response, execution);
            if (delay < 0) {
                LOGGER.warn("HTTP {} from {}; retry interval exceeds configured maximum",
                        response.statusCode(), request.uri());
                return response;
            }
            LOGGER.warn("HTTP {} from {}; retrying in {} ms ({}/{})", response.statusCode(),
                    request.uri(), delay, execution, retryCount);
            trace("http retry %s after %d ms (status %d)",
                    request.uri(), delay, response.statusCode());
            if (delay > 0) {
                Thread.sleep(delay);
            }
        }
    }

    private long retryDelay(HttpResponse<?> response, int execution) {
        Long retryAfter = response.headers()
                .firstValue(RETRY_AFTER)
                .map(this::retryAfter)
                .orElse(null);
        long delay = retryAfter != null ? retryAfter : multiplyBounded(execution, retryInterval);
        return delay <= retryIntervalMax ? delay : -1L;
    }

    private Long retryAfter(String value) {
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? null : multiplyBounded(seconds, 1_000L);
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(
                        ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant()
                                .toEpochMilli()
                                - System.currentTimeMillis(),
                        0L);
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }

    private static long multiplyBounded(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    protected void implClose() {
        if (closeClient) {
            JdkTransporterCloser.closer(client).run();
        }
    }

    private ClientProfile clientProfile(RepositorySystemSession session, RemoteRepository repository, boolean insecure) {
        SSLContext sslContext = null;
        try (AuthenticationContext repoAuthContext = AuthenticationContext.forRepository(session, repository)) {
            if (repoAuthContext != null) {
                sslContext = repoAuthContext.get(AuthenticationContext.SSL_CONTEXT, SSLContext.class);
            }
        }
        AuthenticationScope serverAuthentication = repository.getAuthentication() == null ? null : new AuthenticationScope(baseUri, repository.getAuthentication());
        ProxyProfile proxy = repository.getProxy() == null ? null : new ProxyProfile(
                repository.getProxy().getHost(),
                repository.getProxy().getPort(),
                repository.getProxy().getAuthentication());
        return new ClientProfile(
                Version.valueOf(ConfigUtils.getString(session, DEFAULT_HTTP_VERSION, CONFIG_PROP_HTTP_VERSION + "." + repository.getId(), CONFIG_PROP_HTTP_VERSION)),
                connectTimeout,
                sslContext,
                insecure,
                getHttpLocalAddress(session, repository),
                serverAuthentication,
                proxy);
    }

    private HttpClient createClient(RepositorySystemSession session, RemoteRepository repository, ClientProfile profile,
            Executor executor)
            throws RuntimeException {

        HashMap<RequestorType, PasswordAuthentication> authentications = new HashMap<>();
        try (AuthenticationContext repoAuthContext = AuthenticationContext.forRepository(session, repository)) {
            if (repoAuthContext != null) {
                String username = repoAuthContext.get(AuthenticationContext.USERNAME);
                String password = repoAuthContext.get(AuthenticationContext.PASSWORD);

                authentications.put(RequestorType.SERVER, new PasswordAuthentication(username, password.toCharArray()));
            }
        }

        SSLContext sslContext = profile.sslContext();
        if (sslContext == null) {
            try {
                if (profile.insecure()) {
                    sslContext = SSLContext.getInstance("TLS");
                    X509ExtendedTrustManager tm = new X509ExtendedTrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                    };
                    sslContext.init(null, new X509TrustManager[] {tm}, null);
                } else {
                    sslContext = SSLContext.getDefault();
                }
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new IllegalStateException("SSL Context setup failure", e);
                }
            }
        }

        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(profile.version())
                .followRedirects(Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(profile.connectTimeout()))
                .sslContext(sslContext)
                .executor(executor);

        if (profile.insecure()) {
            SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);
            builder.sslParameters(sslParameters);
        }

        setLocalAddress(builder, profile::localAddress);

        if (profile.proxy() != null) {
            ProxySelector proxy = ProxySelector.of(
                    new InetSocketAddress(profile.proxy().host(),
                            profile.proxy().port()));

            builder.proxy(proxy);
            try (AuthenticationContext proxyAuthContext = AuthenticationContext.forProxy(session, repository)) {
                if (proxyAuthContext != null) {
                    String username = proxyAuthContext.get(AuthenticationContext.USERNAME);
                    String password = proxyAuthContext.get(AuthenticationContext.PASSWORD);

                    authentications.put(RequestorType.PROXY, new PasswordAuthentication(username, password.toCharArray()));
                }
            }
        }

        if (!authentications.isEmpty()) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return authentications.get(getRequestorType());
                }
            });
        }

        return builder.build();
    }

    private static InetAddress getHttpLocalAddress(RepositorySystemSession session, RemoteRepository repository) {
        String bindAddress = ConfigUtils.getString(session, null, "aether.connector.http.localAddress" + "." + repository.getId(), "aether.connector.http.localAddress");
        if (bindAddress == null) {
            return null;
        }
        try {
            return InetAddress.getByName(bindAddress);
        } catch (UnknownHostException uhe) {
            throw new IllegalArgumentException("Given bind address (" + bindAddress + ") cannot be resolved for remote repository " + repository, uhe);
        }
    }

    private static void setLocalAddress(HttpClient.Builder builder, Supplier<InetAddress> addressSupplier) {
        try {
            final InetAddress address = addressSupplier.get();
            if (address == null) {
                return;
            }

            final Method mtd = builder.getClass().getDeclaredMethod("localAddress", InetAddress.class);
            if (!mtd.canAccess(builder)) {
                mtd.setAccessible(true);
            }
            mtd.invoke(builder, address);
        } catch (final NoSuchMethodException nsme) {
            // skip, not yet in the API
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getTargetException());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
