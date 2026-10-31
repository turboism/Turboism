package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FrameworkUpdateDownloaderTest {
    @TempDir
    Path temporary;

    @Test
    void realHttpAuthenticatesDeduplicatesAndNeverDownloadsUnchangedJarOrWritesInstallation() throws Exception {
        try (Fixture f = new Fixture()) {
            final List<FrameworkUpdateDownloader.Progress> progress = new ArrayList<>();
            final var result = f.download(progress::add).get(5, TimeUnit.SECONDS);
            assertEquals(1, f.objectRequests.get()); // Agent + second path share one object.
            assertEquals(2, f.documentRequests.get());
            assertEquals(1, result.objects().size());
            assertEquals(
                    FrameworkDeltaPlanner.Action.REUSE,
                    result.plan().decisions().stream()
                            .filter(d -> d.entry().relativePath().contains("unchanged"))
                            .findFirst()
                            .orElseThrow()
                            .action());
            assertArrayEquals(f.payload, Files.readAllBytes(result.objects().get(f.hash)));
            assertArrayEquals(f.manifest, Files.readAllBytes(result.manifest()));
            assertArrayEquals(f.envelope, Files.readAllBytes(result.envelope()));
            assertArrayEquals(f.unchanged, Files.readAllBytes(f.install.resolve("graal/lib/unchanged.jar")));
            assertEquals("user", Files.readString(f.install.resolve("plugins/acp.jar")));
            assertFalse(Files.exists(f.install.resolve("turboism-agent.jar")));
            assertFalse(Files.exists(f.cache.resolve("STAGED")));
            assertTrue(progress.stream().allMatch(p -> p.received() <= p.total() && p.verified() <= p.received()));
            f.download(ignored -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, f.objectRequests.get());
            assertEquals(2, f.documentRequests.get()); // Full-file rehash, zero network cache reuse.
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"206", "200", "416", "changed-etag"})
    void resumeUsesStrongIfRangeAndSafelyRestartsWithoutAppend(final String mode) throws Exception {
        try (Fixture f = new Fixture()) {
            f.partial(1024, "\"v1\"");
            f.handler = exchange -> {
                final int request = f.objectRequests.get();
                if (request == 1) {
                    assertEquals("bytes=1024-", exchange.getRequestHeaders().getFirst("Range"));
                    assertEquals("\"v1\"", exchange.getRequestHeaders().getFirst("If-Range"));
                    if (mode.equals("416")) {
                        send(exchange, 416, new byte[0]);
                        return;
                    }
                    if (mode.equals("200")) {
                        send(exchange, 200, f.payload);
                        return;
                    }
                    exchange.getResponseHeaders()
                            .set("Content-Range", "bytes 1024-" + (f.payload.length - 1) + "/" + f.payload.length);
                    exchange.getResponseHeaders().set("ETag", mode.equals("changed-etag") ? "\"v2\"" : "\"v1\"");
                    send(exchange, 206, Arrays.copyOfRange(f.payload, 1024, f.payload.length));
                } else {
                    assertNull(exchange.getRequestHeaders().getFirst("Range"));
                    send(exchange, 200, f.payload);
                }
            };
            final List<FrameworkUpdateDownloader.Progress> progress = new ArrayList<>();
            final var result = f.download(progress::add).get(5, TimeUnit.SECONDS);
            assertArrayEquals(f.payload, Files.readAllBytes(result.objects().get(f.hash)));
            assertEquals(mode.equals("206") ? 1 : 2, f.objectRequests.get());
            assertEquals(
                    !mode.equals("206"),
                    progress.stream().anyMatch(p -> p.phase() == FrameworkUpdateDownloader.Phase.RESTARTING));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"redirect", "encoding", "length", "hash", "range", "weak-etag", "range-end", "range-total"})
    void unsafeHttpNeverProducesVerifiedObject(final String mode) throws Exception {
        try (Fixture f = new Fixture()) {
            if (mode.startsWith("range")) f.partial(1024, "\"v1\"");
            f.handler = exchange -> {
                switch (mode) {
                    case "redirect" -> {
                        exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
                        send(exchange, 302, new byte[0]);
                    }
                    case "encoding" -> {
                        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                        send(exchange, 200, f.payload);
                    }
                    case "length" -> send(exchange, 200, new byte[f.payload.length + 1]);
                    case "hash" -> {
                        final byte[] bad = f.payload.clone();
                        bad[0] ^= 1;
                        send(exchange, 200, bad);
                    }
                    case "weak-etag" -> {
                        exchange.getResponseHeaders().set("ETag", "W/\"v1\"");
                        send(exchange, 206, f.payload);
                    }
                    default -> {
                        exchange.getResponseHeaders().set("ETag", "\"v1\"");
                        final int start = mode.equals("range") ? 0 : 1024;
                        final int end = mode.equals("range-end") ? f.payload.length - 2 : f.payload.length - 1;
                        final int total = mode.equals("range-total") ? f.payload.length + 1 : f.payload.length;
                        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + total);
                        send(exchange, 206, Arrays.copyOfRange(f.payload, 1024, f.payload.length));
                    }
                }
            };
            assertThrows(
                    ExecutionException.class, () -> f.download(ignored -> {}).get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(f.cache.resolve("object-" + f.hash + ".verified")));
            assertFalse(Files.exists(f.cache.resolve("STAGED")));
            assertFalse(Files.exists(f.install.resolve("turboism-agent.jar")));
            assertEquals(1, f.objectRequests.get());
        }
    }

    @Test
    void cancellationRetainsUnverifiedPartialThenNewInstanceResumesAndRehashes() throws Exception {
        try (Fixture f = new Fixture()) {
            final CountDownLatch received = new CountDownLatch(1);
            final CountDownLatch releaseServer = new CountDownLatch(1);
            f.handler = exchange -> {
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                exchange.sendResponseHeaders(200, f.payload.length);
                exchange.getResponseBody().write(f.payload, 0, 4096);
                exchange.getResponseBody().flush();
                try {
                    releaseServer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            };
            final CompletableFuture<?> first = f.download(p -> {
                if (p.sha256().equals(f.hash) && p.received() >= 4096) {
                    received.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(first.cancel(true));
            releaseServer.countDown();
            f.io.submit(() -> {}).get(5, TimeUnit.SECONDS); // Worker has closed cache lock, no sleep/poll.
            final Path part = f.cache.resolve("object-" + f.hash + ".part");
            assertEquals(4096, Files.size(part));
            assertFalse(Files.exists(f.cache.resolve("object-" + f.hash + ".verified")));
            f.handler = exchange -> {
                assertEquals("bytes=4096-", exchange.getRequestHeaders().getFirst("Range"));
                assertEquals("\"v1\"", exchange.getRequestHeaders().getFirst("If-Range"));
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                exchange.getResponseHeaders()
                        .set("Content-Range", "bytes 4096-" + (f.payload.length - 1) + "/" + f.payload.length);
                send(exchange, 206, Arrays.copyOfRange(f.payload, 4096, f.payload.length));
            };
            final var result = f.download(ignored -> {}).get(5, TimeUnit.SECONDS);
            assertArrayEquals(f.payload, Files.readAllBytes(result.objects().get(f.hash)));
            assertTrue(first.isCancelled());
        }
    }

    @Test
    void signatureFailureClosureMismatchAndMissingInstallationEvidenceStopBeforeObjectRequest() throws Exception {
        for (final String mode : List.of("signature", "closure", "evidence", "context", "replay", "compatibility")) {
            try (Fixture f = new Fixture()) {
                if (mode.equals("signature")) {
                    final var tree = UpdateFileSupport.JSON.readTree(f.envelope);
                    final byte[] signature =
                            Base64.getDecoder().decode(tree.get("signature").asText());
                    signature[0] ^= 1;
                    ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
                            .put("signature", Base64.getEncoder().encodeToString(signature));
                    f.envelope = UpdateFileSupport.JSON.writeValueAsBytes(tree);
                    f.candidate = f.candidate(f.closureHash);
                }
                if (mode.equals("closure")) f.candidate = f.candidate("0".repeat(64));
                if (mode.equals("evidence")) f.context = f.context(Map.of());
                if (mode.equals("replay") || mode.equals("compatibility")) {
                    final var c = f.context;
                    f.context = new UpdateVerificationContext(
                            c.expectedVersion(),
                            c.expectedChannel(),
                            c.expectedBuildNumber(),
                            c.expectedSourceRevision(),
                            c.currentUpdaterVersion(),
                            mode.equals("compatibility") ? "linux-x64" : c.supportedPlatform(),
                            mode.equals("replay")
                                    ? InstalledBuild.of("2.0.0", OptionalLong.of(50), "2.0.0")
                                    : c.installedBuild(),
                            c.installedFrameworkHashes());
                }
                if (mode.equals("context")) {
                    final var c = f.context;
                    f.context = new UpdateVerificationContext(
                            c.expectedVersion(),
                            c.expectedChannel(),
                            51,
                            c.expectedSourceRevision(),
                            c.currentUpdaterVersion(),
                            c.supportedPlatform(),
                            c.installedBuild(),
                            c.installedFrameworkHashes());
                    assertThrows(IllegalArgumentException.class, () -> f.download(ignored -> {}));
                } else {
                    assertThrows(
                            ExecutionException.class,
                            () -> f.download(ignored -> {}).get(5, TimeUnit.SECONDS),
                            mode);
                }
                assertEquals(0, f.objectRequests.get(), mode);
            }
        }
    }

    @Test
    void corruptVerifiedCacheIsRehashedAndReplacedRatherThanTrustedByName() throws Exception {
        try (Fixture f = new Fixture()) {
            final var first = f.download(ignored -> {}).get(5, TimeUnit.SECONDS);
            f.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            final byte[] bad = f.payload.clone();
            bad[0] ^= 1;
            Files.write(first.objects().get(f.hash), bad);
            f.download(ignored -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(2, f.objectRequests.get());
            assertArrayEquals(f.payload, Files.readAllBytes(first.objects().get(f.hash)));
        }
    }

    @Test
    void cacheLockLinksUnknownOwnershipAndInstallationOverlapFailClosed() throws Exception {
        try (Fixture f = new Fixture()) {
            try (FrameworkUpdateCache cache = new FrameworkUpdateCache(f.cache)) {
                assertThrows(
                        ExecutionException.class,
                        () -> f.download(ignored -> {}).get(5, TimeUnit.SECONDS));
                final Path part = cache.path("object", f.hash, "part");
                Files.createSymbolicLink(part, f.install.resolve("plugins/acp.jar"));
            }
            assertThrows(
                    ExecutionException.class, () -> f.download(ignored -> {}).get(5, TimeUnit.SECONDS));
            Files.delete(f.cache.resolve("object-" + f.hash + ".part"));
            Files.createLink(f.cache.resolve("object-" + f.hash + ".part"), f.install.resolve("plugins/acp.jar"));
            assertThrows(
                    ExecutionException.class, () -> f.download(ignored -> {}).get(5, TimeUnit.SECONDS));
            assertEquals("user", Files.readString(f.install.resolve("plugins/acp.jar")));
            final Path unknown = temporary.resolve("unknown");
            Files.createDirectory(unknown);
            assertThrows(IOException.class, () -> new FrameworkUpdateCache(unknown));
            final Path file = temporary.resolve("not-directory");
            Files.writeString(file, "user");
            assertThrows(IOException.class, () -> new FrameworkUpdateCache(file.resolve("cache")));
            final var overlapping =
                    new FrameworkUpdateDownloader(f.install.resolve("cache"), f.source, f.verifier, f.io, f.timer);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> overlapping.download(f.candidate, f.context, f.install, ignored -> {}));
        }
    }

    @Test
    void defaultSourceDisabledAndConfiguredOriginCannotBeRelaxedByFeedOrPort() throws Exception {
        for (final String uri : List.of(
                "http://api.turboism.dev/a/",
                "https://api.turboism.dev:443/a/",
                "https://user@api.turboism.dev/a/",
                "https://api.turboism.dev/a/?q=1",
                "https://evil.invalid/a/",
                "https://api.turboism.dev/a/../b/")) {
            assertThrows(IllegalArgumentException.class, () -> FrameworkUpdateSource.configured(URI.create(uri)));
        }
        try (Fixture f = new Fixture()) {
            final var disabled =
                    new FrameworkUpdateDownloader(f.cache, FrameworkUpdateSource.disabled(), f.verifier, f.io, f.timer);
            assertThrows(
                    ExecutionException.class,
                    () -> disabled.download(f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            assertEquals(0, f.documentRequests.get());
        }
    }

    @Test
    void partialWithoutStrongValidatorAndCompleteButCorruptPartialAreRehashedOrRestarted() throws Exception {
        for (final String tag : List.of("W/\"v1\"", "\"v1\"")) {
            try (Fixture f = new Fixture()) {
                f.partial(tag.startsWith("W/") ? 1024 : f.payload.length, tag);
                if (!tag.startsWith("W/")) {
                    final Path part = f.cache.resolve("object-" + f.hash + ".part");
                    final byte[] bad = f.payload.clone();
                    bad[0] ^= 1;
                    Files.write(part, bad);
                }
                f.handler = exchange -> {
                    assertNull(exchange.getRequestHeaders().getFirst("Range"));
                    send(exchange, 200, f.payload);
                };
                final var result = f.download(ignored -> {}).get(5, TimeUnit.SECONDS);
                assertArrayEquals(f.payload, Files.readAllBytes(result.objects().get(f.hash)));
                assertEquals(1, f.objectRequests.get());
            }
        }
    }

    @Test
    void untrustedDocumentLengthsAndDigestsFailBeforePayloadAndParsing() throws Exception {
        for (final String mode : List.of("length", "digest", "redirect", "encoding")) {
            try (Fixture f = new Fixture()) {
                f.documentHandler = exchange -> {
                    switch (mode) {
                        case "length" -> {
                            exchange.sendResponseHeaders(200, UpdateManifest.MAX_MANIFEST_BYTES + 1L);
                            exchange.close();
                        }
                        case "digest" -> {
                            final byte[] bad = f.manifest.clone();
                            bad[0] ^= 1;
                            send(exchange, 200, bad);
                        }
                        case "redirect" -> {
                            exchange.getResponseHeaders().set("Location", "https://api.turboism.dev/forbidden");
                            send(exchange, 302, new byte[0]);
                        }
                        default -> {
                            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                            send(exchange, 200, f.manifest);
                        }
                    }
                };
                assertThrows(
                        ExecutionException.class,
                        () -> f.download(ignored -> {}).get(5, TimeUnit.SECONDS));
                assertEquals(0, f.objectRequests.get());
                assertFalse(Files.exists(f.cache.resolve("document-" + sha(f.manifest) + ".verified")));
            }
        }
    }

    @Test
    void shortReadRetainsOnlyPartialAndSubsequentCallCanResume() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handler = exchange -> {
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                exchange.sendResponseHeaders(200, f.payload.length);
                exchange.getResponseBody().write(f.payload, 0, 1024);
                exchange.close();
            };
            assertThrows(
                    ExecutionException.class, () -> f.download(ignored -> {}).get(8, TimeUnit.SECONDS));
            assertEquals(1024, Files.size(f.cache.resolve("object-" + f.hash + ".part")));
            assertFalse(Files.exists(f.cache.resolve("object-" + f.hash + ".verified")));
            f.handler = exchange -> {
                assertEquals("bytes=1024-", exchange.getRequestHeaders().getFirst("Range"));
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                exchange.getResponseHeaders()
                        .set("Content-Range", "bytes 1024-" + (f.payload.length - 1) + "/" + f.payload.length);
                send(exchange, 206, Arrays.copyOfRange(f.payload, 1024, f.payload.length));
            };
            f.download(ignored -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(5, f.objectRequests.get());
        }
    }

    @Test
    void totalDeadlineAndCancellationOfBlockedResponseCannotCompleteSessionLater() throws Exception {
        for (final boolean timeout : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                final CountDownLatch entered = new CountDownLatch(1);
                final CountDownLatch release = new CountDownLatch(1);
                f.handler = exchange -> {
                    exchange.getResponseHeaders().set("ETag", "\"v1\"");
                    exchange.sendResponseHeaders(200, f.payload.length);
                    exchange.getResponseBody().flush();
                    entered.countDown();
                    try {
                        release.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                };
                final var downloader = new FrameworkUpdateDownloader(
                        f.cache, f.source, f.verifier, f.io, f.timer, Duration.ofSeconds(2));
                final var future = downloader.download(f.candidate, f.context, f.install, ignored -> {});
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (timeout) assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
                else assertTrue(future.cancel(true));
                try {
                    f.io.submit(() -> {}).get(7, TimeUnit.SECONDS);
                    assertFalse(Files.exists(f.cache.resolve("object-" + f.hash + ".verified")));
                    assertTrue(future.isCompletedExceptionally());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void resourceBoundsPrivatePermissionsAndUnsafeAncestorsAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            try (FrameworkUpdateCache cache = new FrameworkUpdateCache(f.cache)) {
                assertThrows(IOException.class, () -> cache.preflight(1024L * 1024 * 1024 + 1));
                final Path part = cache.path("object", f.hash, "part");
                cache.create(part);
                makeReadableByOthers(part);
                assertThrows(IOException.class, () -> cache.present(part));
            }
            final Path alias = temporary.resolve("installation-alias");
            Files.createSymbolicLink(alias, f.install);
            final Path fresh = temporary.resolve("fresh-cache");
            final var downloader = new FrameworkUpdateDownloader(fresh, f.source, f.verifier, f.io, f.timer);
            assertThrows(
                    ExecutionException.class,
                    () -> downloader
                            .download(f.candidate, f.context, alias, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(fresh));
            final Path parentAlias = temporary.resolve("cache-parent-alias");
            Files.createSymbolicLink(parentAlias, f.cache.getParent());
            assertThrows(IOException.class, () -> new FrameworkUpdateCache(parentAlias.resolve("other-cache")));
        }
    }

    @Test
    void persistentStageRecoversOnNewInstanceWithFreshAuthorityAndRealAggregateProgress() throws Exception {
        try (Fixture f = new Fixture()) {
            final List<FrameworkUpdateDownloader.Progress> updates = new ArrayList<>();
            final var first = f.downloader()
                    .stage(f.candidate, f.context, f.install, updates::add)
                    .get(5, TimeUnit.SECONDS);
            final String id = first.staged().orElseThrow().id();
            assertTrue(first.staged().orElseThrow().directoryDurabilityConfirmed());
            final Path record = f.cache.resolve("task-" + id + ".json");
            final String text = Files.readString(record);
            assertFalse(text.contains(f.install.toString()));
            assertFalse(text.contains("Action"));
            assertEquals(
                    "STAGED",
                    UpdateFileSupport.JSON.readTree(text).path("state").asText());
            final var last = updates.get(updates.size() - 1);
            assertEquals(f.payload.length * 2L + f.unchanged.length, last.taskTotal());
            assertEquals(last.taskTotal(), last.taskVerified());
            final int requests = f.objectRequests.get() + f.documentRequests.get();
            final var recovered = f.downloader()
                    .recover(id, f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            assertEquals(id, recovered.staged().orElseThrow().id());
            f.downloader()
                    .recover(id, f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            assertEquals(requests, f.objectRequests.get() + f.documentRequests.get());
            assertFalse(Files.exists(f.install.resolve("turboism-agent.jar")));
            assertEquals("user", Files.readString(f.install.resolve("plugins/acp.jar")));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "manifest",
                "object",
                "missing",
                "installed",
                "installed-missing",
                "record",
                "path",
                "duplicate",
                "evidence",
                "identity",
                "root"
            })
    void recoveryNeverTrustsStatusPlanPathsOrOldAuthority(final String mode) throws Exception {
        try (Fixture f = new Fixture()) {
            final var staged = f.downloader()
                    .stage(f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            final String id = staged.staged().orElseThrow().id();
            final Path record = f.cache.resolve("task-" + id + ".json");
            switch (mode) {
                case "manifest" -> Files.write(staged.manifest(), new byte[f.manifest.length]);
                case "object" -> Files.write(staged.objects().get(f.hash), new byte[f.payload.length]);
                case "missing" -> Files.delete(staged.objects().get(f.hash));
                case "installed" ->
                    Files.write(f.install.resolve("graal/lib/unchanged.jar"), new byte[f.unchanged.length]);
                case "installed-missing" -> Files.delete(f.install.resolve("graal/lib/unchanged.jar"));
                case "record" -> Files.writeString(record, "{\"state\":\"STAGED\"}");
                case "record-link" -> {
                    Files.delete(record);
                    Files.createSymbolicLink(record, f.install.resolve("plugins/acp.jar"));
                }
                case "record-hardlink" -> {
                    Files.delete(record);
                    Files.createLink(record, f.install.resolve("plugins/acp.jar"));
                }
                case "duplicate" ->
                    Files.writeString(
                            record,
                            Files.readString(record)
                                    .replace("\"state\":\"STAGED\"", "\"state\":\"STAGED\",\"state\":\"STAGED\""));
                case "path" -> {
                    final var tree = (com.fasterxml.jackson.databind.node.ObjectNode)
                            UpdateFileSupport.JSON.readTree(record.toFile());
                    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("objects")).put("../../user", 1);
                    Files.write(record, UpdateFileSupport.JSON.writeValueAsBytes(tree));
                }
                case "evidence" -> f.context = f.context(Map.of());
                case "identity" -> {
                    final var c = f.context;
                    f.context = new UpdateVerificationContext(
                            c.expectedVersion(),
                            c.expectedChannel(),
                            c.expectedBuildNumber(),
                            c.expectedSourceRevision(),
                            c.currentUpdaterVersion(),
                            c.supportedPlatform(),
                            InstalledBuild.of("1.0.0", OptionalLong.of(48), "1.0.0"),
                            c.installedFrameworkHashes());
                }
                default -> {}
            }
            final int requests = f.objectRequests.get() + f.documentRequests.get();
            if (List.of("evidence", "identity", "root", "record-link", "record-hardlink")
                    .contains(mode)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> f.downloader()
                                .recover(
                                        id,
                                        f.candidate,
                                        f.context,
                                        mode.equals("root") ? temporary.resolve("different-install") : f.install,
                                        ignored -> {}));
            } else {
                assertThrows(
                        ExecutionException.class,
                        () -> f.downloader()
                                .recover(id, f.candidate, f.context, f.install, ignored -> {})
                                .get(5, TimeUnit.SECONDS));
            }
            assertEquals(requests, f.objectRequests.get() + f.documentRequests.get());
            assertEquals("user", Files.readString(f.install.resolve("plugins/acp.jar")));
        }
    }

    @Test
    void incompleteDownloadRecordCanBeRetriedButCacheOnlyRecoveryDoesNotFetchMissingObjects() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handler = exchange -> send(exchange, 403, new byte[0]);
            assertThrows(
                    ExecutionException.class,
                    () -> f.downloader()
                            .stage(f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            final String id =
                    new FrameworkUpdateTaskStore(f.candidate.incremental().orElseThrow(), f.context, f.install).id;
            assertEquals(
                    "DOWNLOADING",
                    UpdateFileSupport.JSON
                            .readTree(f.cache.resolve("task-" + id + ".json").toFile())
                            .path("state")
                            .asText());
            assertThrows(
                    ExecutionException.class,
                    () -> f.downloader()
                            .recover(id, f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            assertEquals(1, f.objectRequests.get());
            f.handler = exchange -> send(exchange, 200, f.payload);
            assertTrue(f.downloader()
                    .stage(f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS)
                    .staged()
                    .isPresent());
            assertEquals(2, f.objectRequests.get());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "before-write",
                "after-write",
                "before-force",
                "after-force",
                "before-rename",
                "after-rename",
                "before-directory-force",
                "after-directory-force"
            })
    void processFaultAtRecordBoundaryRequiresFreshRevalidation(final String boundary) throws Exception {
        try (Fixture f = new Fixture()) {
            final var saved = f.download(ignored -> {}).get(5, TimeUnit.SECONDS);
            final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
            final var broken = f.downloader(FrameworkUpdateCache.DEFAULT_QUOTA, point -> {
                if ((point.startsWith(boundary + ":task-") || point.equals(boundary))
                        && fired.compareAndSet(false, true)) {
                    throw new IOException("Injected process interruption");
                }
            });
            assertThrows(
                    ExecutionException.class,
                    () -> broken.stage(f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            assertTrue(fired.get());
            final var retried = f.downloader()
                    .stage(f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            f.downloader()
                    .recover(retried.staged().orElseThrow().id(), f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            assertArrayEquals(f.payload, Files.readAllBytes(saved.objects().get(f.hash)));
            assertEquals(1, f.objectRequests.get());
        }
    }

    @Test
    void cancellationBeforeCommitWinsAndDuringCommitCannotLieAboutPublishedState() throws Exception {
        for (final boolean afterClaim : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                final CountDownLatch entered = new CountDownLatch(1);
                final CountDownLatch release = new CountDownLatch(1);
                final var downloader = f.downloader(FrameworkUpdateCache.DEFAULT_QUOTA, point -> {
                    final boolean target = afterClaim
                            ? point.startsWith("before-rename:task-") && stagedTemporary(f.cache)
                            : point.equals("before-commit-claim");
                    if (target) {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                final var future = downloader.stage(f.candidate, f.context, f.install, ignored -> {});
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(!afterClaim, future.cancel(true));
                release.countDown();
                f.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
                final String id =
                        new FrameworkUpdateTaskStore(f.candidate.incremental().orElseThrow(), f.context, f.install).id;
                final String state = UpdateFileSupport.JSON
                        .readTree(f.cache.resolve("task-" + id + ".json").toFile())
                        .path("state")
                        .asText();
                assertEquals(afterClaim ? "STAGED" : "DOWNLOADING", state);
                if (afterClaim)
                    assertTrue(future.get(5, TimeUnit.SECONDS).staged().isPresent());
                else assertTrue(future.isCancelled());
            }
        }
    }

    @Test
    void postRenameFailureReportsVisiblePublicationWithUnconfirmedDurability() throws Exception {
        try (Fixture f = new Fixture()) {
            final var downloader = f.downloader(FrameworkUpdateCache.DEFAULT_QUOTA, point -> {
                if (point.startsWith("after-rename:task-")) {
                    final Path record;
                    try (var files = Files.list(f.cache)) {
                        record = files.filter(p -> p.toString().endsWith(".json"))
                                .findFirst()
                                .orElseThrow();
                    }
                    if (Files.readString(record).contains("\"state\":\"STAGED\""))
                        throw new IOException("Crash after commit rename");
                }
            });
            final var future = downloader.stage(f.candidate, f.context, f.install, ignored -> {});
            final var result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.staged().orElseThrow().directoryDurabilityConfirmed());
            assertFalse(future.cancel(true));
            f.downloader()
                    .recover(result.staged().orElseThrow().id(), f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void quotaPreservesSharedAndUnknownFilesAndCachedObjectsNeedNoSecondCopyReservation() throws Exception {
        try (Fixture f = new Fixture()) {
            final var first = f.downloader()
                    .stage(f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            final String firstId = first.staged().orElseThrow().id();
            // A second independently bound installation shares immutable document/object bytes.
            final Path other = temporary.resolve("other-install");
            Files.createDirectories(other.resolve("graal/lib"));
            Files.write(other.resolve("graal/lib/unchanged.jar"), f.unchanged);
            final var second = f.downloader()
                    .stage(f.candidate, f.context, other, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, f.objectRequests.get());
            final long tight;
            try (var files = Files.list(f.cache)) {
                tight = files.mapToLong(p -> {
                                    try {
                                        return Files.size(p);
                                    } catch (IOException e) {
                                        throw new java.io.UncheckedIOException(e);
                                    }
                                })
                                .sum()
                        + 8000;
            }
            f.downloader(tight, ignored -> {})
                    .recover(firstId, f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            final Path outside = temporary.resolve("outside-private-file");
            createPrivateFile(outside);
            Files.writeString(outside, "user");
            try (FrameworkUpdateCache cache = new FrameworkUpdateCache(f.cache)) {
                assertThrows(IOException.class, () -> cache.remove(outside));
                assertThrows(IOException.class, () -> cache.read(outside, 100));
                assertThrows(IOException.class, () -> cache.create(temporary.resolve("outside-created")));
            }
            assertEquals("user", Files.readString(outside));
            final Path unknown = f.cache.resolve("user.keep");
            createPrivateFile(unknown);
            Files.writeString(unknown, "user");
            f.downloader().discard(firstId).get(5, TimeUnit.SECONDS);
            try (FrameworkUpdateCache cache = new FrameworkUpdateCache(f.cache, tight, ignored -> {})) {
                final String junkHash = "b".repeat(64);
                final Path junk = cache.path("object", junkHash, "part");
                cache.create(junk);
                Files.write(junk, new byte[20000]);
                cache.preflight(0);
                assertFalse(Files.exists(junk));
                assertTrue(Files.exists(first.objects().get(f.hash))); // Other STAGED still references shared hash.
                assertThrows(IOException.class, () -> cache.preflight(tight));
            }
            assertEquals("user", Files.readString(unknown));
            f.downloader()
                    .recover(second.staged().orElseThrow().id(), f.candidate, f.context, other, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void boundedTransientRetriesRespectExtentAndNeverRetryIntegrityErrors() throws Exception {
        for (final int status : List.of(408, 429, 500, 502, 503, 504)) {
            try (Fixture f = new Fixture()) {
                f.handler = exchange -> {
                    if (f.objectRequests.get() < 3) {
                        exchange.getResponseHeaders().set("Retry-After", "0");
                        send(exchange, status, new byte[0]);
                    } else send(exchange, 200, f.payload);
                };
                final List<FrameworkUpdateDownloader.Progress> updates = new ArrayList<>();
                f.downloader()
                        .stage(f.candidate, f.context, f.install, updates::add)
                        .get(5, TimeUnit.SECONDS);
                assertEquals(3, f.objectRequests.get());
                assertEquals(
                        2,
                        updates.stream()
                                .filter(p -> p.phase() == FrameworkUpdateDownloader.Phase.RETRYING)
                                .count());
                assertTrue(updates.stream().allMatch(p -> p.taskVerified() <= p.taskTotal()));
            }
        }
        try (Fixture f = new Fixture()) {
            f.handler = exchange -> send(exchange, 503, new byte[0]);
            assertThrows(
                    ExecutionException.class,
                    () -> f.downloader()
                            .stage(f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            assertEquals(3, f.objectRequests.get());
        }
    }

    @Test
    void cancellationDuringBackoffCallbackFailureAndExecutorRejectionReleaseWriter() throws Exception {
        try (Fixture f = new Fixture()) {
            final CountDownLatch retry = new CountDownLatch(1);
            f.handler = exchange -> {
                exchange.getResponseHeaders().set("Retry-After", "99999999");
                send(exchange, 429, new byte[0]);
            };
            final var future = f.downloader().stage(f.candidate, f.context, f.install, p -> {
                if (p.phase() == FrameworkUpdateDownloader.Phase.RETRYING) retry.countDown();
            });
            assertTrue(retry.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            f.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, f.objectRequests.get());
            f.handler = exchange -> send(exchange, 200, f.payload);
            assertThrows(
                    ExecutionException.class,
                    () -> f.downloader()
                            .stage(f.candidate, f.context, f.install, p -> {
                                throw new IllegalStateException("callback failed");
                            })
                            .get(5, TimeUnit.SECONDS));
            assertTrue(f.downloader()
                    .stage(f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS)
                    .staged()
                    .isPresent());
            f.timer.shutdown();
            assertThrows(
                    ExecutionException.class,
                    () -> f.downloader()
                            .stage(f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            try (FrameworkUpdateCache cache = new FrameworkUpdateCache(f.cache)) {
                cache.preflight(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "before-write",
                "after-write",
                "before-force",
                "after-force",
                "before-rename",
                "after-rename",
                "before-directory-force",
                "after-directory-force"
            })
    void finalPublicationFaultsNeverTurnCancellationOrPartialDataIntoReadiness(final String boundary) throws Exception {
        try (Fixture f = new Fixture()) {
            final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
            final var downloader = f.downloader(FrameworkUpdateCache.DEFAULT_QUOTA, point -> {
                if (point.equals("before-stage-prepare")) armed.set(true);
                if (armed.get()
                        && (point.startsWith(boundary + ":task-") || point.equals(boundary))
                        && fired.compareAndSet(false, true)) {
                    throw new IOException("Injected final publication interruption");
                }
            });
            final var future = downloader.stage(f.candidate, f.context, f.install, ignored -> {});
            final boolean visible = List.of("after-rename", "before-directory-force", "after-directory-force")
                    .contains(boundary);
            if (visible) {
                final var result = future.get(5, TimeUnit.SECONDS);
                assertFalse(result.staged().orElseThrow().directoryDurabilityConfirmed());
                assertFalse(future.cancel(true));
            } else assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(fired.get());
            final String id =
                    new FrameworkUpdateTaskStore(f.candidate.incremental().orElseThrow(), f.context, f.install).id;
            assertEquals(
                    visible ? "STAGED" : "DOWNLOADING",
                    UpdateFileSupport.JSON
                            .readTree(f.cache.resolve("task-" + id + ".json").toFile())
                            .path("state")
                            .asText());
            assertTrue(f.downloader()
                    .recover(id, f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS)
                    .staged()
                    .isPresent());
            assertEquals(1, f.objectRequests.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-force", "after-force", "before-rename", "after-rename"})
    void resourcePublicationFaultRetainsOnlyReverifiableCacheBytes(final String boundary) throws Exception {
        try (Fixture f = new Fixture()) {
            final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
            final var downloader = f.downloader(FrameworkUpdateCache.DEFAULT_QUOTA, point -> {
                if (point.startsWith(boundary + ":object-" + f.hash)
                        && !point.endsWith(".etag")
                        && fired.compareAndSet(false, true)) {
                    throw new IOException("Resource publication interrupted");
                }
            });
            assertThrows(
                    ExecutionException.class,
                    () -> downloader
                            .stage(f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            assertTrue(fired.get());
            final var result = f.downloader()
                    .stage(f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.staged().isPresent());
            assertArrayEquals(f.payload, Files.readAllBytes(result.objects().get(f.hash)));
            assertEquals(1, f.objectRequests.get());
        }
    }

    @Test
    void allReusePublishesTruthfulFullTaskProgressAndNoObjectRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.write(f.install.resolve("turboism-agent.jar"), f.payload);
            Files.write(f.install.resolve("graal/lib/copy.jar"), f.payload);
            f.context = f.context(Map.of(
                    "turboism-agent.jar",
                    f.hash,
                    "graal/lib/copy.jar",
                    f.hash,
                    "graal/lib/unchanged.jar",
                    f.unchangedHash));
            final List<FrameworkUpdateDownloader.Progress> updates = new ArrayList<>();
            final var result = f.downloader()
                    .stage(f.candidate, f.context, f.install, updates::add)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(0, f.objectRequests.get());
            assertTrue(result.objects().isEmpty());
            final var last = updates.get(updates.size() - 1);
            assertEquals(f.payload.length * 2L + f.unchanged.length, last.taskTotal());
            assertEquals(last.taskTotal(), last.taskVerified());
        }
    }

    @Test
    void installationOrObjectChangingDuringPreparationCannotPublishStaged() throws Exception {
        for (final String mode : List.of("installation", "object", "manifest", "envelope")) {
            try (Fixture f = new Fixture()) {
                final var downloader = f.downloader(FrameworkUpdateCache.DEFAULT_QUOTA, point -> {
                    if (point.equals("before-commit-claim")) {
                        switch (mode) {
                            case "installation" -> Files.delete(f.install.resolve("graal/lib/unchanged.jar"));
                            case "manifest" ->
                                Files.write(
                                        f.cache.resolve(
                                                "document-" + FrameworkUpdateTaskStore.hash(f.manifest) + ".verified"),
                                        new byte[f.manifest.length]);
                            case "envelope" ->
                                Files.write(
                                        f.cache.resolve(
                                                "document-" + FrameworkUpdateTaskStore.hash(f.envelope) + ".verified"),
                                        new byte[f.envelope.length]);
                            default ->
                                Files.write(
                                        f.cache.resolve("object-" + f.hash + ".verified"), new byte[f.payload.length]);
                        }
                    }
                });
                assertThrows(
                        ExecutionException.class,
                        () -> downloader
                                .stage(f.candidate, f.context, f.install, ignored -> {})
                                .get(5, TimeUnit.SECONDS));
                final String id =
                        new FrameworkUpdateTaskStore(f.candidate.incremental().orElseThrow(), f.context, f.install).id;
                assertEquals(
                        "DOWNLOADING",
                        UpdateFileSupport.JSON
                                .readTree(
                                        f.cache.resolve("task-" + id + ".json").toFile())
                                .path("state")
                                .asText());
                assertEquals("user", Files.readString(f.install.resolve("plugins/acp.jar")));
            }
        }
    }

    @Test
    void timerShutdownDuringBackoffAndIoRejectionLeaveNoWriterOrStagedLeak() throws Exception {
        try (Fixture f = new Fixture()) {
            final CountDownLatch retry = new CountDownLatch(1);
            f.handler = exchange -> {
                exchange.getResponseHeaders().set("Retry-After", "99999999");
                send(exchange, 503, new byte[0]);
            };
            final var future = f.downloader().stage(f.candidate, f.context, f.install, p -> {
                if (p.phase() == FrameworkUpdateDownloader.Phase.RETRYING) retry.countDown();
            });
            assertTrue(retry.await(5, TimeUnit.SECONDS));
            f.timer.shutdownNow();
            assertThrows(ExecutionException.class, () -> future.get(4, TimeUnit.SECONDS));
            f.io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            try (FrameworkUpdateCache cache = new FrameworkUpdateCache(f.cache)) {
                cache.preflight(0);
            }
        }
        try (Fixture f = new Fixture()) {
            f.io.shutdown();
            assertThrows(
                    ExecutionException.class,
                    () -> f.downloader()
                            .stage(f.candidate, f.context, f.install, ignored -> {})
                            .get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(f.cache));
        }
    }

    @Test
    void freshChangedExpectedReleaseCannotRecoverAnOldTask() throws Exception {
        try (Fixture f = new Fixture()) {
            final var saved = f.downloader()
                    .stage(f.candidate, f.context, f.install, ignored -> {})
                    .get(5, TimeUnit.SECONDS);
            final var old = f.candidate.incremental().orElseThrow();
            final var next = new UpdateIncrementalDiscovery(
                    old.version(),
                    old.channel(),
                    old.buildNumber(),
                    "b".repeat(40),
                    old.manifest(),
                    old.envelope(),
                    old.objectsSha256());
            final var candidate = new UpdateDiscovery.Candidate(
                    f.candidate.version(),
                    f.candidate.versionText(),
                    f.candidate.buildNumber(),
                    Optional.empty(),
                    List.of(),
                    Optional.of(next.sourceRevision()),
                    Optional.of(next));
            final var c = f.context;
            final var context = new UpdateVerificationContext(
                    c.expectedVersion(),
                    c.expectedChannel(),
                    c.expectedBuildNumber(),
                    next.sourceRevision(),
                    c.currentUpdaterVersion(),
                    c.supportedPlatform(),
                    c.installedBuild(),
                    c.installedFrameworkHashes());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.downloader()
                            .recover(saved.staged().orElseThrow().id(), candidate, context, f.install, ignored -> {}));
            assertEquals(1, f.objectRequests.get());
        }
    }

    private static boolean stagedTemporary(final Path cache) throws IOException {
        try (var files = Files.list(cache)) {
            for (final Path file :
                    files.filter(p -> p.toString().endsWith(".writing")).toList()) {
                if (Files.readString(file).contains("\"state\":\"STAGED\"")) return true;
            }
        }
        return false;
    }

    private static void send(final HttpExchange exchange, final int status, final byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    @FunctionalInterface
    private interface Handler {
        void serve(HttpExchange exchange) throws IOException;
    }

    // ---------------------------------------------------------------- coordinator

    private FrameworkUpdateCoordinator coordinator(
            final Fixture f, final FrameworkUpdateCoordinator.HelperLauncher launcher) throws IOException {
        final Path area = f.install.getParent();
        Files.writeString(area.resolve("helper.jar"), "helper-copy-test");
        return new FrameworkUpdateCoordinator(
                f.install,
                area.resolve("stages"),
                FrameworkUpdateHealth.workDirectory(f.install),
                area.resolve("helper.jar"),
                Path.of(System.getProperty("java.home"), "bin", "java"),
                f.io,
                launcher,
                () -> 4242L);
    }

    private static Process liveProcess() throws IOException {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        FrameworkUpdateHelperTest.Sleep.class.getName())
                .redirectErrorStream(true)
                .start();
    }

    @Test
    void coordinatorDownloadsStagesAndOnlyThenSchedulesHelperWaitingForOwnPid() throws Exception {
        try (Fixture f = new Fixture()) {
            final var launched = new java.util.concurrent.atomic.AtomicReference<List<String>>();
            final var live = new Process[1];
            try (var c = coordinator(f, command -> {
                launched.set(command);
                live[0] = liveProcess();
                return live[0];
            })) {
                final var seen = new java.util.concurrent.CopyOnWriteArrayList<FrameworkUpdateCoordinator.State>();
                c.subscribe(status -> seen.add(status.state()));
                assertEquals(FrameworkUpdateCoordinator.State.IDLE, c.status().state());
                assertNull(launched.get(), "nothing launches before the user asks");

                final var staged = c.download(f.downloader(), f.candidate, f.context).get(10, TimeUnit.SECONDS);
                assertEquals(FrameworkUpdateCoordinator.State.STAGED, staged.state(), String.valueOf(staged.message()));
                assertNull(launched.get(), "downloaded is not applied: helper must not start yet");
                assertEquals("2.0.0", staged.version());

                final var pending = c.applyOnExit().get(10, TimeUnit.SECONDS);
                assertEquals(
                        FrameworkUpdateCoordinator.State.APPLY_PENDING, pending.state(), String.valueOf(pending.message()));
                final List<String> command = launched.get();
                assertEquals("dev.turboism.update.FrameworkUpdateHelper", command.get(command.indexOf("-cp") + 2));
                assertEquals("4242", command.get(command.indexOf("--wait-pid") + 1), "waits for OUR pid");
                final Path copiedHelper = Path.of(command.get(command.indexOf("-cp") + 1));
                assertNotEquals(f.install.getParent().resolve("helper.jar"), copiedHelper);
                assertEquals("helper-copy-test", Files.readString(copiedHelper));
                assertTrue(Files.isRegularFile(c.stageDirectory().orElseThrow().resolve("manifest.json")));
                assertTrue(Files.isRegularFile(c.stageDirectory().orElseThrow().resolve("files/turboism-agent.jar")));
                assertFalse(
                        Files.exists(c.stageDirectory().orElseThrow().resolve("files/graal/lib/unchanged.jar")),
                        "unchanged file is not staged");
                assertEquals(
                        List.of(
                                FrameworkUpdateCoordinator.State.IDLE,
                                FrameworkUpdateCoordinator.State.DOWNLOADING,
                                FrameworkUpdateCoordinator.State.STAGED,
                                FrameworkUpdateCoordinator.State.APPLY_PENDING),
                        seen.stream().distinct().toList());
                assertEquals("user", Files.readString(f.install.resolve("plugins/acp.jar")));
                assertFalse(Files.exists(f.install.resolve("turboism-agent.jar")), "installation untouched");
            } finally {
                if (live[0] != null) live[0].destroyForcibly();
            }
        }
    }

    @Test
    void coordinatorReportsFailureTruthfullyAndAllowsRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handler = exchange -> send(exchange, 404, new byte[0]);
            try (var c = coordinator(f, command -> {
                throw new AssertionError("must not launch after a failed download");
            })) {
                final var failed = c.download(f.downloader(), f.candidate, f.context).get(20, TimeUnit.SECONDS);
                assertEquals(FrameworkUpdateCoordinator.State.FAILED, failed.state());
                assertTrue(failed.message().isPresent());
                assertEquals(FrameworkUpdateCoordinator.State.FAILED, c.applyOnExit().get(5, TimeUnit.SECONDS).state());
                assertEquals(FrameworkUpdateCoordinator.State.IDLE, c.reset().state());
                f.handler = exchange -> {
                    exchange.getResponseHeaders().set("ETag", "\"v1\"");
                    send(exchange, 200, f.payload);
                };
                assertEquals(
                        FrameworkUpdateCoordinator.State.STAGED,
                        c.download(f.downloader(), f.candidate, f.context).get(20, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void coordinatorCancelStopsDownloadAndNeverTouchesInstallation() throws Exception {
        try (Fixture f = new Fixture()) {
            final var started = new java.util.concurrent.CountDownLatch(1);
            f.handler = exchange -> {
                started.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            };
            try (var c = coordinator(f, command -> {
                throw new AssertionError("must not launch");
            })) {
                final var future = c.download(f.downloader(), f.candidate, f.context);
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertEquals(FrameworkUpdateCoordinator.State.DOWNLOADING, c.status().state());
                c.cancel();
                final var done = future.get(15, TimeUnit.SECONDS);
                assertEquals(FrameworkUpdateCoordinator.State.CANCELLED, done.state());
                assertFalse(Files.exists(f.install.resolve("turboism-agent.jar")));
                assertEquals(
                        FrameworkUpdateCoordinator.State.CANCELLED, c.applyOnExit().get(5, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void coordinatorDoesNotReportScheduledWhenHelperDiesImmediately() throws Exception {
        try (Fixture f = new Fixture()) {
            try (var c = coordinator(f, command -> {
                final Process dead = new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                        .redirectErrorStream(true)
                        .start();
                dead.getInputStream().readAllBytes();
                try {
                    dead.waitFor();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                return dead;
            })) {
                assertEquals(
                        FrameworkUpdateCoordinator.State.STAGED,
                        c.download(f.downloader(), f.candidate, f.context).get(20, TimeUnit.SECONDS).state());
                final var result = c.applyOnExit().get(10, TimeUnit.SECONDS);
                assertEquals(FrameworkUpdateCoordinator.State.FAILED, result.state(), "must not claim a scheduled apply");
                assertTrue(result.message().orElse("").contains("helper"));
            }
        }
    }

    @Test
    void coordinatorRefusesStageInsideInstallationAndSecondDownloadJoins() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new FrameworkUpdateCoordinator(
                            f.install,
                            f.install.resolve("stages"),
                            f.install.getParent().resolve("work"),
                            f.install.getParent().resolve("cp"),
                            Path.of("java"),
                            f.io,
                            command -> null,
                            () -> 1L));
            final var gate = new java.util.concurrent.CountDownLatch(1);
            final var requests = new java.util.concurrent.atomic.AtomicInteger();
            f.handler = exchange -> {
                requests.incrementAndGet();
                try {
                    gate.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                send(exchange, 200, f.payload);
            };
            try (var c = coordinator(f, command -> null)) {
                final var first = c.download(f.downloader(), f.candidate, f.context);
                final var second = c.download(f.downloader(), f.candidate, f.context);
                assertSame(first, second, "same in-flight download is joined");
                gate.countDown();
                assertEquals(FrameworkUpdateCoordinator.State.STAGED, first.get(20, TimeUnit.SECONDS).state());
                assertEquals(1, requests.get(), "one object request, not two");
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path cache;
        final Path install;
        final byte[] payload = new byte[65536];
        final byte[] unchanged = new byte[1024 * 1024];
        final String hash;
        final String unchangedHash;
        final byte[] manifest;
        byte[] envelope;
        final String closureHash;
        final HttpServer server;
        final ExecutorService serverThreads = Executors.newCachedThreadPool();
        final ExecutorService io = Executors.newSingleThreadExecutor();
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger objectRequests = new AtomicInteger();
        final AtomicInteger documentRequests = new AtomicInteger();
        final UpdateManifestVerifier verifier;
        final FrameworkUpdateSource source;
        volatile Handler handler;
        volatile Handler documentHandler;
        UpdateDiscovery.Candidate candidate;
        UpdateVerificationContext context;

        Fixture() throws Exception {
            final Path root = Files.createTempDirectory(temporary, "session-");
            cache = root.resolve("cache");
            install = root.resolve("install");
            Arrays.fill(payload, (byte) 42);
            Arrays.fill(unchanged, (byte) 13);
            hash = sha(payload);
            unchangedHash = sha(unchanged);
            Files.createDirectories(install.resolve("graal/lib"));
            Files.write(install.resolve("graal/lib/unchanged.jar"), unchanged);
            Files.createDirectory(install.resolve("plugins"));
            Files.writeString(install.resolve("plugins/acp.jar"), "user");
            final var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            verifier = new UpdateManifestVerifier(Map.of("ephemeral", pair.getPublic()));
            final var entries = List.of(
                    entry("turboism-agent.jar", payload),
                    entry("graal/lib/copy.jar", payload),
                    entry("graal/lib/unchanged.jar", unchanged));
            manifest = new UpdateManifest(
                            1, "ephemeral", "2.0.0", "stable", 50, "a".repeat(40), "1.0.0", "windows-x64", entries)
                    .toCanonicalBytes();
            final Signature sign = Signature.getInstance("Ed25519");
            sign.initSign(pair.getPrivate());
            sign.update(manifest);
            final var sig = UpdateFileSupport.JSON
                    .createObjectNode()
                    .put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                    .put("schemaVersion", 1)
                    .put("algorithm", "Ed25519")
                    .put("keyId", "ephemeral")
                    .put("manifestSha256", sha(manifest))
                    .put("signature", Base64.getEncoder().encodeToString(sign.sign()));
            envelope = UpdateFileSupport.JSON.writeValueAsBytes(sig);
            final ArrayNode closure = UpdateFileSupport.JSON.createArrayNode();
            new java.util.TreeMap<>(Map.of(hash, payload.length, unchangedHash, unchanged.length))
                    .forEach((h, n) -> closure.addObject().put("bytes", n).put("sha256", h));
            closureHash = sha(UpdateFileSupport.JSON.writeValueAsBytes(closure));
            candidate = candidate(closureHash);
            context = context(Map.of("graal/lib/unchanged.jar", unchangedHash));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverThreads);
            server.createContext("/", exchange -> {
                try {
                    final String path = exchange.getRequestURI().getPath();
                    if (path.equals("/documents/" + sha(manifest))) {
                        documentRequests.incrementAndGet();
                        if (documentHandler != null) documentHandler.serve(exchange);
                        else send(exchange, 200, manifest);
                    } else if (path.equals("/documents/" + sha(envelope))) {
                        documentRequests.incrementAndGet();
                        send(exchange, 200, envelope);
                    } else if (path.equals("/objects/" + hash + "/payload")) {
                        objectRequests.incrementAndGet();
                        handler.serve(exchange);
                    } else {
                        objectRequests.incrementAndGet();
                        send(exchange, 404, new byte[0]);
                    }
                } catch (Throwable failure) {
                    exchange.close();
                }
            });
            handler = exchange -> {
                exchange.getResponseHeaders().set("ETag", "\"v1\"");
                send(exchange, 200, payload);
            };
            server.start();
            source = FrameworkUpdateSource.loopbackForTest(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        }

        UpdateDiscovery.Candidate candidate(final String closure) throws Exception {
            final var capability = new UpdateIncrementalDiscovery(
                    "2.0.0",
                    "stable",
                    50,
                    "a".repeat(40),
                    new UpdateIncrementalDiscovery.Reference(manifest.length, sha(manifest)),
                    new UpdateIncrementalDiscovery.Reference(envelope.length, sha(envelope)),
                    closure);
            return new UpdateDiscovery.Candidate(
                    UpdateVersion.parseInstalled("2.0.0"),
                    "2.0.0",
                    OptionalLong.of(50),
                    Optional.empty(),
                    List.of(),
                    Optional.of("a".repeat(40)),
                    Optional.of(capability));
        }

        UpdateVerificationContext context(final Map<String, String> receipt) {
            return new UpdateVerificationContext(
                    "2.0.0",
                    "stable",
                    50,
                    "a".repeat(40),
                    "1.0.0",
                    "windows-x64",
                    InstalledBuild.of("1.0.0", OptionalLong.of(49), "1.0.0"),
                    receipt);
        }

        FrameworkUpdateDownloader downloader() {
            return downloader(FrameworkUpdateCache.DEFAULT_QUOTA, ignored -> {});
        }

        FrameworkUpdateDownloader downloader(final long quota, final FrameworkUpdateCache.Faults faults) {
            return new FrameworkUpdateDownloader(
                    cache, source, verifier, io, timer, Duration.ofMinutes(1), quota, faults);
        }

        CompletableFuture<FrameworkUpdateDownloader.Downloaded> download(
                final Consumer<FrameworkUpdateDownloader.Progress> progress) {
            return new FrameworkUpdateDownloader(cache, source, verifier, io, timer)
                    .download(candidate, context, install, progress);
        }

        void partial(final int bytes, final String tag) throws Exception {
            try (FrameworkUpdateCache c = new FrameworkUpdateCache(cache)) {
                final Path part = c.path("object", hash, "part");
                c.create(part);
                Files.write(part, Arrays.copyOf(payload, bytes));
                c.etag(c.path("object", hash, "etag"), tag);
            }
        }

        @Override
        public void close() throws Exception {
            server.stop(0);
            serverThreads.shutdownNow();
            io.shutdownNow();
            timer.shutdownNow();
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(timer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static UpdateManifestEntry entry(final String path, final byte[] bytes) throws Exception {
        final String hash = sha(bytes);
        return new UpdateManifestEntry(path, bytes.length, hash, "sha256:" + hash);
    }

    private static String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void makeReadableByOthers(final Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                    path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            return;
        }
        final var view = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
        final var everyone = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
        final var acl = new java.util.ArrayList<>(view.getAcl());
        acl.add(java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(everyone)
                .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA)
                .build());
        view.setAcl(acl);
    }

    private static void createPrivateFile(final Path path) throws IOException {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            Files.createFile(
                    path,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(path);
        }
    }
}
