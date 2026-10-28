package dev.turboism.pluginmanagement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * HTTPS-only transport client for the Plugin Directory v2 catalog, detached signature,
 * and approved GitHub Release JAR artifacts.
 */
public final class PluginCatalogClient {

    /** Canonical HTTPS endpoint for the complete v2 catalog. */
    public static final URI CATALOG_URI = URI.create("https://plugin.turboism.dev/api/v2/catalog.json");

    /** Canonical HTTPS endpoint for the detached v2 catalog signature envelope. */
    public static final URI SIGNATURE_URI = URI.create("https://plugin.turboism.dev/api/v2/catalog.json.sig");

    /** Accept header value for the v2 catalog endpoint. */
    public static final String CATALOG_ACCEPT = "application/vnd.turboism.plugin-catalog+json;version=2";

    /** Accept header value for the v2 signature endpoint. */
    public static final String SIGNATURE_ACCEPT = "application/vnd.turboism.plugin-catalog-signature+json;version=2";

    /** Approved hosts for initial GitHub Release artifact downloads and CDN redirect hops. */
    public static final Set<String> ALLOWED_ARTIFACT_HOSTS =
            Set.of("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");

    /** Default per-request deadline. */
    public static final Duration DEFAULT_REQUEST_DEADLINE = Duration.ofSeconds(30);

    /** Total artifact budget, including redirects, retry and local writes. */
    public static final Duration DEFAULT_ARTIFACT_DEADLINE = Duration.ofMinutes(5);

    private static final Duration READ_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Pattern GITHUB_RELEASE_PATH = Pattern.compile(
            "/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/?#]+/[A-Za-z0-9][A-Za-z0-9._-]*\\.jar");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /** Pluggable HTTP connection factory used by unit tests. */
    @FunctionalInterface
    public interface ConnectionFactory {
        /** Opens an {@link HttpURLConnection} for {@code uri}. */
        HttpURLConnection open(URI uri) throws IOException;
    }

    private static final ConnectionFactory DEFAULT_CONNECTION_FACTORY = uri -> {
        final URLConnection opened = uri.toURL().openConnection();
        if (!(opened instanceof HttpURLConnection http)) {
            throw new IOException("URI did not open an HttpURLConnection: " + uri);
        }
        return http;
    };

    private final URI catalogUri;
    private final URI signatureUri;
    private final Duration requestDeadline;
    private final Duration artifactDeadline;
    private final int artifactAttempts;
    private final ConnectionFactory connectionFactory;

    /** Creates a client targeting the canonical {@code plugin.turboism.dev} v2 endpoints. */
    public PluginCatalogClient() {
        this(
                CATALOG_URI,
                SIGNATURE_URI,
                DEFAULT_REQUEST_DEADLINE,
                DEFAULT_ARTIFACT_DEADLINE,
                DEFAULT_CONNECTION_FACTORY,
                2);
    }

    /** Creates a client with custom endpoints, request deadline, and connection factory. */
    public PluginCatalogClient(
            final URI catalogUri,
            final URI signatureUri,
            final Duration requestDeadline,
            final ConnectionFactory connectionFactory) {
        this(catalogUri, signatureUri, requestDeadline, requestDeadline, connectionFactory, 1);
    }

    /** Creates a client with separate catalog and artifact budgets. Connections honor the supplied factory's proxy policy. */
    public PluginCatalogClient(
            final URI catalogUri,
            final URI signatureUri,
            final Duration requestDeadline,
            final Duration artifactDeadline,
            final ConnectionFactory connectionFactory) {
        this(catalogUri, signatureUri, requestDeadline, artifactDeadline, connectionFactory, 2);
    }

    private PluginCatalogClient(
            final URI catalogUri,
            final URI signatureUri,
            final Duration requestDeadline,
            final Duration artifactDeadline,
            final ConnectionFactory connectionFactory,
            final int artifactAttempts) {
        this.catalogUri = requireStrictHttpsEndpoint(catalogUri, "catalogUri");
        this.signatureUri = requireStrictHttpsEndpoint(signatureUri, "signatureUri");
        this.requestDeadline = Objects.requireNonNull(requestDeadline, "requestDeadline");
        this.artifactDeadline = Objects.requireNonNull(artifactDeadline, "artifactDeadline");
        this.artifactAttempts = artifactAttempts;
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        if (requestDeadline.isZero() || requestDeadline.isNegative()) {
            throw new IllegalArgumentException("requestDeadline must be positive");
        }
        if (artifactDeadline.isZero() || artifactDeadline.isNegative()) {
            throw new IllegalArgumentException("artifactDeadline must be positive");
        }
    }

    /** Byte progress; completion still requires hash and package validation. */
    public record DownloadProgress(long receivedBytes, long totalBytes) {}

    /** Cancellation for one installation. Disconnecting never blocks the caller's UI thread. */
    public static final class DownloadControl {
        private enum State {
            ACTIVE,
            CANCELLED,
            COMMITTED
        }

        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();

        /** Requests cancellation until the installation starts committing files. */
        public boolean cancel() {
            if (!state.compareAndSet(State.ACTIVE, State.CANCELLED)) return false;
            final HttpURLConnection active = connection.get();
            if (active != null) CompletableFuture.runAsync(() -> disconnectQuietly(active));
            return true;
        }

        /** Returns whether cancellation was accepted. */
        public boolean isCancelled() {
            return state.get() == State.CANCELLED;
        }

        /** Ends cancellation admission immediately before applying or journalling an installation. */
        public boolean beginCommit() {
            return state.compareAndSet(State.ACTIVE, State.COMMITTED);
        }

        /** Returns whether cancellation can still be requested. */
        public boolean canCancel() {
            return state.get() == State.ACTIVE;
        }
    }

    /** Stable download failure code for actionable market feedback. */
    public static final class DownloadFailure extends IOException {
        private final String code;

        private DownloadFailure(final String code, final String message, final Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /** Returns the failed download stage or verification code. */
        public String code() {
            return code;
        }
    }

    /** Returns the configured HTTPS catalog endpoint. */
    public URI catalogUri() {
        return catalogUri;
    }

    /** Returns the configured HTTPS signature endpoint. */
    public URI signatureUri() {
        return signatureUri;
    }

    /**
     * Fetches the v2 catalog and its detached signature, honoring {@code etag} revalidation.
     *
     * @param etag optional previously cached ETag header value
     * @return {@link CatalogFetchResult.NotModified} on HTTP 304, or {@link CatalogFetchResult.Fetched} on HTTP 200
     * @throws IOException when the network request, status code, encoding, or size bound fails
     */
    public CatalogFetchResult fetchCatalog(final Optional<String> etag) throws IOException {
        Objects.requireNonNull(etag, "etag");
        final long deadlineNanos = deadlineNanos(requestDeadline);
        final RawResponse catalogResponse =
                fetchBytes(catalogUri, CATALOG_ACCEPT, etag, PluginCatalogVerifier.MAX_CATALOG_BYTES, deadlineNanos);
        if (catalogResponse.statusCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
            if (catalogResponse.body().length != 0) {
                throw new IOException("HTTP 304 response must not carry a body");
            }
            return new CatalogFetchResult.NotModified(catalogResponse.etag().or(() -> etag));
        }
        if (catalogResponse.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("catalog request failed with HTTP " + catalogResponse.statusCode());
        }
        if (catalogResponse.body().length == 0) {
            throw new IOException("catalog response body is empty");
        }
        final RawResponse signatureResponse = fetchBytes(
                signatureUri,
                SIGNATURE_ACCEPT,
                Optional.empty(),
                PluginCatalogVerifier.MAX_SIGNATURE_BYTES,
                deadlineNanos);
        if (signatureResponse.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("signature request failed with HTTP " + signatureResponse.statusCode());
        }
        if (signatureResponse.body().length == 0) {
            throw new IOException("signature response body is empty");
        }
        return new CatalogFetchResult.Fetched(catalogResponse.body(), signatureResponse.body(), catalogResponse.etag());
    }

    /**
     * Downloads an immutable plugin JAR artifact from an approved GitHub Release host,
     * enforcing HTTPS, redirect host allowlisting, exact byte length, and constant-time SHA-256 equality.
     *
     * @param artifactUrl initial HTTPS GitHub Release artifact URL
     * @param expectedSize exact expected JAR size in bytes (`1..16 MiB`)
     * @param expectedSha256 expected lowercase 64-hex SHA-256 digest
     * @param destination target file path to write
     * @throws IOException when host admission, redirect bounds, size, or SHA-256 verification fails
     */
    public void downloadArtifact(
            final URI artifactUrl, final long expectedSize, final String expectedSha256, final Path destination)
            throws IOException {
        downloadArtifact(artifactUrl, expectedSize, expectedSha256, destination, new DownloadControl(), ignored -> {});
    }

    /** Downloads a verified artifact with cancellable, worker-thread progress notifications. */
    public void downloadArtifact(
            final URI artifactUrl,
            final long expectedSize,
            final String expectedSha256,
            final Path destination,
            final DownloadControl control,
            final Consumer<DownloadProgress> progress)
            throws IOException {
        Objects.requireNonNull(artifactUrl, "artifactUrl");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(progress, "progress");
        if (expectedSize <= 0L || expectedSize > PluginCatalogVerifier.MAX_ARTIFACT_BYTES) {
            throw new IOException("expected artifact size is outside the 1..16 MiB bound: " + expectedSize);
        }
        if (!SHA256_PATTERN.matcher(expectedSha256).matches()) {
            throw new IOException("expectedSha256 must be lowercase 64-hex");
        }
        validateInitialArtifactUri(artifactUrl);

        final long deadlineNanos = deadlineNanos(artifactDeadline);
        for (int attempt = 1; ; attempt++) {
            try {
                downloadAttempt(
                        artifactUrl, expectedSize, expectedSha256, destination, control, progress, deadlineNanos);
                return;
            } catch (DownloadFailure failure) {
                if (attempt >= artifactAttempts
                        || control.isCancelled()
                        || !(failure.code().equals("PLUGIN_DOWNLOAD_RESPONSE_TIMEOUT")
                                || failure.code().equals("PLUGIN_DOWNLOAD_READ_TIMEOUT"))) throw failure;
                checkDownload(deadlineNanos, control);
            }
        }
    }

    private void downloadAttempt(
            final URI artifactUrl,
            final long expectedSize,
            final String expectedSha256,
            final Path destination,
            final DownloadControl control,
            final Consumer<DownloadProgress> progress,
            final long deadlineNanos)
            throws IOException {
        URI currentUri = artifactUrl;
        for (int redirects = 0; ; redirects++) {
            checkDownload(deadlineNanos, control);
            final HttpURLConnection connection = connectionFactory.open(currentUri);
            control.connection.set(connection);
            try {
                checkDownload(deadlineNanos, control);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                final long remaining = remainingNanos(deadlineNanos);
                connection.setConnectTimeout(timeoutMillis(Math.min(CONNECT_TIMEOUT.toNanos(), remaining)));
                setDownloadReadTimeout(connection, deadlineNanos);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", PluginCatalog.CatalogArtifact.MEDIA_TYPE);
                connection.setRequestProperty("Accept-Encoding", "identity");

                final int status;
                try {
                    status = connection.getResponseCode();
                } catch (SocketTimeoutException failure) {
                    checkDownload(deadlineNanos, control);
                    throw new DownloadFailure(
                            "PLUGIN_DOWNLOAD_RESPONSE_TIMEOUT",
                            "Timed out connecting or waiting for HTTPS response from " + currentUri.getHost(),
                            failure);
                } catch (IOException failure) {
                    checkDownload(deadlineNanos, control);
                    throw new DownloadFailure(
                            "PLUGIN_DOWNLOAD_CONNECTION_FAILED",
                            "Cannot connect to " + currentUri.getHost() + ": " + failure.getMessage(),
                            failure);
                }
                checkDownload(deadlineNanos, control);
                if (REDIRECT_STATUSES.contains(status)) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException("artifact download exceeded max redirects (" + MAX_REDIRECTS + ")");
                    }
                    final String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("redirect response missing Location header");
                    }
                    final URI nextUri;
                    try {
                        nextUri = currentUri.resolve(location.trim());
                    } catch (IllegalArgumentException badRedirect) {
                        throw new IOException("invalid redirect Location URI", badRedirect);
                    }
                    validateRedirectArtifactUri(nextUri);
                    currentUri = nextUri;
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new DownloadFailure(
                            "PLUGIN_DOWNLOAD_HTTP_FAILED", "Artifact download failed with HTTP " + status, null);
                }
                rejectCompressedContentEncoding(connection);
                final long contentLength = connection.getContentLengthLong();
                if (contentLength >= 0L && contentLength != expectedSize) {
                    throw new IOException(
                            "PLUGIN_ARTIFACT_SIZE_MISMATCH: Content-Length " + contentLength + " != " + expectedSize);
                }
                try (InputStream input = connection.getInputStream()) {
                    streamAndVerifyArtifact(
                            input,
                            expectedSize,
                            expectedSha256,
                            destination,
                            deadlineNanos,
                            connection,
                            control,
                            progress);
                }
                return;
            } finally {
                control.connection.compareAndSet(connection, null);
                disconnectQuietly(connection);
            }
        }
    }

    private static void setDownloadReadTimeout(final HttpURLConnection connection, final long deadlineNanos) {
        connection.setReadTimeout(timeoutMillis(Math.min(READ_IDLE_TIMEOUT.toNanos(), remainingNanos(deadlineNanos))));
    }

    private static void checkDownload(final long deadlineNanos, final DownloadControl control) throws DownloadFailure {
        if (control.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new DownloadFailure("PLUGIN_DOWNLOAD_CANCELLED", "Plugin download cancelled", null);
        }
        if (remainingNanos(deadlineNanos) <= 0L) {
            throw new DownloadFailure(
                    "PLUGIN_DOWNLOAD_DEADLINE_EXCEEDED", "Plugin download exceeded its total time budget", null);
        }
    }

    private RawResponse fetchBytes(
            final URI uri,
            final String accept,
            final Optional<String> etag,
            final int maxBytes,
            final long deadlineNanos)
            throws IOException {
        checkDeadline(deadlineNanos);
        final HttpURLConnection connection = connectionFactory.open(uri);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            final long remaining = remainingNanos(deadlineNanos);
            connection.setConnectTimeout(timeoutMillis(Math.min(CONNECT_TIMEOUT.toNanos(), remaining)));
            connection.setReadTimeout(timeoutMillis(remaining));
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Accept-Encoding", "identity");
            etag.filter(value -> !value.isBlank())
                    .ifPresent(value -> connection.setRequestProperty("If-None-Match", value));

            final int status = connection.getResponseCode();
            checkDeadline(deadlineNanos);
            rejectCompressedContentEncoding(connection);
            final long contentLength = connection.getContentLengthLong();
            if (contentLength > maxBytes) {
                throw new IOException("response Content-Length " + contentLength + " exceeds cap " + maxBytes);
            }
            final byte[] body;
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                try (InputStream stream = connection.getInputStream()) {
                    body = readBounded(stream, 0, deadlineNanos);
                }
            } else {
                try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    body = readBounded(stream, maxBytes, deadlineNanos);
                }
            }
            final Optional<String> responseEtag = Optional.ofNullable(connection.getHeaderField("ETag"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty());
            return new RawResponse(status, body, responseEtag);
        } finally {
            disconnectQuietly(connection);
        }
    }

    private static byte[] readBounded(final InputStream stream, final int maxBytes, final long deadlineNanos)
            throws IOException {
        if (stream == null) return new byte[0];
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int total = 0;
        for (int read; ; ) {
            checkDeadline(deadlineNanos);
            read = stream.read(chunk);
            if (read < 0) break;
            if (read == 0) continue;
            if (read > maxBytes - total) {
                throw new IOException("response body exceeds cap of " + maxBytes + " bytes");
            }
            buffer.write(chunk, 0, read);
            total += read;
        }
        checkDeadline(deadlineNanos);
        return buffer.toByteArray();
    }

    private static void streamAndVerifyArtifact(
            final InputStream input,
            final long expectedSize,
            final String expectedSha256,
            final Path destination,
            final long deadlineNanos,
            final HttpURLConnection connection,
            final DownloadControl control,
            final Consumer<DownloadProgress> progress)
            throws IOException {
        if (input == null) {
            throw new IOException("PLUGIN_ARTIFACT_SIZE_MISMATCH: empty artifact response stream");
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        long total = 0L;
        final byte[] buffer = new byte[16 * 1024];
        boolean completed = false;
        boolean opened = false;
        try {
            try (OutputStream output = new java.io.BufferedOutputStream(
                    Files.newOutputStream(
                            destination,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE),
                    64 * 1024)) {
                opened = true;
                notifyProgress(progress, 0, expectedSize);
                for (int read; ; ) {
                    checkDownload(deadlineNanos, control);
                    setDownloadReadTimeout(connection, deadlineNanos);
                    try {
                        read = input.read(buffer);
                    } catch (SocketTimeoutException failure) {
                        checkDownload(deadlineNanos, control);
                        throw new DownloadFailure(
                                "PLUGIN_DOWNLOAD_READ_TIMEOUT", "Plugin download stopped receiving data", failure);
                    } catch (IOException failure) {
                        checkDownload(deadlineNanos, control);
                        throw new DownloadFailure(
                                "PLUGIN_DOWNLOAD_READ_FAILED",
                                "Plugin download interrupted: " + failure.getMessage(),
                                failure);
                    }
                    checkDownload(deadlineNanos, control);
                    if (read < 0) break;
                    if (read == 0) continue;
                    if (read > expectedSize - total) {
                        throw new DownloadFailure(
                                "PLUGIN_ARTIFACT_SIZE_MISMATCH",
                                "PLUGIN_ARTIFACT_SIZE_MISMATCH: stream exceeded expected size " + expectedSize,
                                null);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    total += read;
                    notifyProgress(progress, total, expectedSize);
                }
                if (total != expectedSize) {
                    throw new DownloadFailure(
                            "PLUGIN_ARTIFACT_SIZE_MISMATCH",
                            "PLUGIN_ARTIFACT_SIZE_MISMATCH: read " + total + " bytes, expected " + expectedSize,
                            null);
                }
                final byte[] actualHash = digest.digest();
                final byte[] expectedHash = HexFormat.of().parseHex(expectedSha256);
                if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                    throw new DownloadFailure(
                            "PLUGIN_ARTIFACT_HASH_MISMATCH",
                            "PLUGIN_ARTIFACT_HASH_MISMATCH: downloaded JAR SHA-256 mismatch",
                            null);
                }
            } catch (DownloadFailure failure) {
                throw failure;
            } catch (IOException failure) {
                throw new DownloadFailure(
                        "PLUGIN_DOWNLOAD_WRITE_FAILED",
                        "Cannot write plugin download: " + failure.getMessage(),
                        failure);
            }
            checkDownload(deadlineNanos, control);
            completed = true;
        } finally {
            if (!completed && opened) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException ignored) {
                    // Best-effort cleanup of rejected artifact file.
                }
            }
        }
    }

    private static void notifyProgress(
            final Consumer<DownloadProgress> progress, final long received, final long total) {
        try {
            progress.accept(new DownloadProgress(received, total));
        } catch (RuntimeException ignored) {
            // A UI observer must not alter artifact verification or installation.
        }
    }

    private static void rejectCompressedContentEncoding(final HttpURLConnection connection) throws IOException {
        final String encoding = connection.getHeaderField("Content-Encoding");
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
            throw new IOException("unexpected Content-Encoding header: " + encoding);
        }
    }

    /** Validates that {@code uri} is a strict HTTPS endpoint without credentials, query, or fragment. */
    public static URI requireStrictHttpsEndpoint(final URI uri, final String name) {
        Objects.requireNonNull(uri, name);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    name + " must be an https URI without credentials, query, or fragment: " + uri);
        }
        return uri;
    }

    /** Validates that an initial artifact download URL targets an approved GitHub Release path over HTTPS. */
    public static void validateInitialArtifactUri(final URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri");
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawPath() == null
                || !GITHUB_RELEASE_PATH.matcher(uri.getRawPath()).matches()) {
            throw new IOException("unapproved artifact download URL: " + uri);
        }
    }

    private static void validateRedirectArtifactUri(final URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new IOException("unapproved redirect URI: " + uri);
        }
        final String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ARTIFACT_HOSTS.contains(host)) {
            throw new IOException("unapproved redirect host: " + host);
        }
    }

    private static void checkDeadline(final long deadlineNanos) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("catalog request was interrupted");
        }
        if (remainingNanos(deadlineNanos) <= 0L) {
            throw new IOException("catalog request exceeded deadline");
        }
    }

    private static long remainingNanos(final long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    private static long deadlineNanos(final Duration duration) {
        final long nanos = duration.toNanos();
        final long now = System.nanoTime();
        return now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
    }

    private static int timeoutMillis(final long nanos) {
        final long millis = TimeUnit.NANOSECONDS.toMillis(Math.max(1L, nanos));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
    }

    private static void disconnectQuietly(final HttpURLConnection connection) {
        if (connection == null) return;
        try {
            connection.disconnect();
        } catch (RuntimeException ignored) {
            // Best-effort connection cleanup.
        }
    }

    private record RawResponse(int statusCode, byte[] body, Optional<String> etag) {}

    /** Outcome of a catalog fetch with ETag revalidation. */
    public sealed interface CatalogFetchResult permits CatalogFetchResult.Fetched, CatalogFetchResult.NotModified {

        /** HTTP 200 outcome carrying fresh catalog bytes, signature bytes, and optional ETag. */
        record Fetched(byte[] catalogBytes, byte[] signatureBytes, Optional<String> etag)
                implements CatalogFetchResult {
            public Fetched {
                catalogBytes =
                        Objects.requireNonNull(catalogBytes, "catalogBytes").clone();
                signatureBytes =
                        Objects.requireNonNull(signatureBytes, "signatureBytes").clone();
                etag = Objects.requireNonNull(etag, "etag");
            }
        }

        /** HTTP 304 outcome indicating the cached catalog remains current. */
        record NotModified(Optional<String> etag) implements CatalogFetchResult {
            public NotModified {
                etag = Objects.requireNonNull(etag, "etag");
            }
        }
    }
}
