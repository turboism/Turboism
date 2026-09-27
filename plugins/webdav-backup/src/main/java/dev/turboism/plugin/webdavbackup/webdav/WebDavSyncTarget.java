package dev.turboism.plugin.webdavbackup.webdav;

import dev.turboism.sdk.cubism.backup.BackupSyncTarget;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Minimal JDK-only WebDAV {@link BackupSyncTarget}: MKCOL collection creation,
 * PROPFIND existence probe, PUT upload and DELETE, with bounded retry and
 * backoff for 5xx/network failures.
 *
 * <p>Safety: credentials are only ever sent in the {@code Authorization}
 * header; URLs, status codes, and file names may be logged, but never the
 * password (see {@link WebDavConfig#toString()}). Redirects are never
 * followed, so the Authorization header can never be re-issued to another
 * origin; a 3xx response is a fail-closed diagnostic. Zero third-party
 * dependencies ({@code java.net.http} only).</p>
 */
public final class WebDavSyncTarget implements BackupSyncTarget {

    private static final String DAV_DEPTH_1 = "<?xml version=\"1.0\"?>"
        + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>";

    private final WebDavConfig config;
    private final HttpClient client;
    private final java.util.function.Consumer<String> diagnostics;

    public WebDavSyncTarget(final WebDavConfig config) {
        this(config, reason -> { });
    }

    /** Test seam: a diagnostics sink receives sanitized failure reasons (never credentials). */
    public WebDavSyncTarget(
        final WebDavConfig config,
        final java.util.function.Consumer<String> diagnostics
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER);
        if (!config.verifyTls()) {
            builder.sslContext(permissiveSslContext());
        }
        this.client = builder.build();
    }

    @Override
    public void sync(final List<File> newBackupFiles) {
        Objects.requireNonNull(newBackupFiles, "newBackupFiles");
        if (!config.enabled()) {
            return;
        }
        for (File file : newBackupFiles) {
            upload(file);
        }
    }

    /**
     * Probes the configured endpoint (MKCOL + PROPFIND on the remote
     * collection), independent of the {@code enabled} flag, and fails closed
     * with the failing HTTP status (or a sanitized network reason) when the
     * endpoint is unreachable or unauthenticated. Credentials never appear in
     * the failure message.
     */
    public void verify() {
        ensureCollection(config.remotePath());
    }

    /** Uploads one artifact (MKCOL + PROPFIND + PUT with retry); fails closed on error. */
    public void upload(final File file) {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) {
            throw new IllegalStateException("backup artifact is not a regular file: " + file.getName());
        }
        if (file.length() <= 0) {
            throw new IllegalStateException("backup artifact is empty: " + file.getName());
        }
        final String collection = config.remotePath();
        ensureCollection(collection);
        putWithRetry(file, targetUri(collection, file.getName()));
    }

    /** Deletes one remote resource (used for cleanup and tests). */
    public void delete(final String remotePath) {
        final HttpResponse<Void> response = send(
            request("DELETE", targetUri(config.remotePath(), remotePath), HttpRequest.BodyPublishers.noBody()).build()
        );
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new IllegalStateException(
                "webdav delete failed: " + response.statusCode() + " path=" + remotePath
            );
        }
    }

    private void ensureCollection(final String collection) {
        final int created = send(
            request("MKCOL", targetUri(collection, null), HttpRequest.BodyPublishers.noBody()).build()
        ).statusCode();
        if (created == 201) {
            return; // collection created
        }
        if (created / 100 == 5) {
            throw new IllegalStateException("webdav mkcol failed: " + created);
        }
        // 405 (already exists) and other 4xx codes are confirmed with a PROPFIND
        // probe before uploading (RFC 4918 servers may answer 409/403 for an
        // existing collection).
        final int probed = send(request("PROPFIND", targetUri(collection, null),
            HttpRequest.BodyPublishers.ofString(DAV_DEPTH_1))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .build()).statusCode();
        if (probed / 100 != 2) {
            throw new IllegalStateException(
                "webdav collection unavailable: mkcol=" + created + " propfind=" + probed
            );
        }
    }

    private void putWithRetry(final File file, final URI target) {
        final int maxAttempts = 1 + config.retryMax();
        IOException lastNetwork = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                final HttpResponse<Void> response = client.send(
                    request("PUT", target, HttpRequest.BodyPublishers.ofFile(file.toPath()))
                        .header("Content-Type", "application/octet-stream")
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                );
                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    diagnostics.accept("webdav:put-ok file=" + file.getName() + " remote=" + target
                        + " bytes=" + file.length() + " attempts=" + attempt);
                    return;
                }
                rejectRedirect("PUT", response);
                if (response.statusCode() / 100 != 5 && response.statusCode() != 429) {
                    throw new IllegalStateException(
                        "webdav put failed: " + response.statusCode() + " file=" + file.getName()
                    );
                }
                lastNetwork = new IOException("webdav put status " + response.statusCode());
            } catch (IOException failure) {
                lastNetwork = failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("webdav put interrupted: " + file.getName(), interrupted);
            }
            if (attempt < maxAttempts) {
                backoff(attempt);
            }
        }
        diagnostics.accept("webdav:put-exhausted file=" + file.getName());
        throw new IllegalStateException("webdav put exhausted retries: " + file.getName(), lastNetwork);
    }

    private void backoff(final int attempt) {
        final long delay = Math.min(config.retryBaseDelayMs() * (1L << (attempt - 1)), 30_000L);
        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("webdav retry backoff interrupted", interrupted);
        }
    }

    /**
     * Builds the request URI from the configured root URL plus the collection
     * and file name. The root URL may carry its own path (with or without a
     * trailing slash); each collection/file-name segment is percent-encoded by
     * the multi-argument {@link URI} constructor, so spaces, {@code #},
     * {@code %}, {@code ?} and non-ASCII characters in artifact names can
     * never corrupt the path or spill into a query/fragment. A query or
     * fragment on the configured root URL is not carried into the request.
     */
    private URI targetUri(final String collection, final String fileName) {
        final URI base = config.url();
        final StringBuilder path = new StringBuilder();
        final String basePath = base.getPath() == null ? "" : base.getPath();
        if (!basePath.isEmpty() && !"/".equals(basePath)) {
            path.append(basePath.endsWith("/")
                ? basePath.substring(0, basePath.length() - 1)
                : basePath);
        }
        for (String segment : collection.split("/")) {
            if (!segment.isEmpty()) {
                path.append('/').append(segment);
            }
        }
        if (fileName != null) {
            path.append('/').append(fileName);
        }
        if (path.length() == 0) {
            path.append('/');
        }
        try {
            return new URI(base.getScheme(), base.getAuthority(), path.toString(), null, null);
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalStateException("webdav target uri is invalid", failure);
        }
    }

    private HttpRequest.Builder request(
        final String method,
        final URI target,
        final HttpRequest.BodyPublisher body
    ) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .method(method, body);
        if (config.username() != null && !config.username().isBlank()) {
            final String token = Base64.getEncoder().encodeToString(
                (config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8)
            );
            builder.header("Authorization", "Basic " + token);
        }
        return builder;
    }

    private HttpResponse<Void> send(final HttpRequest request) {
        try {
            final HttpResponse<Void> response =
                client.send(request, HttpResponse.BodyHandlers.discarding());
            rejectRedirect(request.method(), response);
            return response;
        } catch (IOException failure) {
            throw new IllegalStateException("webdav request failed: " + request.method(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("webdav request interrupted: " + request.method(), interrupted);
        }
    }

    /**
     * Fails closed on any 3xx: the client never follows redirects, so the
     * Authorization header can only ever reach the configured origin. The
     * diagnostic carries the redirect status and the target authority only —
     * never the full {@code Location} value, which may embed server-issued
     * tokens.
     */
    private void rejectRedirect(final String method, final HttpResponse<Void> response) {
        final int status = response.statusCode();
        if (status < 300 || status > 399) {
            return;
        }
        final String location = response.headers().firstValue("Location").orElse(null);
        final String target = redirectAuthority(location);
        diagnostics.accept("webdav:redirect-not-followed method=" + method
            + " status=" + status + " location=" + target);
        throw new IllegalStateException(
            "webdav " + method + " redirected (status=" + status + ", location=" + target
                + "); redirects are not followed");
    }

    private static String redirectAuthority(final String location) {
        if (location == null || location.isBlank()) {
            return "<none>";
        }
        try {
            final URI uri = URI.create(location.trim());
            final String authority = uri.getRawAuthority();
            if (authority == null) {
                return "<relative>";
            }
            return (uri.getScheme() == null ? "" : uri.getScheme() + "://") + authority;
        } catch (IllegalArgumentException failure) {
            return "<invalid>";
        }
    }

    private static SSLContext permissiveSslContext() {
        try {
            final TrustManager[] trustAll = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context;
        } catch (Exception failure) {
            throw new IllegalStateException("webdav permissive TLS context unavailable", failure);
        }
    }
}
