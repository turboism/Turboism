package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkDeltaPlannerTest {

    @TempDir
    Path home;

    private KeyPair testKeyPair;
    private UpdateManifestVerifier verifier;
    private UpdateVerificationContext defaultContext;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        testKeyPair = generator.generateKeyPair();
        verifier = new UpdateManifestVerifier(Map.of("test-key-v1", testKeyPair.getPublic()));
        defaultContext = new UpdateVerificationContext(
                "0.68.0",
                "stable",
                50L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.67.4",
                "windows-x64",
                InstalledBuild.of("0.67.4", java.util.OptionalLong.of(49), "0.67.4"),
                Map.of());
    }

    private TrustedUpdateManifest createTrustedManifest(final List<UpdateManifestEntry> entries) throws Exception {
        final UpdateManifest manifest = new UpdateManifest(
                1,
                "test-key-v1",
                "0.68.0",
                "stable",
                50L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.67.0",
                "windows-x64",
                entries);
        final byte[] manifestBytes = manifest.toCanonicalBytes();
        final byte[] hash = MessageDigest.getInstance("SHA-256").digest(manifestBytes);
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(testKeyPair.getPrivate());
        signer.update(manifestBytes);
        final byte[] sig = signer.sign();

        final String envelopeJson = String.format(
                """
                {
                  "format": "turboism-update-manifest-sig-v1",
                  "schemaVersion": 1,
                  "algorithm": "Ed25519",
                  "keyId": "test-key-v1",
                  "manifestSha256": "%s",
                  "signature": "%s"
                }
                """, HexFormat.of().formatHex(hash), Base64.getEncoder().encodeToString(sig));

        return verifier.verify(
                manifestBytes, envelopeJson.getBytes(StandardCharsets.UTF_8), defaultContext, Instant.now());
    }

    @Test
    void selectsOnlyChangedAndMissingJarsWithoutModifyingAnything() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Files.createDirectories(home.resolve("graal/lib"));
        Files.writeString(home.resolve("turboism-agent.jar"), "old-agent");
        Files.writeString(home.resolve("graal/lib/sdk-1.jar"), "unchanged-plugin");
        Files.writeString(home.resolve("config.json"), "{\"mySettings\":true}");

        withReceipts(Map.of(
                "turboism-agent.jar",
                entry("turboism-agent.jar", "old-agent").sha256(),
                "graal/lib/sdk-1.jar",
                entry("graal/lib/sdk-1.jar", "unchanged-plugin").sha256()));
        final var entries = List.of(
                entry("turboism-agent.jar", "new-agent"),
                entry("graal/lib/sdk-1.jar", "unchanged-plugin"),
                entry("graal/lib/truffle-api-1.jar", "graal"));
        final TrustedUpdateManifest trusted = createTrustedManifest(entries);
        final FrameworkDeltaPlanner.Plan plan = FrameworkDeltaPlanner.plan(home, trusted);

        assertEquals(
                List.of(
                        FrameworkDeltaPlanner.Action.REUSE,
                        FrameworkDeltaPlanner.Action.DOWNLOAD,
                        FrameworkDeltaPlanner.Action.DOWNLOAD),
                plan.decisions().stream()
                        .map(FrameworkDeltaPlanner.Decision::action)
                        .toList());
        assertEquals("new-agent".getBytes(StandardCharsets.UTF_8).length + 5L, plan.bytesToDownload());

        // Assert read-only guarantee: nothing in home was mutated
        assertEquals("old-agent", Files.readString(home.resolve("turboism-agent.jar")));
        assertEquals("unchanged-plugin", Files.readString(home.resolve("graal/lib/sdk-1.jar")));
        assertEquals("{\"mySettings\":true}", Files.readString(home.resolve("config.json")));
        assertTrue(Files.notExists(home.resolve("graal/lib/truffle-api-1.jar")));
    }

    @Test
    void preservesUnmanagedUserPluginsAndConfigsWithoutClobbering() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        // User's own unmanaged plugin
        Files.writeString(home.resolve("plugins/user-custom-tool.jar"), "custom-user-bytes");
        Files.writeString(home.resolve("config.json"), "{\"userKey\":123}");

        final var entries = List.of(entry("turboism-agent.jar", "new-agent"));
        final TrustedUpdateManifest trusted = createTrustedManifest(entries);
        final FrameworkDeltaPlanner.Plan plan = FrameworkDeltaPlanner.plan(home, trusted);

        assertEquals(1, plan.decisions().size());
        assertEquals("turboism-agent.jar", plan.decisions().get(0).entry().relativePath());

        // User files remain completely intact and ignored
        assertEquals("custom-user-bytes", Files.readString(home.resolve("plugins/user-custom-tool.jar")));
        assertEquals("{\"userKey\":123}", Files.readString(home.resolve("config.json")));
    }

    @Test
    void rejectsOwnershipViolationWhenManifestTriesToReplaceUnapprovedPlugin() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        // An unapproved plugin name in manifest
        assertThrows(IllegalArgumentException.class, () -> entry("plugins/user-arbitrary-mod.jar", "hack"));
    }

    @Test
    void rejectsRootAncestorSymbolicLinkEscape() throws Exception {
        final Path realExternalDir = Files.createDirectory(home.resolve("real-target"));
        final Path linkAncestor = home.resolve("link-ancestor");
        try {
            Files.createSymbolicLink(linkAncestor, realExternalDir);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink creation is unavailable on this host");
        }

        final Path subInstallRoot = linkAncestor.resolve("app");
        Files.createDirectories(subInstallRoot);

        final var entries = List.of(entry("turboism-agent.jar", "new-agent"));
        final TrustedUpdateManifest trusted = createTrustedManifest(entries);

        // Planning against an installation root that has a symlink ancestor must fail
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(subInstallRoot, trusted));
    }

    @Test
    void respectsCancellationAndThreadInterruption() throws Exception {
        Files.createDirectories(home.resolve("graal/lib"));
        Files.writeString(home.resolve("turboism-agent.jar"), "old-agent");

        final var entries = List.of(entry("turboism-agent.jar", "new-agent"));
        final TrustedUpdateManifest trusted = createTrustedManifest(entries);

        // Pre-interrupt the thread
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> FrameworkDeltaPlanner.plan(home, trusted));
            // Ensure thread interruption status is preserved
            assertTrue(Thread.currentThread().isInterrupted(), "thread interrupt flag must be preserved");
        } finally {
            Thread.interrupted(); // clear status for teardown
        }
    }

    @Test
    void detectsSameSizeCorruptionByHash() throws Exception {
        Files.writeString(home.resolve("turboism-agent.jar"), "abcd");
        withReceipts(
                Map.of("turboism-agent.jar", entry("turboism-agent.jar", "abcd").sha256()));
        final var entries = List.of(entry("turboism-agent.jar", "abce"));
        final TrustedUpdateManifest trusted = createTrustedManifest(entries);
        final var plan = FrameworkDeltaPlanner.plan(home, trusted);
        assertEquals(
                FrameworkDeltaPlanner.Action.DOWNLOAD, plan.decisions().get(0).action());
        assertEquals(4L, plan.bytesToDownload());
    }

    @Test
    void rejectsWindowsReservedDeviceNames() {
        assertThrows(IllegalArgumentException.class, () -> entry("graal/lib/con.jar", "x"));
        assertThrows(IllegalArgumentException.class, () -> entry("plugins/aux.jar", "x"));
        assertThrows(IllegalArgumentException.class, () -> entry("graal/lib/nul.jar", "x"));
        assertThrows(IllegalArgumentException.class, () -> entry("graal/lib/PRN.jar", "x"));
        assertThrows(IllegalArgumentException.class, () -> entry("plugins/com1.jar", "x"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"turboism-agent.jar", "graal/lib/local.jar"})
    void rejectsExistingEligibleNamesWithoutTrustedOwnership(final String path) throws Exception {
        final Path file = home.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "local-user-file");
        final var trusted = createTrustedManifest(List.of(entry(path, "new")));
        final IOException error = assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(home, trusted));
        assertTrue(error.getMessage().contains("FRAMEWORK_OWNERSHIP_VIOLATION"));
        assertEquals("local-user-file", Files.readString(file));
    }

    @Test
    void rejectsChangedReceiptAndNeverTreatsLocalUserBytesAsDownload() throws Exception {
        Files.writeString(home.resolve("turboism-agent.jar"), "user-replacement");
        withReceipts(Map.of(
                "turboism-agent.jar",
                entry("turboism-agent.jar", "official-old").sha256()));
        final var trusted = createTrustedManifest(List.of(entry("turboism-agent.jar", "official-new")));
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(home, trusted));
        assertEquals("user-replacement", Files.readString(home.resolve("turboism-agent.jar")));
    }

    @Test
    void signedManifestCannotClaimManagedOrUserFilesWithOfficialPluginNames() throws Exception {
        Files.createDirectories(home.resolve("plugins"));
        Files.createDirectories(home.resolve("state/runtime/plugin-management"));
        final Path metadata = home.resolve("state/runtime/plugin-management/catalog-state.json");
        // A real catalog-management receipt still grants no framework ownership.
        Files.writeString(metadata, "{\"schemaVersion\":2,\"stagedUpdates\":{\"acp\":\"1.0.0\"}}");
        final Path plugin = home.resolve("plugins/acp.jar");
        for (final String contents : List.of("independently-managed", "user-same-name")) {
            Files.writeString(plugin, contents);
            final var valid = createTrustedManifest(List.of(entry("turboism-agent.jar", "new")));
            final String json = new String(valid.manifest().toCanonicalBytes(), StandardCharsets.UTF_8)
                    .replace("turboism-agent.jar", "plugins/acp.jar");
            final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            final Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(testKeyPair.getPrivate());
            signer.update(bytes);
            final String envelope =
                    "{\"format\":\"turboism-update-manifest-sig-v1\",\"schemaVersion\":1,\"algorithm\":\"Ed25519\",\"keyId\":\"test-key-v1\",\"manifestSha256\":\""
                            + HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256").digest(bytes))
                            + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signer.sign()) + "\"}";
            final var error = assertThrows(
                    UpdateManifestVerifier.UpdateManifestVerificationException.class,
                    () -> verifier.verify(
                            bytes, envelope.getBytes(StandardCharsets.UTF_8), defaultContext, Instant.EPOCH));
            assertEquals("MALFORMED_MANIFEST", error.errorCode());
            assertTrue(error.getMessage().contains("FRAMEWORK_OWNERSHIP_VIOLATION"));
            assertEquals(contents, Files.readString(plugin));
        }
        assertTrue(Files.readString(metadata).contains("stagedUpdates"));
    }

    @Test
    void rejectsNonDirectoryRootAncestorsAndTargetParents() throws Exception {
        final Path file = Files.writeString(home.resolve("file"), "not-directory");
        final var trusted = createTrustedManifest(List.of(entry("turboism-agent.jar", "new")));
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(file.resolve("child"), trusted));
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(file, trusted));
        Files.writeString(home.resolve("graal"), "not-directory");
        final var graal = createTrustedManifest(List.of(entry("graal/lib/a.jar", "new")));
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(home, graal));
    }

    @Test
    void rejectsDirectoryTargetAndStaticTargetOrParentLinks() throws Exception {
        final Path external = Files.createDirectory(home.resolve("external"));
        final var trusted = createTrustedManifest(List.of(entry("turboism-agent.jar", "new")));
        Files.createDirectory(home.resolve("turboism-agent.jar"));
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(home, trusted));
        Files.delete(home.resolve("turboism-agent.jar"));
        try {
            Files.createSymbolicLink(home.resolve("turboism-agent.jar"), external.resolve("missing"));
            Files.createSymbolicLink(home.resolve("graal"), external);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Host cannot create symlinks: " + unavailable);
        }
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(home, trusted));
        final var graal = createTrustedManifest(List.of(entry("graal/lib/a.jar", "new")));
        assertThrows(IOException.class, () -> FrameworkDeltaPlanner.plan(home, graal));
    }

    @Test
    void missingInstallationIsReadOnlyAndPrecancelWinsBeforeRootScan() throws Exception {
        final Path absent = home.resolve("missing/app");
        final var trusted = createTrustedManifest(List.of(entry("turboism-agent.jar", "new")));
        assertEquals(
                FrameworkDeltaPlanner.Action.DOWNLOAD,
                FrameworkDeltaPlanner.plan(absent, trusted).decisions().get(0).action());
        assertTrue(Files.notExists(home.resolve("missing")));
        final Path file = Files.writeString(home.resolve("file"), "x");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> FrameworkDeltaPlanner.plan(file.resolve("child"), trusted));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void attributeAccessErrorsAreNotMissingFiles() throws Exception {
        Assumptions.assumeTrue(
                Files.getFileStore(home).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        final Path blocked = Files.createDirectory(home.resolve("blocked"));
        final var permissions = Files.getPosixFilePermissions(blocked);
        final var trusted = createTrustedManifest(List.of(entry("turboism-agent.jar", "new")));
        try {
            Files.setPosixFilePermissions(blocked, java.util.Set.of());
            // root or capability-enabled test users can bypass DAC; label that case explicitly.
            Assumptions.assumeFalse(Files.isExecutable(blocked), "Test user bypasses directory permission denial");
            assertThrows(java.nio.file.AccessDeniedException.class, () -> FrameworkDeltaPlanner.plan(blocked, trusted));
        } finally {
            Files.setPosixFilePermissions(blocked, permissions);
        }
    }

    @Test
    void cancelsDuringHashingWithoutReturningPartialPlan() throws Exception {
        final Path agent = home.resolve("turboism-agent.jar");
        // A sparse regular file makes entry into streaming hashing observable without a large allocation.
        try (var channel = java.nio.channels.FileChannel.open(
                agent, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(128L * 1024 * 1024 - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        withReceipts(Map.of("turboism-agent.jar", "a".repeat(64)));
        final var trusted = createTrustedManifest(List.of(entry("turboism-agent.jar", "new")));
        final var outcome = new java.util.concurrent.atomic.AtomicReference<Object>();
        final Thread worker = new Thread(() -> {
            try {
                outcome.set(FrameworkDeltaPlanner.plan(home, trusted));
            } catch (Exception failure) {
                outcome.set(failure);
            } finally {
                if (Thread.currentThread().isInterrupted()) outcome.set(List.of(outcome.get(), true));
            }
        });
        worker.start();
        boolean observedHash = false;
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try {
            while (worker.isAlive() && System.nanoTime() < deadline) {
                if (java.util.Arrays.stream(worker.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(FrameworkDeltaPlanner.class.getName())
                                && frame.getMethodName().equals("sha256"))) {
                    observedHash = true;
                    worker.interrupt();
                    break;
                }
                Thread.yield();
            }
        } finally {
            worker.join(5000);
            if (worker.isAlive()) worker.interrupt();
        }
        assertTrue(observedHash, "Must exercise interruption while hashing");
        assertTrue(outcome.get() instanceof List<?>);
        final List<?> interrupted = (List<?>) outcome.get();
        assertTrue(interrupted.get(0) instanceof InterruptedException);
        assertEquals(true, interrupted.get(1));
    }

    private void withReceipts(final Map<String, String> receipts) {
        defaultContext = new UpdateVerificationContext(
                defaultContext.expectedVersion(),
                defaultContext.expectedChannel(),
                defaultContext.expectedBuildNumber(),
                defaultContext.expectedSourceRevision(),
                defaultContext.currentUpdaterVersion(),
                defaultContext.supportedPlatform(),
                defaultContext.installedBuild(),
                receipts);
    }

    private static UpdateManifestEntry entry(final String path, final String contents) {
        final byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        final byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        final String hex = HexFormat.of().formatHex(hash);
        return new UpdateManifestEntry(path, bytes.length, hex, "sha256:" + hex);
    }
}
