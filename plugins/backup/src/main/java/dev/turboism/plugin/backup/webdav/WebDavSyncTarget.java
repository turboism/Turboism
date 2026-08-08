package dev.turboism.plugin.backup.webdav;

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
 * password (see {@link WebDavConfig#toString()}). Zero third-party
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
            .followRedirects(HttpClient.Redirect.NORMAL);
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

    private URI targetUri(final String collection, final String fileName) {
        final String base = config.url().toString();
        final String root = base.endsWith("/") ? base : base + "/";
        final String path = fileName == null
            ? collection
            : (collection.equals("/") ? "/" + fileName : collection + "/" + fileName);
        // The normalized collection already starts with '/'; strip it to avoid
        // a double slash after the base root.
        return URI.create(root + (path.startsWith("/") ? path.substring(1) : path));
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
            return client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException failure) {
            throw new IllegalStateException("webdav request failed: " + request.method(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("webdav request interrupted: " + request.method(), interrupted);
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
