package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression protection for the signed framework update receipt and the surrounding transaction
 * state (#111).
 *
 * <p>The committed receipt is signed with a clearly marked {@code test-key-reproducible} Ed25519
 * key. It is not a historical Turboism release signature and must never be treated as provenance
 * for a published artifact. The test only verifies existing bytes through the production
 * {@link UpdateManifestVerifier}; it never re-signs a historical receipt.</p>
 */
class SignedReceiptAndUpdateJournalCompatibilityTest {

    private static final String TEST_KEY_ID = "test-key-reproducible";

    /**
     * Test-only deterministic Ed25519 seed. The corresponding X.509 public key is below; the
     * fixture README documents both. This is not a production or historical release key.
     */
    private static final String TEST_PRIVATE_SEED_HEX =
            "bb40f1e2c19b88de46ee23180259ccd80fd8ea3beee91d81907f52ba38e32a27";

    private static final String TEST_PUBLIC_KEY_X509 = "MCowBQYDK2VwAyEAIRzyTAHu9jIM5GHnFnjcAmMVZEDbCDnZm73tWQXR8N8=";

    private static final String SOURCE_REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final String OLD_VERSION = "0.67.4";
    private static final String NEW_VERSION = "0.68.0";

    @TempDir
    Path temporary;

    private static KeyPair testKeyPair() throws Exception {
        final KeyFactory factory = KeyFactory.getInstance("Ed25519");
        final PrivateKey privateKey = factory.generatePrivate(new EdECPrivateKeySpec(
                NamedParameterSpec.ED25519, HexFormat.of().parseHex(TEST_PRIVATE_SEED_HEX)));
        final PublicKey publicKey = factory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(TEST_PUBLIC_KEY_X509)));
        return new KeyPair(publicKey, privateKey);
    }

    private static byte[] sign(final byte[] manifestBytes, final PrivateKey privateKey) throws Exception {
        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(manifestBytes);
        return signer.sign();
    }

    private static byte[] envelope(final byte[] manifestBytes, final byte[] signature) throws IOException {
        final ObjectNode root = UpdateFileSupport.JSON.createObjectNode();
        root.put("format", UpdateManifestVerifier.SIGNATURE_FORMAT);
        root.put("schemaVersion", 1);
        root.put("algorithm", "Ed25519");
        root.put("keyId", TEST_KEY_ID);
        root.put("manifestSha256", FrameworkUpdateTaskStore.hash(manifestBytes));
        root.put("signature", Base64.getEncoder().encodeToString(signature));
        return UpdateFileSupport.JSON.writeValueAsBytes(root);
    }

    private byte[] resource(final String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/update/test-key-reproducible/" + name)) {
            assertNotNull(input, "missing test fixture " + name);
            return input.readAllBytes();
        }
    }

    @Test
    void committedTestReceiptVerifiesThroughProductionAuthenticatorWithoutMutation() throws Exception {
        final KeyPair keys = testKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of(TEST_KEY_ID, keys.getPublic()));
        final byte[] manifestBytes = resource("signed-receipt-manifest.json");
        final byte[] envelopeBytes = resource("signed-receipt-manifest.json.sig");
        final byte[] manifestBefore = manifestBytes.clone();
        final byte[] envelopeBefore = envelopeBytes.clone();

        final UpdateManifest manifest = verifier.authenticate(manifestBytes, envelopeBytes);

        assertEquals(OLD_VERSION, manifest.version());
        assertEquals("stable", manifest.channel());
        assertEquals(TEST_KEY_ID, manifest.keyId());
        assertEquals(1, manifest.entries().size());
        assertEquals("turboism-agent.jar", manifest.entries().get(0).relativePath());
        assertArrayEquals(manifestBefore, manifestBytes, "verification must not mutate the signed manifest");
        assertArrayEquals(envelopeBefore, envelopeBytes, "verification must not mutate the envelope");

        // The fixture signature is reproducible from the documented test seed; this proves the
        // fixture is a fixed test-key document rather than an opaque historical signature.
        final JsonNode envelope = UpdateFileSupport.JSON.readTree(envelopeBytes);
        assertArrayEquals(
                Base64.getDecoder().decode(envelope.path("signature").asText()),
                sign(manifestBytes, keys.getPrivate()),
                "test-key signature must be deterministic");
    }

    @Test
    void tamperedManifestBytesAreRejectedWithoutRewritingSignedEvidence() throws Exception {
        final KeyPair keys = testKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of(TEST_KEY_ID, keys.getPublic()));
        final byte[] manifestBytes = resource("signed-receipt-manifest.json");
        final byte[] envelopeBytes = resource("signed-receipt-manifest.json.sig");
        final byte[] tampered = manifestBytes.clone();
        tampered[10] ^= 1;

        final var failure = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.authenticate(tampered, envelopeBytes));
        assertEquals("MANIFEST_HASH_MISMATCH", failure.errorCode());
        assertArrayEquals(manifestBytes, resource("signed-receipt-manifest.json"));
        assertArrayEquals(envelopeBytes, resource("signed-receipt-manifest.json.sig"));
    }

    @Test
    void tamperedSignatureEnvelopeIsRejectedWithoutMutatingItsBytes() throws Exception {
        final KeyPair keys = testKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of(TEST_KEY_ID, keys.getPublic()));
        final byte[] manifestBytes = resource("signed-receipt-manifest.json");
        final ObjectNode envelope =
                (ObjectNode) UpdateFileSupport.JSON.readTree(resource("signed-receipt-manifest.json.sig"));
        final byte[] signature =
                Base64.getDecoder().decode(envelope.path("signature").asText());
        signature[0] ^= 1;
        envelope.put("signature", Base64.getEncoder().encodeToString(signature));
        final byte[] tamperedEnvelope = UpdateFileSupport.JSON.writeValueAsBytes(envelope);

        final var failure = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.authenticate(manifestBytes, tamperedEnvelope));
        assertEquals("SIGNATURE_INVALID", failure.errorCode());
        assertArrayEquals(manifestBytes, resource("signed-receipt-manifest.json"));
    }

    @Test
    void revokedOrUnknownTestKeyIsRefusedEvenForValidSignatureBytes() throws Exception {
        final KeyPair keys = testKeyPair();
        final byte[] manifestBytes = resource("signed-receipt-manifest.json");
        final byte[] envelopeBytes = resource("signed-receipt-manifest.json.sig");

        final UpdateManifestVerifier revoked =
                new UpdateManifestVerifier(Map.of(TEST_KEY_ID, keys.getPublic()), Set.of(TEST_KEY_ID));
        assertEquals(
                "REVOKED_KEY",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> revoked.authenticate(manifestBytes, envelopeBytes))
                        .errorCode());

        final UpdateManifestVerifier unknown = new UpdateManifestVerifier(Map.of("another-test-key", keys.getPublic()));
        assertEquals(
                "UNKNOWN_KEY",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> unknown.authenticate(manifestBytes, envelopeBytes))
                        .errorCode());
    }

    @Test
    void unknownReceiptIndexFieldIsRefusedWithoutReplacingTheOriginalIndex() throws Exception {
        final KeyPair keys = testKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of(TEST_KEY_ID, keys.getPublic()));
        final Path root = temporary.resolve("installation");
        ensurePrivateDirectory(root);
        final Path index =
                root.resolve(FrameworkInstallationReceiptLoader.STATE).resolve("installed-receipt.json");
        ensurePrivateDirectory(index.getParent());
        final ObjectNode document = UpdateFileSupport.JSON.createObjectNode();
        document.put("format", FrameworkInstallationReceiptLoader.FORMAT);
        document.put("schemaVersion", 1);
        document.put("layout", FrameworkInstallationReceiptLoader.LAYOUT);
        document.put("manifestSha256", "a".repeat(64));
        document.put("rootIdentity", "test-root");
        document.putObject("futureReceiptField").put("schemaVersion", 2);
        writePrivateFile(index, UpdateFileSupport.JSON.writeValueAsBytes(document));
        final byte[] before = Files.readAllBytes(index);

        assertThrows(IOException.class, () -> FrameworkInstallationReceiptLoader.loadForHelper(root, verifier));
        assertArrayEquals(before, Files.readAllBytes(index), "a refused receipt index must not be replaced");
    }

    @Test
    void failedSignedTransitionRestoresOriginalReceiptIndexIdentityAndPayloadBytes() throws Exception {
        final Path root = temporary.resolve("installation");
        final Path work = temporary.resolve("work");
        final KeyPair keys = testKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of(TEST_KEY_ID, keys.getPublic()));

        final Release oldRelease = release(OLD_VERSION, 49);
        final Release newRelease = release(NEW_VERSION, 50);
        writePayloads(root, oldRelease.payloads());
        final FrameworkUpdateReceipt.Prepared oldReceipt = publishReceipt(root, oldRelease, verifier);
        final byte[] oldIndex = Files.readAllBytes(oldReceipt.indexPath());
        final byte[] oldIdentity =
                "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"0.67.4\"}\n".getBytes(StandardCharsets.UTF_8);
        writePrivateFile(root.resolve(InstalledBuild.METADATA_FILE_NAME), oldIdentity);
        final Path oldManifest = receiptDocument(root, oldRelease, "manifest.json");
        final Path oldEnvelope = receiptDocument(root, oldRelease, "manifest.json.sig");
        final byte[] oldManifestBytes = Files.readAllBytes(oldManifest);
        final byte[] oldEnvelopeBytes = Files.readAllBytes(oldEnvelope);

        final FrameworkInstallationReceiptLoader.Evidence previous =
                FrameworkInstallationReceiptLoader.loadForHelper(root, verifier);
        final Path staged = temporary.resolve("staged");
        writeChangedPayloads(staged, oldRelease, newRelease);
        final FrameworkUpdateReceipt.Prepared newReceipt =
                FrameworkUpdateReceipt.prepare(root, newRelease.manifestBytes(), newRelease.envelopeBytes(), verifier);
        final byte[] newIdentity =
                "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"0.68.0\"}\n".getBytes(StandardCharsets.UTF_8);

        final FrameworkUpdateApplier applier = new FrameworkUpdateApplier(root, work, point -> {
            if (point.equals("before-installed-build")) throw new IOException("injected failure");
        });
        final FrameworkUpdateApplier.Result result =
                applier.applyAuthenticated(newReceipt, previous, staged, newIdentity);

        assertEquals(FrameworkUpdateApplier.Outcome.ROLLED_BACK, result.outcome());
        assertArrayEquals(oldIndex, Files.readAllBytes(oldReceipt.indexPath()), "receipt index must be restored");
        assertArrayEquals(oldIdentity, Files.readAllBytes(root.resolve(InstalledBuild.METADATA_FILE_NAME)));
        assertArrayEquals(oldManifestBytes, Files.readAllBytes(oldManifest));
        assertArrayEquals(oldEnvelopeBytes, Files.readAllBytes(oldEnvelope));
        assertPayloads(root, oldRelease.payloads());
        assertTrue(Files.notExists(root.resolve("graal/lib/sdk-" + NEW_VERSION + ".jar")));
        assertTrue(Files.notExists(root.resolve("graal/lib/graal-host-" + NEW_VERSION + ".jar")));
        assertFalse(Files.exists(work.resolve(FrameworkUpdateApplier.JOURNAL)));

        // The restored receipt still authenticates end-to-end through the production loader.
        final FrameworkInstallationReceiptLoader.Evidence restored =
                FrameworkInstallationReceiptLoader.loadForHelper(root, verifier);
        assertEquals(previous.binding(), restored.binding());
    }

    @Test
    void interruptedSignedTransitionRecoversOriginalReceiptAndIdentityBytes() throws Exception {
        final Path root = temporary.resolve("installation");
        final Path work = temporary.resolve("work");
        final KeyPair keys = testKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of(TEST_KEY_ID, keys.getPublic()));

        final Release oldRelease = release(OLD_VERSION, 49);
        final Release newRelease = release(NEW_VERSION, 50);
        writePayloads(root, oldRelease.payloads());
        final FrameworkUpdateReceipt.Prepared oldReceipt = publishReceipt(root, oldRelease, verifier);
        final byte[] oldIndex = Files.readAllBytes(oldReceipt.indexPath());
        final byte[] oldIdentity =
                "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"0.67.4\"}\n".getBytes(StandardCharsets.UTF_8);
        writePrivateFile(root.resolve(InstalledBuild.METADATA_FILE_NAME), oldIdentity);
        final FrameworkInstallationReceiptLoader.Evidence previous =
                FrameworkInstallationReceiptLoader.loadForHelper(root, verifier);
        final Path staged = temporary.resolve("staged");
        writeChangedPayloads(staged, oldRelease, newRelease);
        final FrameworkUpdateReceipt.Prepared newReceipt =
                FrameworkUpdateReceipt.prepare(root, newRelease.manifestBytes(), newRelease.envelopeBytes(), verifier);
        final byte[] newIdentity =
                "{\"schemaVersion\":1,\"variant\":\"full\",\"version\":\"0.68.0\"}\n".getBytes(StandardCharsets.UTF_8);

        final class Crash extends Error {}
        assertThrows(
                Crash.class,
                () -> new FrameworkUpdateApplier(root, work, point -> {
                            if (point.equals("after-receipt-index")) throw new Crash();
                        })
                        .applyAuthenticated(newReceipt, previous, staged, newIdentity));
        assertTrue(Files.exists(work.resolve(FrameworkUpdateApplier.JOURNAL)), "interrupted journal must survive");

        final FrameworkUpdateApplier.Result recovered = new FrameworkUpdateApplier(root, work).recover();

        assertEquals(FrameworkUpdateApplier.Outcome.RECOVERED, recovered.outcome());
        assertArrayEquals(oldIndex, Files.readAllBytes(oldReceipt.indexPath()));
        assertArrayEquals(oldIdentity, Files.readAllBytes(root.resolve(InstalledBuild.METADATA_FILE_NAME)));
        assertPayloads(root, oldRelease.payloads());
        assertEquals(
                previous.binding(),
                FrameworkInstallationReceiptLoader.loadForHelper(root, verifier).binding());
    }

    @Test
    void corruptFrameworkUpdateJournalIsRejectedAndKeptForRecovery() throws Exception {
        final Path root = temporary.resolve("installation");
        ensurePrivateDirectory(root);
        final Path work = temporary.resolve("work");
        ensurePrivateDirectory(work);
        final byte[] original = """
                {
                  "format": "turboism-update-journal-v1",
                  "state": "APPLYING",
                  "files": [
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(work.resolve(FrameworkUpdateApplier.JOURNAL), original);

        assertThrows(IOException.class, () -> new FrameworkUpdateApplier(root, work).recover());
        assertArrayEquals(original, Files.readAllBytes(work.resolve(FrameworkUpdateApplier.JOURNAL)));
    }

    @Test
    void wrongFrameworkJournalStateOrFormatIsRejectedWithoutRewritingTheJournal() throws Exception {
        final Path root = temporary.resolve("installation");
        ensurePrivateDirectory(root);
        final Path work = temporary.resolve("work");
        ensurePrivateDirectory(work);
        final byte[] original = """
                {
                  "format": "turboism-update-journal-v1",
                  "state": "FUTURE_STATE",
                  "version": "0.68.0",
                  "root": "%s",
                  "files": []
                }
                """.formatted(root).getBytes(StandardCharsets.UTF_8);
        Files.write(work.resolve(FrameworkUpdateApplier.JOURNAL), original);

        assertThrows(IOException.class, () -> new FrameworkUpdateApplier(root, work).recover());
        assertArrayEquals(original, Files.readAllBytes(work.resolve(FrameworkUpdateApplier.JOURNAL)));
    }

    @Test
    void corruptUpdateStateAndPreferencesAreReadAsDefaultsWithoutRewritingBytes() throws Exception {
        final Path updateState = temporary.resolve(UpdateStateStore.FILE_NAME);
        final byte[] corruptState = "{\"schemaVersion\":1,\"etag\":".getBytes(StandardCharsets.UTF_8);
        Files.write(updateState, corruptState);
        final List<String> stateDiagnostics = new ArrayList<>();
        final UpdateStateStore stateStore = new UpdateStateStore(temporary, stateDiagnostics::add);
        assertTrue(stateStore.read().lastAutomaticAttempt().isEmpty());
        assertEquals(List.of("UPDATE_STATE_CORRUPT"), stateDiagnostics);
        assertArrayEquals(corruptState, Files.readAllBytes(updateState));

        final Path preferences = temporary.resolve(UpdatePreferencesStore.FILE_NAME);
        final byte[] corruptPreferences =
                "{\"schemaVersion\":2,\"automaticChecksEnabled\":true}".getBytes(StandardCharsets.UTF_8);
        Files.write(preferences, corruptPreferences);
        final List<String> preferenceDiagnostics = new ArrayList<>();
        final UpdatePreferencesStore preferenceStore =
                new UpdatePreferencesStore(temporary, preferenceDiagnostics::add);
        assertEquals(UpdatePreferencesStore.StoredPreferences.DEFAULT, preferenceStore.readAll());
        assertEquals(List.of("UPDATE_PREFERENCES_CORRUPT"), preferenceDiagnostics);
        assertArrayEquals(corruptPreferences, Files.readAllBytes(preferences));
    }

    private record Release(
            UpdateManifest manifest, byte[] manifestBytes, byte[] envelopeBytes, Map<String, byte[]> payloads) {}

    private static Release release(final String version, final long buildNumber) throws Exception {
        final Map<String, byte[]> payloads = new TreeMap<>();
        for (final String name : UpdateManifestEntry.FRAMEWORK_ROOT_FILES) {
            payloads.put(name, ("root:" + name).getBytes(StandardCharsets.UTF_8));
        }
        payloads.put("turboism-agent.jar", ("agent:" + version).getBytes(StandardCharsets.UTF_8));
        payloads.put("graal/lib/sdk-" + version + ".jar", ("sdk:" + version).getBytes(StandardCharsets.UTF_8));
        payloads.put("graal/lib/graal-host-" + version + ".jar", ("host:" + version).getBytes(StandardCharsets.UTF_8));
        final List<UpdateManifestEntry> entries = payloads.entrySet().stream()
                .map(entry -> new UpdateManifestEntry(
                        entry.getKey(),
                        entry.getValue().length,
                        FrameworkUpdateTaskStore.hash(entry.getValue()),
                        "sha256:" + FrameworkUpdateTaskStore.hash(entry.getValue())))
                .toList();
        final UpdateManifest manifest = new UpdateManifest(
                1, TEST_KEY_ID, version, "stable", buildNumber, SOURCE_REVISION, "0.67.0", "windows-x64", entries);
        final KeyPair keys = testKeyPair();
        final byte[] manifestBytes = manifest.toCanonicalBytes();
        return new Release(
                manifest, manifestBytes, envelope(manifestBytes, sign(manifestBytes, keys.getPrivate())), payloads);
    }

    private static FrameworkUpdateReceipt.Prepared publishReceipt(
            final Path root, final Release release, final UpdateManifestVerifier verifier) throws Exception {
        final FrameworkUpdateReceipt.Prepared receipt =
                FrameworkUpdateReceipt.prepare(root, release.manifestBytes(), release.envelopeBytes(), verifier);
        receipt.publishDocuments();
        writePrivateFile(receipt.indexPath(), receipt.index());
        return receipt;
    }

    private static Path receiptDocument(final Path root, final Release release, final String name) {
        final String digest = FrameworkUpdateTaskStore.hash(release.manifestBytes());
        return root.resolve(FrameworkInstallationReceiptLoader.STATE)
                .resolve("receipts")
                .resolve(digest)
                .resolve(name);
    }

    private static void writePayloads(final Path root, final Map<String, byte[]> payloads) throws IOException {
        ensurePrivateDirectory(root);
        for (final var entry : payloads.entrySet()) {
            final Path target = root.resolve(entry.getKey());
            ensurePrivateDirectory(target.getParent());
            Files.write(target, entry.getValue());
            restrictFile(target);
        }
    }

    private static void writeChangedPayloads(final Path staged, final Release oldRelease, final Release newRelease)
            throws IOException {
        ensurePrivateDirectory(staged);
        for (final var entry : newRelease.payloads().entrySet()) {
            final byte[] old = oldRelease.payloads().get(entry.getKey());
            if (old != null && java.util.Arrays.equals(old, entry.getValue())) continue;
            final Path target = staged.resolve(entry.getKey());
            ensurePrivateDirectory(target.getParent());
            Files.write(target, entry.getValue());
            restrictFile(target);
        }
    }

    private static void assertPayloads(final Path root, final Map<String, byte[]> payloads) throws IOException {
        for (final var entry : payloads.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(root.resolve(entry.getKey())), entry.getKey());
        }
    }

    private static void writePrivateFile(final Path path, final byte[] bytes) throws IOException {
        ensurePrivateDirectory(path.getParent());
        final Path temporary = Files.createTempFile(path.getParent(), ".test-", ".writing");
        try {
            Files.write(temporary, bytes);
            restrictFile(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        restrictFile(path);
    }

    private static void ensurePrivateDirectory(final Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static void restrictFile(final Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
