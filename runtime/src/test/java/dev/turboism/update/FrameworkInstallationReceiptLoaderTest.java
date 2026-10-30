package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FrameworkInstallationReceiptLoaderTest {
    @TempDir
    Path temporary;

    @Test
    void injectedFileBoundaryClosesOnSuccessAndVerificationFailure() throws Exception {
        final Fixture f = new Fixture();
        final var closes = new java.util.concurrent.atomic.AtomicInteger();
        final FrameworkInstallationFiles.Factory factory = root -> {
            final var delegate = new FrameworkInstallationFiles.Posix(root);
            return new FrameworkInstallationFiles() {
                @Override
                public String rootIdentity() throws IOException {
                    return delegate.rootIdentity();
                }

                @Override
                public byte[] document(Path path, int limit) throws IOException, InterruptedException {
                    return delegate.document(path, limit);
                }

                @Override
                public void verify(UpdateManifestEntry entry) throws IOException, InterruptedException {
                    delegate.verify(entry);
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        };
        final var loader = new FrameworkInstallationReceiptLoader(
                f.root, f.verifier, () -> f.info, () -> f.root.resolve("turboism-agent.jar"), factory);
        loader.load();
        assertEquals(1, closes.get());
        Files.writeString(f.root.resolve("turboism-agent.jar"), "changed");
        assertThrows(IOException.class, loader::load);
        assertEquals(2, closes.get());
    }

    @Test
    void historicalAuthenticationAndPrivateEvidenceDoNotAdmitOldBuildAsNewTarget() throws Exception {
        final Fixture f = new Fixture();
        final var evidence = f.loader().load();
        assertEquals(f.info, evidence.loaded());
        assertFalse(evidence.hashes().containsKey("plugins/acp.jar"));
        assertEquals("user", Files.readString(f.root.resolve("plugins/acp.jar")));
        assertEquals(49, f.verifier.authenticate(f.manifest, f.envelope).buildNumber());
        final var replay = new UpdateVerificationContext(
                "1.0.0",
                "stable",
                49,
                "b".repeat(40),
                "1.0.0",
                "windows-x64",
                InstalledBuild.from(f.info),
                evidence.hashes());
        assertEquals(
                "BUILD_NOT_NEWER",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> f.verifier.verify(f.manifest, f.envelope, replay, Instant.now()))
                        .errorCode());
        assertArrayEquals(f.manifest, Files.readAllBytes(f.documents.resolve("manifest.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "build", "channel", "version", "dirty", "local", "unknown", "artifact"})
    void rejectsMismatchWithLoadedIdentity(final String mode) throws Exception {
        final Fixture f = new Fixture();
        final var original = f.info;
        if (!mode.equals("artifact")) {
            f.info = new FrameworkBuildInfo(
                    mode.equals("version") ? "1.1.0" : original.version(),
                    mode.equals("channel") ? FrameworkBuildInfo.Channel.BETA : original.channel(),
                    mode.equals("unknown") ? OptionalLong.empty() : OptionalLong.of(mode.equals("build") ? 48 : 49),
                    mode.equals("source") ? "c".repeat(40) : original.sourceRevision(),
                    mode.equals("local") ? "local" : "ci",
                    mode.equals("dirty"));
        }
        final var loader = new FrameworkInstallationReceiptLoader(
                f.root,
                f.verifier,
                () -> f.info,
                () -> mode.equals("artifact") ? f.root.resolve("other.jar") : f.root.resolve("turboism-agent.jar"));
        assertThrows(IOException.class, loader::load);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-index",
                "missing-doc",
                "missing-file",
                "modified-file",
                "tampered-manifest",
                "bad-signature",
                "root",
                "path",
                "layout",
                "schema-overflow",
                "duplicate",
                "unknown-field",
                "oversized",
                "oversized-manifest",
                "oversized-envelope",
                "unreadable",
                "symlink-file",
                "hardlink-file",
                "symlink-index",
                "hardlink-index",
                "symlink-parent",
                "permissions-file",
                "permissions-index",
                "permissions-state"
            })
    void rejectsCorruptOrUnsafeReceiptWithoutWritingInstallation(final String mode) throws Exception {
        final Fixture f = new Fixture();
        final Path agent = f.root.resolve("turboism-agent.jar");
        switch (mode) {
            case "missing-index" -> Files.delete(f.index);
            case "missing-doc" -> Files.delete(f.documents.resolve("manifest.json"));
            case "missing-file" -> Files.delete(agent);
            case "modified-file" -> Files.writeString(agent, "changed");
            case "tampered-manifest" -> Files.writeString(f.documents.resolve("manifest.json"), "{}");
            case "bad-signature" -> {
                final ObjectNode signature = (ObjectNode) UpdateFileSupport.JSON.readTree(f.envelope);
                signature.put("signature", Base64.getEncoder().encodeToString(new byte[64]));
                Files.write(
                        f.documents.resolve("manifest.json.sig"), UpdateFileSupport.JSON.writeValueAsBytes(signature));
            }
            case "root", "path", "layout", "schema-overflow", "unknown-field" -> {
                final ObjectNode index = (ObjectNode) UpdateFileSupport.JSON.readTree(Files.readAllBytes(f.index));
                switch (mode) {
                    case "root" -> index.put("rootIdentity", "0".repeat(64));
                    case "path" -> index.put("manifestSha256", "../../elsewhere");
                    case "layout" -> index.put("layout", "nsis");
                    case "schema-overflow" ->
                        index.put("schemaVersion", new java.math.BigInteger("18446744073709551617"));
                    default -> index.put("path", agent.toString());
                }
                Files.write(f.index, UpdateFileSupport.JSON.writeValueAsBytes(index));
            }
            case "duplicate" ->
                Files.writeString(
                        f.index,
                        Files.readString(f.index)
                                .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
            case "oversized" -> Files.write(f.index, new byte[4097]);
            case "oversized-manifest" ->
                Files.write(f.documents.resolve("manifest.json"), new byte[UpdateManifest.MAX_MANIFEST_BYTES + 1]);
            case "oversized-envelope" ->
                Files.write(
                        f.documents.resolve("manifest.json.sig"),
                        new byte[UpdateManifestVerifier.MAX_ENVELOPE_BYTES + 1]);
            case "unreadable" -> Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("-w-------"));
            case "symlink-file", "hardlink-file", "symlink-index", "hardlink-index" -> {
                final Path target = mode.endsWith("index") ? f.index : agent;
                final Path saved = target.resolveSibling("saved");
                Files.move(target, saved);
                if (mode.startsWith("symlink")) Files.createSymbolicLink(target, saved);
                else Files.createLink(target, saved);
            }
            case "symlink-parent" -> {
                final Path lib = f.root.resolve("graal/lib");
                final Path saved = f.root.resolve("graal/saved");
                Files.move(lib, saved);
                Files.createSymbolicLink(lib, saved);
            }
            case "permissions-file" ->
                Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("rw-rw-r--"));
            case "permissions-index" ->
                Files.setPosixFilePermissions(f.index, PosixFilePermissions.fromString("rw-r--r--"));
            case "permissions-state" ->
                Files.setPosixFilePermissions(f.index.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
            default -> throw new AssertionError(mode);
        }
        if (mode.equals("bad-signature")) {
            assertThrows(
                    UpdateManifestVerifier.UpdateManifestVerificationException.class,
                    () -> f.loader().load());
        } else {
            assertThrows(IOException.class, () -> f.loader().load());
        }
        assertEquals("user", Files.readString(f.root.resolve("plugins/acp.jar")));
    }

    @Test
    void copiedReceiptCannotBindADifferentDirectoryEvenAtSameVersion() throws Exception {
        final Fixture f = new Fixture();
        final Path moved = temporary.resolve("moved");
        Files.move(f.root, moved);
        assertThrows(
                IOException.class,
                () -> new FrameworkInstallationReceiptLoader(moved, f.verifier, () -> f.info).load());
    }

    @Test
    void rotationKeepsHistoricalKeyButRevocationOverridesBothOldAndNewAuthentication() throws Exception {
        final Fixture f = new Fixture();
        final var next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final var rotated = new UpdateManifestVerifier(Map.of("fixture", f.keys.getPublic(), "next", next.getPublic()));
        assertNotNull(new FrameworkInstallationReceiptLoader(f.root, rotated, () -> f.info).load());
        final var unknown = new UpdateManifestVerifier(Map.of("next", next.getPublic()));
        assertEquals(
                "UNKNOWN_KEY",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> new FrameworkInstallationReceiptLoader(f.root, unknown, () -> f.info).load())
                        .errorCode());
        final var revoked = new UpdateManifestVerifier(
                Map.of("fixture", f.keys.getPublic(), "next", next.getPublic()), Set.of("fixture"));
        assertEquals(
                "REVOKED_KEY",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> new FrameworkInstallationReceiptLoader(f.root, revoked, () -> f.info).load())
                        .errorCode());
    }

    @Test
    void incompleteSignedLayoutAndPluginsCannotCreateInstallationEvidence() throws Exception {
        final Fixture f = new Fixture();
        f.publish(List.of(f.old.entries().get(0)));
        assertThrows(IOException.class, () -> f.loader().load());
        assertThrows(IllegalArgumentException.class, () -> Fixture.entry("plugins/acp.jar", new byte[0]));
    }

    @Test
    void defaultTrustConfigurationAndDevelopmentArtifactAreUnavailable() throws Exception {
        assertThrows(
                IOException.class,
                () -> FrameworkInstallationReceiptLoader.disabled().load());
        final Fixture f = new Fixture();
        assertThrows(IOException.class, () -> new FrameworkInstallationReceiptLoader(f.root, f.verifier).load());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> f.loader().load());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "normal",
                "receipt-before-start",
                "receipt-before-commit",
                "candidate-before-start",
                "candidate-before-commit",
                "receipt-before-recover",
                "receipt-rebind-before-recover",
                "loaded-before-recover",
                "candidate-during-load",
                "safe-before-start",
                "checking-before-start",
                "failed-before-start",
                "legacy-before-start",
                "refreshed-before-start",
                "index-reformat-before-commit"
            })
    void realDiscoveryReceiptAndLoopbackStageRecoverRevalidateLiveAuthority(final String mode) throws Exception {
        final Fixture f = new Fixture();
        final var io = Executors.newSingleThreadExecutor();
        final var timer = Executors.newSingleThreadScheduledExecutor();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 8, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {});
        final var settings = new Settings();
        final var response =
                new java.util.concurrent.atomic.AtomicReference<CompletableFuture<UpdateTransport.Response>>();
        final UpdateTransport transport = etag -> response.get();
        final RuntimeUpdateService service = new RuntimeUpdateService(
                temporary.resolve("service"),
                scheduler,
                settings,
                transport,
                Clock.systemUTC(),
                InstalledBuild.from(f.info),
                Duration.ofDays(1),
                Duration.ofDays(1),
                ignored -> {});
        try {
            assertThrows(IOException.class, () -> service.incrementalRequest(f.loader()));
            final byte[] changed = "new-agent-and-libs".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final List<UpdateManifestEntry> entries = new ArrayList<>();
            for (final var oldEntry : f.old.entries()) {
                if (oldEntry.relativePath().startsWith("graal/lib/")) continue;
                entries.add(
                        oldEntry.relativePath().equals("turboism-agent.jar")
                                ? Fixture.entry(oldEntry.relativePath(), changed)
                                : oldEntry);
            }
            entries.add(Fixture.entry("graal/lib/sdk-2.0.0.jar", changed));
            entries.add(Fixture.entry("graal/lib/graal-host-2.0.0-all.jar", changed));
            final byte[] manifest = new UpdateManifest(
                            1, "fixture", "2.0.0", "stable", 50, "a".repeat(40), "1.0.0", "windows-x64", entries)
                    .toCanonicalBytes();
            final byte[] envelope = f.sign(manifest);
            final var closure = UpdateFileSupport.JSON.createArrayNode();
            final Map<String, Long> objects = new TreeMap<>();
            entries.forEach(e -> objects.put(e.sha256(), e.bytes()));
            objects.forEach(
                    (hash, bytes) -> closure.addObject().put("bytes", bytes).put("sha256", hash));
            final var descriptor = new UpdateIncrementalDiscovery(
                    "2.0.0",
                    "stable",
                    50,
                    "a".repeat(40),
                    new UpdateIncrementalDiscovery.Reference(manifest.length, FrameworkUpdateTaskStore.hash(manifest)),
                    new UpdateIncrementalDiscovery.Reference(envelope.length, FrameworkUpdateTaskStore.hash(envelope)),
                    FrameworkUpdateTaskStore.hash(UpdateFileSupport.JSON.writeValueAsBytes(closure)));
            final var candidate = new UpdateDiscovery.Candidate(
                    UpdateVersion.parseInstalled("2.0.0"),
                    "2.0.0",
                    OptionalLong.of(50),
                    Optional.empty(),
                    List.of(),
                    Optional.of("a".repeat(40)),
                    Optional.of(descriptor));
            final byte[] feed = UpdateFileSupport.JSON.writeValueAsBytes(
                    new UpdateDiscovery(1, "stable", UpdateDiscovery.Status.READY, Optional.of(candidate))
                            .toJson(UpdateFileSupport.JSON));
            response.set(CompletableFuture.completedFuture(new UpdateTransport.Response(200, feed, Optional.empty())));
            service.checkManual().toCompletableFuture().get(3, TimeUnit.SECONDS);
            if (mode.equals("candidate-during-load")) {
                final var changing = new FrameworkInstallationReceiptLoader(f.root, f.verifier, () -> {
                    service.close();
                    return f.info;
                });
                assertThrows(IOException.class, () -> service.incrementalRequest(changing));
                return;
            }
            final var request = service.incrementalRequest(f.loader());
            final Map<String, byte[]> resources = Map.of(
                    "/documents/" + FrameworkUpdateTaskStore.hash(manifest),
                    manifest,
                    "/documents/" + FrameworkUpdateTaskStore.hash(envelope),
                    envelope,
                    "/objects/" + FrameworkUpdateTaskStore.hash(changed) + "/payload",
                    changed);
            final var objectRequests = new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().startsWith("/objects/")) objectRequests.incrementAndGet();
                final byte[] body = resources.get(exchange.getRequestURI().getPath());
                exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? 0 : body.length);
                if (body != null) exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            final Path cache = temporary.resolve("cache");
            final var faults = (FrameworkUpdateCache.Faults) point -> {
                if (point.equals("before-commit-claim")) {
                    if (mode.equals("receipt-before-commit"))
                        Files.writeString(f.root.resolve("turboism-agent.jar"), "changed");
                    if (mode.equals("candidate-before-commit")) service.close();
                    if (mode.equals("index-reformat-before-commit"))
                        Files.writeString(f.index, Files.readString(f.index) + "\n");
                }
            };
            final var downloader = new FrameworkUpdateDownloader(
                    cache,
                    FrameworkUpdateSource.loopbackForTest(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")),
                    f.verifier,
                    io,
                    timer,
                    Duration.ofMinutes(1),
                    FrameworkUpdateCache.DEFAULT_QUOTA,
                    faults);
            if (mode.equals("receipt-before-start")) Files.delete(f.index);
            if (mode.equals("candidate-before-start")) service.close();
            if (mode.equals("safe-before-start")) settings.save(new RuntimeSettings(true, "INFO", false, false, false));
            if (Set.of("checking-before-start", "failed-before-start", "legacy-before-start", "refreshed-before-start")
                    .contains(mode)) {
                final CompletableFuture<UpdateTransport.Response> next = new CompletableFuture<>();
                response.set(next);
                final var check = service.checkManual();
                if (mode.equals("failed-before-start")) next.completeExceptionally(new IOException("offline"));
                else if (mode.equals("legacy-before-start")) {
                    final var legacy = new UpdateDiscovery.Candidate(
                            UpdateVersion.parseInstalled("2.0.0"),
                            "2.0.0",
                            OptionalLong.of(50),
                            Optional.empty(),
                            List.of());
                    final byte[] legacyFeed = UpdateFileSupport.JSON.writeValueAsBytes(
                            new UpdateDiscovery(1, "stable", UpdateDiscovery.Status.READY, Optional.of(legacy))
                                    .toJson(UpdateFileSupport.JSON));
                    next.complete(new UpdateTransport.Response(200, legacyFeed, Optional.empty()));
                } else if (mode.equals("refreshed-before-start"))
                    next.complete(new UpdateTransport.Response(200, feed, Optional.empty()));
                if (!mode.equals("checking-before-start"))
                    check.toCompletableFuture().get(3, TimeUnit.SECONDS);
            }
            if (mode.endsWith("before-start") && !mode.equals("refreshed-before-start")) {
                assertThrows(IOException.class, () -> service.incrementalRequest(f.loader()));
            }
            if (mode.contains("before-start") || mode.contains("before-commit")) {
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> request.stage(downloader, ignored -> {}).get(5, TimeUnit.SECONDS));
                if (Files.exists(cache)) {
                    try (var paths = Files.list(cache)) {
                        for (final Path path : paths.filter(p -> p.toString().endsWith(".json"))
                                .toList()) assertFalse(Files.readString(path).contains("\"STAGED\""));
                    }
                }
                return;
            }
            final var staged = request.stage(downloader, ignored -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, objectRequests.get()); // all unchanged root files REUSE; new payload is shared.
            final String id = staged.staged().orElseThrow().id();
            if (mode.equals("receipt-rebind-before-recover")) {
                Files.writeString(f.index, Files.readString(f.index) + "\n");
                final var rebound = service.incrementalRequest(f.loader());
                assertThrows(IllegalArgumentException.class, () -> rebound.recover(id, downloader, ignored -> {}));
            } else if (mode.equals("loaded-before-recover")) {
                f.info = new FrameworkBuildInfo(
                        "1.0.0", FrameworkBuildInfo.Channel.STABLE, OptionalLong.of(49), "c".repeat(40), "ci", false);
                assertThrows(IOException.class, () -> service.incrementalRequest(f.loader()));
            } else if (mode.equals("receipt-before-recover")) {
                Files.writeString(f.index, "{}");
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> request.recover(id, downloader, ignored -> {}).get(5, TimeUnit.SECONDS));
            } else {
                final var fresh = service.incrementalRequest(f.loader());
                final var recovered =
                        fresh.recover(id, downloader, ignored -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(id, recovered.staged().orElseThrow().id());
                assertEquals(1, objectRequests.get());
                assertEquals("old:turboism-agent.jar", Files.readString(f.root.resolve("turboism-agent.jar")));
            }
            assertEquals("user", Files.readString(f.root.resolve("plugins/acp.jar")));
        } finally {
            service.close();
            scheduler.shutdown();
            server.stop(0);
            io.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static final class Settings implements RuntimeSettingsService {
        @Override
        public DockCleanupResult cleanEmptyDocks() {
            return new DockCleanupResult("unused");
        }

        private RuntimeSettings value = new RuntimeSettings(false, "INFO", false, false, false);

        @Override
        public RuntimeSettings read() {
            return value;
        }

        @Override
        public RuntimeSettings save(final RuntimeSettings next) {
            value = next;
            return value;
        }
    }

    private final class Fixture {
        final Path root;
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of("fixture", keys.getPublic()));
        FrameworkBuildInfo info = new FrameworkBuildInfo(
                "1.0.0", FrameworkBuildInfo.Channel.STABLE, OptionalLong.of(49), "b".repeat(40), "ci", false);
        UpdateManifest old;
        byte[] manifest;
        byte[] envelope;
        Path documents;
        final Path index;

        Fixture() throws Exception {
            root = Files.createTempDirectory(temporary, "installation-");
            final List<UpdateManifestEntry> entries = new ArrayList<>();
            final List<String> paths = new ArrayList<>(UpdateManifestEntry.FRAMEWORK_ROOT_FILES);
            paths.add("graal/lib/sdk-1.0.0.jar");
            paths.add("graal/lib/graal-host-1.0.0-all.jar");
            for (final String path : paths) {
                final byte[] bytes = ("old:" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                final Path file = root.resolve(path);
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                entries.add(entry(path, bytes));
            }
            Files.createDirectory(root.resolve("plugins"));
            Files.writeString(root.resolve("plugins/acp.jar"), "user");
            index = root.resolve(FrameworkInstallationReceiptLoader.STATE).resolve("installed-receipt.json");
            publish(entries);
        }

        void publish(final List<UpdateManifestEntry> entries) throws Exception {
            old = new UpdateManifest(
                    1, "fixture", "1.0.0", "stable", 49, "b".repeat(40), "1.0.0", "windows-x64", entries);
            manifest = old.toCanonicalBytes();
            envelope = sign(manifest);
            final String hash = FrameworkUpdateTaskStore.hash(manifest);
            documents = index.getParent().resolve("receipts").resolve(hash);
            Files.createDirectories(documents);
            for (Path p = documents; !p.equals(root); p = p.getParent())
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
            writePrivate(documents.resolve("manifest.json"), manifest);
            writePrivate(documents.resolve("manifest.json.sig"), envelope);
            final var record = UpdateFileSupport.JSON
                    .createObjectNode()
                    .put("format", FrameworkInstallationReceiptLoader.FORMAT)
                    .put("schemaVersion", 1)
                    .put("layout", FrameworkInstallationReceiptLoader.LAYOUT)
                    .put("manifestSha256", hash)
                    .put("rootIdentity", new FrameworkInstallationFiles.Posix(root).rootIdentity());
            writePrivate(index, UpdateFileSupport.JSON.writeValueAsBytes(record));
        }

        byte[] sign(final byte[] bytes) throws Exception {
            final Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(bytes);
            return UpdateFileSupport.JSON.writeValueAsBytes(UpdateFileSupport.JSON
                    .createObjectNode()
                    .put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                    .put("schemaVersion", 1)
                    .put("algorithm", "Ed25519")
                    .put("keyId", "fixture")
                    .put("manifestSha256", FrameworkUpdateTaskStore.hash(bytes))
                    .put("signature", Base64.getEncoder().encodeToString(signer.sign())));
        }

        FrameworkInstallationReceiptLoader loader() {
            return new FrameworkInstallationReceiptLoader(root, verifier, () -> info);
        }

        static UpdateManifestEntry entry(final String path, final byte[] bytes) {
            final String hash = FrameworkUpdateTaskStore.hash(bytes);
            return new UpdateManifestEntry(path, bytes.length, hash, "sha256:" + hash);
        }

        private void writePrivate(final Path path, final byte[] bytes) throws IOException {
            Files.write(path, bytes);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
