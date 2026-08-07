package dev.turboism.plugin.backup.webdav;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDavSyncTargetTest {

    @TempDir
    Path temporary;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> putBodies = new CopyOnWriteArrayList<>();
    private Function<String, Integer> statusOverride = path -> null;
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
        final String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + path);
        if ("PUT".equals(method)) {
            putCalls.incrementAndGet();
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
        final String password
    ) {
        return new WebDavConfig(
            enabled,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            username,
            password,
            remotePath,
            true,
            retryMax,
            retryBaseDelayMs,
            10
        );
    }

    private File artifact(final String name) throws IOException {
        final Path file = temporary.resolve(name);
        Files.writeString(file, "backup-content-" + name);
        return file.toFile();
    }

    @Test
    void uploadsThroughMkcolPropfindAndPutWithTheArtifactName() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/turboism-backup", "", ""), diagnostics::add);
        File artifact = artifact("model_backup2026_08_08_1200.cmo3");
        target.sync(List.of(artifact));
        assertTrue(requests.contains("MKCOL /turboism-backup"), "collection must be ensured with MKCOL");
        assertTrue(requests.contains("PROPFIND /turboism-backup"),
            "an existing collection (405) must be confirmed with PROPFIND");
        assertTrue(requests.contains("PUT /turboism-backup/model_backup2026_08_08_1200.cmo3"));
        assertEquals("backup-content-model_backup2026_08_08_1200.cmo3", putBodies.get(0));
        assertEquals(1, putCalls.get());
    }

    @Test
    void uploadsIntoTheRootCollectionWhenRemotePathIsRoot() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/", "", ""), diagnostics::add);
        File artifact = artifact("model_backup2026_08_08_1201.cmo3");
        target.sync(List.of(artifact));
        assertTrue(requests.contains("PUT /model_backup2026_08_08_1201.cmo3"));
    }

    @Test
    void retriesFiveHundredStatusWithBackoffThenSucceeds() throws Exception {
        // 500 once, then success on the second attempt
        statusOverride = path -> path.startsWith("PUT ") && putCalls.get() == 1 ? 500 : null;
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 2, 5, "/backup", "", ""), diagnostics::add);
        File artifact = artifact("model_backup2026_08_08_1202.cmo3");
        target.sync(List.of(artifact));
        assertEquals(2, putCalls.get(), "first PUT must fail with 500 and be retried");
        assertTrue(requests.contains("PUT /backup/model_backup2026_08_08_1202.cmo3"));
    }

    @Test
    void exhaustsRetriesAndFailsClosedWithoutCorruptingTheDiagnostics() throws Exception {
        statusOverride = path -> path.startsWith("PUT ") ? 503 : null;
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 2, 2, "/backup", "", ""), diagnostics::add);
        File artifact = artifact("model_backup2026_08_08_1203.cmo3");
        assertThrows(IllegalStateException.class, () -> target.sync(List.of(artifact)));
        assertEquals(3, putCalls.get(), "1 + retryMax attempts");
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("put-exhausted")),
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
        WebDavSyncTarget target = new WebDavSyncTarget(
            config(true, 0, 0, "/auth", "alice", "s3cret!"), diagnostics::add);
        File artifact = artifact("model_backup2026_08_08_1204.cmo3");
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
        File artifact = artifact("model_backup2026_08_08_1205.cmo3");
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
        assertEquals("/a/b/", WebDavConfig.normalizePath("a//b/"));
        assertEquals("/", WebDavConfig.normalizePath(""));
        assertEquals("/", WebDavConfig.normalizePath("/"));
        assertThrows(IllegalArgumentException.class, () -> WebDavConfig.normalizePath("../escape"));
        assertThrows(IllegalArgumentException.class, () -> WebDavConfig.normalizePath("/a/../../escape"));
        assertThrows(IllegalArgumentException.class, () -> new WebDavConfig(
            true, URI.create("ftp://host"), "", "", "/x", true, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new WebDavConfig(
            true, URI.create("http://user:pass@host/"), "", "", "/x", true, 0, 0, 10));
    }

    @Test
    void uploadRejectsMissingOrEmptyArtifacts() throws Exception {
        WebDavSyncTarget target = new WebDavSyncTarget(config(true, 0, 0, "/backup", "", ""), diagnostics::add);
        assertThrows(IllegalStateException.class, () -> target.upload(temporary.resolve("missing.cmo3").toFile()));
        Path empty = temporary.resolve("empty_backup2026_08_08_1206.cmo3");
        Files.writeString(empty, "");
        assertThrows(IllegalStateException.class, () -> target.upload(empty.toFile()));
    }
}
