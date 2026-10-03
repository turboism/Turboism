package dev.turboism.plugin.webdavbackup.webdav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.plugin.webdavbackup.TestBackupArtifactHandle;
import dev.turboism.sdk.cubism.backup.BackupArtifactHandle;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDavSyncTargetTest {

    @TempDir
    Path temporary;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> rawRequests = new CopyOnWriteArrayList<>();
    private final List<String> putBodies = new CopyOnWriteArrayList<>();
    private Function<String, Integer> statusOverride = path -> null;
    private volatile String redirectLocation;
    private final AtomicInteger putCalls = new AtomicInteger();
    private final List<String> diagnostics = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final String method = exchange.getRequestMethod();
        final URI requestUri = exchange.getRequestURI();
        final String path = requestUri.getPath();
        requests.add(method + " " + path);
        rawRequests.add(method + " " + requestUri.getRawPath()
                + (requestUri.getRawQuery() == null ? "" : "?" + requestUri.getRawQuery()));
        if ("PUT".equals(method)) {
            putCalls.incrementAndGet();
        }
        if (redirectLocation != null) {
            exchange.getResponseHeaders().set("Location", redirectLocation);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        final Integer forced = statusOverride.apply(method + " " + path);
        if (forced != null) {
            exchange.sendResponseHeaders(forced, -1);
            exchange.close();
            return;
        }
        switch (method) {
            case "MKCOL" -> {
                // The mock collection already exists: 405 (Method Not Allowed) is
                // what a real WebDAV server returns for MKCOL on an existing collection.
                exchange.sendResponseHeaders(405, -1);
            }
            case "PROPFIND" -> {
                exchange.sendResponseHeaders(207, -1);
            }
            case "PUT" -> {
                putBodies.add(new String(exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(201, -1);
            }
            case "DELETE" -> {
                exchange.sendResponseHeaders(204, -1);
            }
            default -> exchange.sendResponseHeaders(501, -1);
        }
        exchange.close();
    }

    private WebDavConfig config(
            final boolean enabled,
            final int retryMax,
            final long retryBaseDelayMs,
            final String remotePath,
            final String username,
            final String password) {
        return new WebDavConfig(
                enabled,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                username,
                password,
                remotePath,
                true,
                retryMax,
                retryBaseDelayMs,
                10);
    }

    private BackupArtifactHandle artifact(final String name) throws IOException {
        final Path file = temporary.resolve(name);
        Files.writeString(file, "backup-content-" + name);
        return TestBackupArtifactHandle.of(file);
    }

    @Test
    void uploadsThroughMkcolPropfindAndPutWithTheArtifactName() throws Exception {
        WebDavSyncTarget target =
                new WebDavSyncTarget(config(true, 0, 0, "/turboism-backup", "", ""), diagnostics::add);
        BackupArtifactHandle artifact = artifact("model_backup2026_08_08_1200.cmo3");
        target.sync(List.of(artifact));
        assertTrue(requests.contains("MKCOL /turboism-backup"), "collection must be ensured with MKCOL");
        assertTrue(
                requests.contains("PROPFIND /turboism-backup"),
                "an existing collection (405) must be confirmed with PROPFIND");
        assertTrue(requests.contains("PUT /turboism-backup/model_backup2026_08_08_1200.cmo3"));
        assertEquals("backup-content-model_backup2026_08_08_1200.cmo3", putBodies.get(0));
        assertEquals(1, putCalls.get());
        assertTrue(
                diagnostics.stream()
                        .anyMatch(line -> line.startsWith("webdav:put-ok file=model_backup2026_08_08_1200.cmo3 remote=")
                                && line.contains(" bytes=")
                                && line.endsWith(" attempts=1")),
                "a successful upload must emit the sanitized put-ok diagnostic");
        assertTrue(
                diagnostics.stream().noneMatch(line -> line.contains("s3cret")),
                "diagnostics must never carry credentials");
    }

    @Test
    void uploadsIntoTheRootCollectionWhenRemotePathIsRoot() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/", "", ""), diagnostics::add);
        BackupArtifactHandle artifact = artifact("model_backup2026_08_08_1201.cmo3");
        target.sync(List.of(artifact));
        assertTrue(requests.contains("PUT /model_backup2026_08_08_1201.cmo3"));
    }

    @Test
    void retriesFiveHundredStatusWithBackoffThenSucceeds() throws Exception {
        // 500 once, then success on the second attempt
        statusOverride = path -> path.startsWith("PUT ") && putCalls.get() == 1 ? 500 : null;
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 2, 5, "/backup", "", ""), diagnostics::add);
        BackupArtifactHandle artifact = artifact("model_backup2026_08_08_1202.cmo3");
        target.sync(List.of(artifact));
        assertEquals(2, putCalls.get(), "first PUT must fail with 500 and be retried");
        assertTrue(requests.contains("PUT /backup/model_backup2026_08_08_1202.cmo3"));
        assertTrue(
                diagnostics.stream()
                        .anyMatch(line -> line.startsWith("webdav:put-ok file=model_backup2026_08_08_1202.cmo3")
                                && line.endsWith(" attempts=2")),
                "a retried upload must report the final attempt count");
    }

    @Test
    void exhaustsRetriesAndFailsClosedWithoutCorruptingTheDiagnostics() throws Exception {
        statusOverride = path -> path.startsWith("PUT ") ? 503 : null;
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 2, 2, "/backup", "", ""), diagnostics::add);
        BackupArtifactHandle artifact = artifact("model_backup2026_08_08_1203.cmo3");
        assertThrows(IllegalStateException.class, () -> target.sync(List.of(artifact)));
        assertEquals(3, putCalls.get(), "1 + retryMax attempts");
        assertTrue(
                diagnostics.stream().anyMatch(line -> line.contains("put-exhausted")),
                "sanitized diagnostic must not contain credentials");
    }

    @Test
    void authenticationHeaderCarriesBasicCredentialsButNeverLogsThem() throws Exception {
        final List<String> putAuthHeaders = new CopyOnWriteArrayList<>();
        server.createContext("/auth", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                putAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        WebDavSyncTarget target =
                new WebDavSyncTarget(config(true, 0, 0, "/auth", "alice", "s3cret!"), diagnostics::add);
        BackupArtifactHandle artifact = artifact("model_backup2026_08_08_1204.cmo3");
        target.sync(List.of(artifact));
        assertEquals(1, putAuthHeaders.size());
        assertEquals("Basic YWxpY2U6czNjcmV0IQ==", putAuthHeaders.get(0));
        for (String line : diagnostics) {
            assertFalse(line.contains("s3cret!"), "credentials must never reach diagnostics");
        }
        assertFalse(target.toString().contains("s3cret!"), "config toString must redact the password");
    }

    @Test
    void disabledTargetSkipsUploadEntirely() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(false, 0, 0, "/backup", "", ""), diagnostics::add);
        BackupArtifactHandle artifact = artifact("model_backup2026_08_08_1205.cmo3");
        target.sync(List.of(artifact));
        assertTrue(requests.isEmpty(), "disabled target must not touch the network");
    }

    @Test
    void deleteRemovesRemoteResources() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/backup", "", ""), diagnostics::add);
        target.delete("old_backup2026_08_07_1200.cmo3");
        assertTrue(requests.contains("DELETE /backup/old_backup2026_08_07_1200.cmo3"));
    }

    @Test
    void pathNormalizationRejectsParentEscapesAndCollapsesDots() {
        assertEquals("/a/b", WebDavConfig.normalizePath("/a/./b"));
        assertEquals("/a/b", WebDavConfig.normalizePath("a//b/"), "normalizePath documents no trailing slash");
        assertEquals("/", WebDavConfig.normalizePath(""));
        assertEquals("/", WebDavConfig.normalizePath("/"));
        assertThrows(IllegalArgumentException.class, () -> WebDavConfig.normalizePath("../escape"));
        assertThrows(IllegalArgumentException.class, () -> WebDavConfig.normalizePath("/a/../../escape"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebDavConfig(true, URI.create("ftp://host"), "", "", "/x", true, 0, 0, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebDavConfig(true, URI.create("http://user:pass@host/"), "", "", "/x", true, 0, 0, 10));
    }

    @Test
    void verifyProbesTheCollectionThroughMkcolAndPropfind() {
        WebDavSyncTarget target =
                new WebDavSyncTarget(config(true, 0, 0, "/turboism-backup", "", ""), diagnostics::add);
        target.verify();
        assertTrue(requests.contains("MKCOL /turboism-backup"), "verify must probe with MKCOL");
        assertTrue(
                requests.contains("PROPFIND /turboism-backup"),
                "an existing collection (405) must be confirmed with PROPFIND");
        assertTrue(putCalls.get() == 0, "verify must never upload");
    }

    @Test
    void verifyProbesEvenWhenTheTargetIsDisabled() {
        WebDavSyncTarget target =
                new WebDavSyncTarget(config(false, 0, 0, "/turboism-backup", "", ""), diagnostics::add);
        target.verify();
        assertFalse(requests.isEmpty(), "verify must be independent of the enabled flag");
    }

    @Test
    void verifyFailsClosedWithTheHttpStatusAndNeverLeaksCredentials() {
        statusOverride = path -> (path.startsWith("MKCOL ") || path.startsWith("PROPFIND ")) ? 401 : null;
        WebDavSyncTarget target =
                new WebDavSyncTarget(config(true, 0, 0, "/turboism-backup", "alice", "s3cret!"), diagnostics::add);
        final IllegalStateException failure = assertThrows(IllegalStateException.class, target::verify);
        assertTrue(failure.getMessage().contains("401"), "the status must be reported");
        assertTrue(!failure.getMessage().contains("s3cret!"), "credentials must never leak");
        for (String line : diagnostics) {
            assertTrue(!line.contains("s3cret!"), "credentials must never reach diagnostics");
        }
    }

    @Test
    void encodesEachUriSegmentWhenUploadingArtifacts() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/turbo ism/备份", "", ""), diagnostics::add);
        BackupArtifactHandle artifact = artifact("model 备份 #1?.cmo3");
        target.sync(List.of(artifact));
        assertTrue(
                rawRequests.contains("MKCOL /turbo%20ism/%E5%A4%87%E4%BB%BD"),
                "collection segments must be percent-encoded, got " + rawRequests);
        assertTrue(
                rawRequests.contains("PUT /turbo%20ism/%E5%A4%87%E4%BB%BD/model%20%E5%A4%87%E4%BB%BD%20%231%3F.cmo3"),
                "file name segments must be percent-encoded, got " + rawRequests);
        assertTrue(
                requests.contains("PUT /turbo ism/备份/model 备份 #1?.cmo3"),
                "the decoded resource name must be the original file name");
    }

    @Test
    void encodesLiteralPercentCharactersInArtifactNames() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/backup", "", ""), diagnostics::add);
        target.sync(List.of(artifact("rate 100%.cmo3")));
        assertTrue(
                rawRequests.contains("PUT /backup/rate%20100%25.cmo3"),
                "a literal percent must become %25, got " + rawRequests);
    }

    @Test
    void joinsBaseUrlPathAndCollectionWithoutDoubleSlashes() throws Exception {
        for (String suffix : List.of("/dav", "/dav/")) {
            final WebDavConfig base = new WebDavConfig(
                    true,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + suffix),
                    "",
                    "",
                    "/backup",
                    true,
                    0,
                    0,
                    10);
            new WebDavSyncTarget(base, diagnostics::add).sync(List.of(artifact("model_backup2026_08_08_1300.cmo3")));
            assertTrue(
                    rawRequests.contains("PUT /dav/backup/model_backup2026_08_08_1300.cmo3"),
                    "base suffix " + suffix + " must join without a double slash, got " + rawRequests);
        }
        assertTrue(
                rawRequests.stream().noneMatch(line -> line.contains("//")),
                "no request path may contain a double slash, got " + rawRequests);
    }

    @Test
    void ignoresQueryOnTheConfiguredRootUrlInsteadOfCorruptingThePath() throws Exception {
        final WebDavConfig base = new WebDavConfig(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/dav?tag=1"),
                "",
                "",
                "/backup",
                true,
                0,
                0,
                10);
        new WebDavSyncTarget(base, diagnostics::add).sync(List.of(artifact("model_backup2026_08_08_1301.cmo3")));
        assertTrue(
                rawRequests.contains("PUT /dav/backup/model_backup2026_08_08_1301.cmo3"),
                "a query on the root URL must not corrupt the request path, got " + rawRequests);
    }

    @Test
    void redirectsFailClosedAndNeverCarryCredentialsToAnotherOrigin() throws Exception {
        final List<String> redirectedRequests = new CopyOnWriteArrayList<>();
        final List<String> redirectedAuthHeaders = new CopyOnWriteArrayList<>();
        final HttpServer otherOrigin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            otherOrigin.createContext("/", exchange -> {
                redirectedRequests.add(exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getPath());
                redirectedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            });
            otherOrigin.start();
            redirectLocation = "http://127.0.0.1:" + otherOrigin.getAddress().getPort() + "/stolen";
            final WebDavSyncTarget target =
                    new WebDavSyncTarget(config(true, 0, 0, "/backup", "alice", "s3cret!"), diagnostics::add);
            assertThrows(
                    IllegalStateException.class,
                    () -> target.sync(List.of(artifact("model_backup2026_08_08_1302.cmo3"))),
                    "a redirect must fail closed rather than be followed");
            assertTrue(
                    redirectedRequests.isEmpty(),
                    "no request may be reissued to another origin, got " + redirectedRequests);
            assertTrue(redirectedAuthHeaders.isEmpty(), "credentials must never leave the configured origin");
            assertTrue(
                    diagnostics.stream().anyMatch(line -> line.contains("redirect")),
                    "a redirect must produce a diagnostic, got " + diagnostics);
        } finally {
            otherOrigin.stop(0);
        }
    }

    @Test
    void uploadRejectsMissingOrEmptyArtifacts() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/backup", "", ""), diagnostics::add);
        assertThrows(
                IllegalStateException.class,
                () -> target.upload(TestBackupArtifactHandle.of(temporary.resolve("missing.cmo3"))));
        Path empty = temporary.resolve("empty_backup2026_08_08_1206.cmo3");
        Files.writeString(empty, "");
        assertThrows(IllegalStateException.class, () -> target.upload(TestBackupArtifactHandle.of(empty)));
    }
}
