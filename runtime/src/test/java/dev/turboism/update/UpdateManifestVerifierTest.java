package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class UpdateManifestVerifierTest {

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

    private static String validManifestJson() {
        return """
                {
                  "schemaVersion": 1,
                  "keyId": "test-key-v1",
                  "version": "0.68.0",
                  "channel": "stable",
                  "buildNumber": 50,
                  "sourceRevision": "0123456789abcdef0123456789abcdef01234567",
                  "minimumUpdaterVersion": "0.67.0",
                  "targetPlatform": "windows-x64",
                  "entries": [
                    {
                      "relativePath": "turboism-agent.jar",
                      "bytes": 1024,
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "contentId": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    }
                  ]
                }
                """;
    }

    @Test
    void verifiesValidManifestSuccessfully() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final TrustedUpdateManifest trusted =
                verifier.verify(manifestBytes, envelopeBytes, defaultContext, Instant.now());
        assertNotNull(trusted);
        assertEquals("0.68.0", trusted.version());
        assertEquals("stable", trusted.channel());
        assertEquals(50L, trusted.buildNumber());
        assertEquals("0123456789abcdef0123456789abcdef01234567", trusted.sourceRevision());
        assertEquals("test-key-v1", trusted.verifiedKeyId());
        assertEquals(1, trusted.entries().size());
        assertEquals("turboism-agent.jar", trusted.entries().get(0).relativePath());
    }

    @Test
    void canonicalSerializationIsDeterministicAndRoundTrippable() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final UpdateManifest manifest = UpdateManifest.parse(manifestBytes);
        final byte[] canonical1 = manifest.toCanonicalBytes();
        final byte[] canonical2 = manifest.toCanonicalBytes();

        assertArrayEquals(canonical1, canonical2, "serialization must be byte-for-byte deterministic");

        final UpdateManifest roundTripped = UpdateManifest.parse(canonical1);
        assertEquals(manifest, roundTripped);
        assertEquals(manifest.schemaVersion(), roundTripped.schemaVersion());
        assertEquals(manifest.keyId(), roundTripped.keyId());
        assertEquals(manifest.version(), roundTripped.version());
        assertEquals(manifest.buildNumber(), roundTripped.buildNumber());
        assertEquals(manifest.entries().size(), roundTripped.entries().size());
    }

    @Test
    void rejectsExpectedVersionMismatch() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final UpdateVerificationContext context = new UpdateVerificationContext(
                "0.69.0",
                "stable",
                50L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.67.4",
                "windows-x64",
                InstalledBuild.of("0.67.4", java.util.OptionalLong.of(49), "0.67.4"),
                Map.of());
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, context, Instant.now()));
        assertEquals("VERSION_MISMATCH", ex.errorCode());
    }

    @Test
    void rejectsExpectedChannelMismatch() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final UpdateVerificationContext context = new UpdateVerificationContext(
                "0.68.0",
                "beta",
                50L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.67.4",
                "windows-x64",
                InstalledBuild.of("0.67.4", java.util.OptionalLong.of(49), "0.67.4"),
                Map.of());
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, context, Instant.now()));
        assertEquals("CHANNEL_MISMATCH", ex.errorCode());
    }

    @Test
    void rejectsExpectedBuildNumberMismatch() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final UpdateVerificationContext context = new UpdateVerificationContext(
                "0.68.0",
                "stable",
                51L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.67.4",
                "windows-x64",
                InstalledBuild.of("0.67.4", java.util.OptionalLong.of(49), "0.67.4"),
                Map.of());
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, context, Instant.now()));
        assertEquals("BUILD_NUMBER_MISMATCH", ex.errorCode());
    }

    @Test
    void rejectsExpectedSourceRevisionMismatch() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final UpdateVerificationContext context = new UpdateVerificationContext(
                "0.68.0",
                "stable",
                50L,
                "ffffffffffffffffffffffffffffffffffffffff",
                "0.67.4",
                "windows-x64",
                InstalledBuild.of("0.67.4", java.util.OptionalLong.of(49), "0.67.4"),
                Map.of());
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, context, Instant.now()));
        assertEquals("SOURCE_REVISION_MISMATCH", ex.errorCode());
    }

    @Test
    void rejectsWhenUpdaterVersionIsTooOld() throws Exception {
        final String manifestJson = validManifestJson().replace("\"0.67.0\"", "\"0.68.0\"");
        final byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        // Current updater is 0.67.4, which is older than minimumUpdaterVersion 0.68.0
        final UpdateVerificationContext context = new UpdateVerificationContext(
                "0.68.0",
                "stable",
                50L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.67.4",
                "windows-x64",
                InstalledBuild.of("0.67.4", java.util.OptionalLong.of(49), "0.67.4"),
                Map.of());
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, context, Instant.now()));
        assertEquals("UPDATER_VERSION_TOO_OLD", ex.errorCode());
    }

    @Test
    void rejectsUnsupportedTargetPlatform() throws Exception {
        final String manifestJson = validManifestJson().replace("\"windows-x64\"", "\"all\"");
        final byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        // Target platform is 'all' while host requires strict windows-x64
        final UpdateVerificationContext context = new UpdateVerificationContext(
                "0.68.0",
                "stable",
                50L,
                "0123456789abcdef0123456789abcdef01234567",
                "0.67.4",
                "linux-x64",
                InstalledBuild.of("0.67.4", java.util.OptionalLong.of(49), "0.67.4"),
                Map.of());
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, context, Instant.now()));
        assertEquals("PLATFORM_UNSUPPORTED", ex.errorCode());
    }

    @Test
    void rejectsFloatNumbersInIntegerFields() throws Exception {
        final String floatJson = validManifestJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.5");
        final byte[] manifestBytes = floatJson.getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, defaultContext, Instant.now()));
        assertEquals("MALFORMED_MANIFEST", ex.errorCode());
    }

    @Test
    void rejectsDuplicateKeysInJson() throws Exception {
        final String duplicateKeyJson = """
                {
                  "schemaVersion": 1,
                  "schemaVersion": 1,
                  "keyId": "test-key-v1",
                  "version": "0.68.0",
                  "channel": "stable",
                  "buildNumber": 50,
                  "sourceRevision": "0123456789abcdef0123456789abcdef01234567",
                  "minimumUpdaterVersion": "0.67.0",
                  "targetPlatform": "windows-x64",
                  "entries": []
                }
                """;
        final byte[] manifestBytes = duplicateKeyJson.getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, defaultContext, Instant.now()));
        assertEquals("MALFORMED_MANIFEST", ex.errorCode());
    }

    @Test
    void rejectsTrailingGarbageInJson() throws Exception {
        final String garbageJson = validManifestJson() + "   extra_garbage";
        final byte[] manifestBytes = garbageJson.getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(manifestBytes, envelopeBytes, defaultContext, Instant.now()));
        assertEquals("MALFORMED_MANIFEST", ex.errorCode());
    }

    @Test
    void rejectsOversizedManifestBeforeHashAndSignature() {
        final byte[] oversized = new byte[UpdateManifest.MAX_MANIFEST_BYTES + 1];
        final byte[] dummyEnvelope = "{}".getBytes(StandardCharsets.UTF_8);

        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(oversized, dummyEnvelope, defaultContext, Instant.now()));
        assertEquals("MANIFEST_SIZE_INVALID", ex.errorCode());
    }

    @Test
    void rejectsTamperedManifestBytes() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] envelopeBytes = createEnvelope("test-key-v1", manifestBytes, testKeyPair);

        final byte[] tampered = validManifestJson().replace("0.68.0", "0.69.0").getBytes(StandardCharsets.UTF_8);
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(tampered, envelopeBytes, defaultContext, Instant.now()));
        assertEquals("MANIFEST_HASH_MISMATCH", ex.errorCode());
    }

    @Test
    void rejectsUnknownSigningKey() throws Exception {
        final byte[] manifestBytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final String envelopeJson = """
                {
                  "format": "turboism-update-manifest-sig-v1",
                  "schemaVersion": 1,
                  "algorithm": "Ed25519",
                  "keyId": "unrecognized-key-id",
                  "manifestSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "signature": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
                }
                """;
        final var ex = assertThrows(
                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                () -> verifier.verify(
                        manifestBytes, envelopeJson.getBytes(StandardCharsets.UTF_8), defaultContext, Instant.now()));
        assertEquals("UNKNOWN_KEY", ex.errorCode());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "graal/lib/con.jar",
                "plugins/aux.jar",
                "graal/lib/NUL.jar",
                "graal/lib/prn.jar",
                "plugins/com1.jar"
            })
    void rejectsWindowsReservedDeviceNameInManifestEntries(final String badPath) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateManifestEntry(badPath, 100, "a".repeat(64), "sha256:" + "a".repeat(64)));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private void rejectsSigned(final byte[] bytes, final String code) throws Exception {
        final byte[] envelope = createEnvelope("test-key-v1", bytes, testKeyPair);
        assertEquals(
                code,
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> verifier.verify(bytes, envelope, defaultContext, Instant.EPOCH))
                        .errorCode());
    }

    private void withInstalled(final String version, final String channel, final OptionalLong build) {
        defaultContext = new UpdateVerificationContext(
                defaultContext.expectedVersion(),
                defaultContext.expectedChannel(),
                defaultContext.expectedBuildNumber(),
                defaultContext.expectedSourceRevision(),
                "99.0.0",
                defaultContext.supportedPlatform(),
                InstalledBuild.of(version, channel, build, version),
                Map.of());
    }

    @ParameterizedTest
    @CsvSource({
        "0.67.4,51,BUILD_NOT_NEWER",
        "0.67.4,50,BUILD_NOT_NEWER",
        "0.68.0,50,BUILD_NOT_NEWER",
        "0.69.0,50,VERSION_DOWNGRADE",
        "0.69.0,1,VERSION_DOWNGRADE"
    })
    void rejectsDowngradeBuildNumber(final String installedVersion, final long installedBuild, final String code)
            throws Exception {
        withInstalled(installedVersion, "stable", OptionalLong.of(installedBuild));
        rejectsSigned(validManifestJson().getBytes(StandardCharsets.UTF_8), code);
    }

    @Test
    void rejectsSameBuildWithDifferentSourceEvenWhenExpectedMatches() throws Exception {
        withInstalled("0.68.0", "stable", OptionalLong.of(50));
        final String source = "f".repeat(40);
        defaultContext = new UpdateVerificationContext(
                "0.68.0", "stable", 50, source, "99.0.0", "windows-x64", defaultContext.installedBuild(), Map.of());
        rejectsSigned(
                validManifestJson().replace(defaultContextSource(), source).getBytes(StandardCharsets.UTF_8),
                "BUILD_NOT_NEWER");
    }

    private static String defaultContextSource() {
        return "0123456789abcdef0123456789abcdef01234567";
    }

    @Test
    void checksHistoricalAndUnknownInstalledIdentityIndependentlyOfUpdater() throws Exception {
        withInstalled("0.67.4", "stable", OptionalLong.empty());
        final byte[] bytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        assertNotNull(verifier.verify(
                bytes, createEnvelope("test-key-v1", bytes, testKeyPair), defaultContext, Instant.EPOCH));
        withInstalled("0.68.0", "stable", OptionalLong.empty());
        rejectsSigned(bytes, "VERSION_NOT_NEWER");
        withInstalled("unknown", "stable", OptionalLong.of(1));
        rejectsSigned(bytes, "INSTALLED_IDENTITY_UNKNOWN");
        withInstalled("0.67.4", "beta", OptionalLong.of(49));
        rejectsSigned(bytes, "INSTALLED_CHANNEL_MISMATCH");
    }

    @ParameterizedTest
    @CsvSource({
        "schemaVersion,4294967297",
        "schemaVersion,18446744073709551617",
        "schemaVersion,1.0",
        "buildNumber,18446744073709551666",
        "buildNumber,50.5",
        "bytes,18446744073709552640",
        "bytes,1024.5",
        "bytes,-1",
        "bytes,536870913"
    })
    void rejectsSignedMaliciousIntegerFields(final String field, final String number) throws Exception {
        final String original =
                switch (field) {
                    case "schemaVersion" -> "1";
                    case "buildNumber" -> "50";
                    default -> "1024";
                };
        rejectsSigned(
                validManifestJson()
                        .replace("\"" + field + "\": " + original, "\"" + field + "\": " + number)
                        .getBytes(StandardCharsets.UTF_8),
                "MALFORMED_MANIFEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "18446744073709551617", "1.0"})
    void rejectsEnvelopeIntegerOverflow(final String number) throws Exception {
        final byte[] bytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final String envelope = new String(createEnvelope("test-key-v1", bytes, testKeyPair), StandardCharsets.UTF_8);
        final byte[] malicious = envelope.replace("\"schemaVersion\": 1", "\"schemaVersion\": " + number)
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "UNSUPPORTED_SCHEMA_VERSION",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> verifier.verify(bytes, malicious, defaultContext, Instant.EPOCH))
                        .errorCode());
    }

    @Test
    void rejectsInvalidSignatureWithMatchingHash() throws Exception {
        final byte[] bytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final KeyPair wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final byte[] envelope = createEnvelope("test-key-v1", bytes, wrongKey);
        assertEquals(
                "SIGNATURE_INVALID",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> verifier.verify(bytes, envelope, defaultContext, Instant.EPOCH))
                        .errorCode());
    }

    @Test
    void supportsExplicitKeyRotationAndChecksInternalKeyIdentity() throws Exception {
        final KeyPair next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        verifier = new UpdateManifestVerifier(
                Map.of("test-key-v1", testKeyPair.getPublic(), "test-key-v2", next.getPublic()));
        final byte[] old = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] rotated =
                validManifestJson().replace("test-key-v1", "test-key-v2").getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "test-key-v2",
                verifier.verify(rotated, createEnvelope("test-key-v2", rotated, next), defaultContext, Instant.EPOCH)
                        .verifiedKeyId());
        assertNotNull(
                verifier.verify(old, createEnvelope("test-key-v1", old, testKeyPair), defaultContext, Instant.EPOCH));
        assertEquals(
                "KEY_ID_MISMATCH",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> verifier.verify(
                                        old, createEnvelope("test-key-v2", old, next), defaultContext, Instant.EPOCH))
                        .errorCode());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "plugins/acp.jar",
                "plugins/user-custom.jar",
                "config.json",
                "../turboism-agent.jar",
                "graal/lib/../user.jar",
                "graal/lib/foo..bar.jar",
                "graal/lib/a:stream.jar",
                "graal/lib/a.jar.",
                "graal/lib//a.jar"
            })
    void rejectsSignedOwnershipAndPathViolations(final String path) throws Exception {
        rejectsSigned(
                validManifestJson().replace("turboism-agent.jar", path).getBytes(StandardCharsets.UTF_8),
                "MALFORMED_MANIFEST");
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevelAndBoundsEntryCount() throws Exception {
        final ObjectNode root = (ObjectNode) JSON.readTree(validManifestJson());
        root.put("future", true);
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
        root.remove("future");
        ((ObjectNode) root.withArray("entries").get(0)).put("future", true);
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
        final var entry = root.withArray("entries").get(0).deepCopy();
        root.withArray("entries").removeAll();
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
        for (int i = 0; i <= UpdateManifest.MAX_ENTRIES; i++)
            root.withArray("entries").add(entry);
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
    }

    @Test
    void rejectsMalformedUtf8AndDuplicatePaths() throws Exception {
        final byte[] bytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] invalid = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        invalid[invalid.length - 1] = (byte) 0x80;
        rejectsSigned(invalid, "MALFORMED_MANIFEST");
        final ObjectNode root = (ObjectNode) JSON.readTree(bytes);
        root.withArray("entries").add(root.withArray("entries").get(0).deepCopy());
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
        root.withArray("entries").removeAll();
        final ObjectNode lower = root.withArray("entries").addObject();
        lower.put("relativePath", "graal/lib/a.jar");
        lower.put("bytes", 0);
        lower.put("sha256", "a".repeat(64));
        lower.put("contentId", "sha256:" + "a".repeat(64));
        root.withArray("entries").add(lower.deepCopy().put("relativePath", "graal/lib/A.jar"));
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
    }

    @Test
    void rejectsMissingOversizedAndMalformedEnvelopeFields() throws Exception {
        final byte[] bytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] good = createEnvelope("test-key-v1", bytes, testKeyPair);
        assertEquals("ENVELOPE_SIZE_INVALID", envelopeFailure(bytes, new byte[0]));
        assertEquals(
                "ENVELOPE_SIZE_INVALID",
                envelopeFailure(bytes, new byte[UpdateManifestVerifier.MAX_ENVELOPE_BYTES + 1]));
        final ObjectNode envelope = (ObjectNode) JSON.readTree(good);
        envelope.put("algorithm", "RSA");
        assertEquals("UNSUPPORTED_ALGORITHM", envelopeFailure(bytes, JSON.writeValueAsBytes(envelope)));
        envelope.put("algorithm", "Ed25519");
        envelope.remove("signature");
        assertEquals("MISSING_ENVELOPE_FIELD", envelopeFailure(bytes, JSON.writeValueAsBytes(envelope)));
        envelope.put("signature", "bad!");
        assertEquals("INVALID_SIGNATURE_BASE64", envelopeFailure(bytes, JSON.writeValueAsBytes(envelope)));
        envelope.put("signature", "AA==");
        assertEquals("INVALID_SIGNATURE_LENGTH", envelopeFailure(bytes, JSON.writeValueAsBytes(envelope)));
        envelope.put("future", true);
        assertEquals("MALFORMED_ENVELOPE", envelopeFailure(bytes, JSON.writeValueAsBytes(envelope)));
        assertEquals(
                "MALFORMED_ENVELOPE",
                envelopeFailure(
                        bytes, (new String(good, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                "MALFORMED_ENVELOPE",
                envelopeFailure(
                        bytes,
                        new String(good, StandardCharsets.UTF_8)
                                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1")
                                .getBytes(StandardCharsets.UTF_8)));
    }

    private String envelopeFailure(final byte[] bytes, final byte[] envelope) {
        return assertThrows(
                        UpdateManifestVerifier.UpdateManifestVerificationException.class,
                        () -> verifier.verify(bytes, envelope, defaultContext, Instant.EPOCH))
                .errorCode();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.68.0-beta.1", "00.68.0", "2147483648.0.0", "0.68.0-unknown"})
    void rejectsNoncanonicalOrChannelInconsistentVersions(final String version) throws Exception {
        rejectsSigned(
                validManifestJson().replace("0.68.0", version).getBytes(StandardCharsets.UTF_8), "MALFORMED_MANIFEST");
    }

    @Test
    void hasFixedByteVectorAndIgnoresInputOrdering() throws Exception {
        final UpdateManifest manifest = UpdateManifest.parse(validManifestJson().getBytes(StandardCharsets.UTF_8));
        final String hash = "a".repeat(64);
        final String expected =
                "{\"buildNumber\":50,\"channel\":\"stable\",\"entries\":[{\"bytes\":1024,\"contentId\":\"sha256:" + hash
                        + "\",\"relativePath\":\"turboism-agent.jar\",\"sha256\":\"" + hash
                        + "\"}],\"keyId\":\"test-key-v1\",\"minimumUpdaterVersion\":\"0.67.0\",\"schemaVersion\":1,\"sourceRevision\":\"0123456789abcdef0123456789abcdef01234567\",\"targetPlatform\":\"windows-x64\",\"version\":\"0.68.0\"}";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), manifest.toCanonicalBytes());
        final var entries = new ArrayList<>(manifest.entries());
        entries.add(new UpdateManifestEntry("graal/lib/b.jar", 0, hash, "sha256:" + hash));
        entries.add(new UpdateManifestEntry("graal/lib/a.jar", 0, hash, "sha256:" + hash));
        byte[] first = null;
        for (int i = 0; i < entries.size(); i++) {
            Collections.rotate(entries, 1);
            final UpdateManifest reordered = new UpdateManifest(
                    1,
                    manifest.keyId(),
                    manifest.version(),
                    manifest.channel(),
                    manifest.buildNumber(),
                    manifest.sourceRevision(),
                    manifest.minimumUpdaterVersion(),
                    manifest.targetPlatform(),
                    entries);
            if (first == null) first = reordered.toCanonicalBytes();
            assertArrayEquals(first, reordered.toCanonicalBytes());
            assertEquals(reordered, UpdateManifest.parse(first));
        }
    }

    @Test
    void verifiesFixedPublicSignatureVectorOverDeterministicBytes() throws Exception {
        // Public RFC 8032 test key; signature computed independently with Python cryptography.
        final byte[] publicKey = HexFormat.of()
                .parseHex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        final var key = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(publicKey));
        final byte[] signature = HexFormat.of()
                .parseHex(
                        "1d9c460df3987afa98e4fcc4bf10f5978937ee51a942c3539e4c88106c73bd68b07417829d066f18e957e7660c08a306b619a19094cceb8739232f095286a80d");
        final byte[] bytes = UpdateManifest.parse(validManifestJson().getBytes(StandardCharsets.UTF_8))
                .toCanonicalBytes();
        final ObjectNode envelope = JSON.createObjectNode();
        envelope.put("format", UpdateManifestVerifier.SIGNATURE_FORMAT);
        envelope.put("schemaVersion", 1);
        envelope.put("algorithm", "Ed25519");
        envelope.put("keyId", "test-key-v1");
        envelope.put(
                "manifestSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        envelope.put("signature", Base64.getEncoder().encodeToString(signature));
        final var fixedVerifier = new UpdateManifestVerifier(Map.of("test-key-v1", key));
        assertEquals(
                Instant.EPOCH,
                fixedVerifier
                        .verify(bytes, JSON.writeValueAsBytes(envelope), defaultContext, Instant.EPOCH)
                        .verifiedAt());
        // Reformatting preserves JSON meaning but invalidates a signature over the published bytes.
        final byte[] pretty = validManifestJson().getBytes(StandardCharsets.UTF_8);
        envelope.put(
                "manifestSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pretty)));
        assertEquals(
                "SIGNATURE_INVALID",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> fixedVerifier.verify(
                                        pretty, JSON.writeValueAsBytes(envelope), defaultContext, Instant.EPOCH))
                        .errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"keyId", "version", "channel", "sourceRevision", "minimumUpdaterVersion", "targetPlatform"})
    void rejectsWrongTextTypesAndMissingFields(final String field) throws Exception {
        final ObjectNode root = (ObjectNode) JSON.readTree(validManifestJson());
        root.put(field, 123);
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
        root.remove(field);
        rejectsSigned(JSON.writeValueAsBytes(root), "MALFORMED_MANIFEST");
    }

    @Test
    void constructionAndSerializationShareBoundsAtMaximumEntryCount() throws Exception {
        final var entries = new ArrayList<UpdateManifestEntry>();
        for (int i = 0; i < UpdateManifest.MAX_ENTRIES; i++) {
            entries.add(new UpdateManifestEntry(
                    "graal/lib/" + "x".repeat(215) + i + ".jar",
                    UpdateManifest.MAX_FILE_BYTES,
                    "a".repeat(64),
                    "sha256:" + "a".repeat(64)));
        }
        final var manifest = new UpdateManifest(
                1,
                "k".repeat(64),
                "0.68.0",
                "stable",
                UpdateDiscovery.MAX_BUILD_NUMBER,
                "f".repeat(40),
                "0.67.0-" + "x".repeat(120),
                "windows-x64",
                entries);
        final byte[] bytes = manifest.toCanonicalBytes();
        assertTrue(bytes.length <= UpdateManifest.MAX_MANIFEST_BYTES);
        assertEquals(manifest, UpdateManifest.parse(bytes));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateManifestEntry(
                        "graal/lib/" + "x".repeat(240) + ".jar", 0, "a".repeat(64), "sha256:" + "a".repeat(64)));
        entries.add(entries.get(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateManifest(
                        1,
                        "test-key-v1",
                        "0.68.0",
                        "stable",
                        50,
                        defaultContextSource(),
                        "0.67.0",
                        "windows-x64",
                        entries));
    }

    @ParameterizedTest
    @CsvSource({"beta,0.68.0-beta.1", "beta,0.68.0-rc.0", "beta,0.68.0", "nightly,0.68.0-0.nightly.1", "nightly,0.68.0"
    })
    void admitsExistingDiscoveryChannelForms(final String channel, final String version) throws Exception {
        final byte[] bytes = validManifestJson()
                .replace("stable", channel)
                .replace("0.68.0", version)
                .getBytes(StandardCharsets.UTF_8);
        final var context = new UpdateVerificationContext(
                version,
                channel,
                50,
                defaultContextSource(),
                "0.67.4",
                "windows-x64",
                InstalledBuild.of("0.67.4", channel, OptionalLong.of(49), "0.67.4"),
                Map.of());
        assertNotNull(
                verifier.verify(bytes, createEnvelope("test-key-v1", bytes, testKeyPair), context, Instant.EPOCH));
    }

    @Test
    void authenticatesOldReceiptsSeparatelyFromNewReleaseAdmission() throws Exception {
        final byte[] bytes = validManifestJson().getBytes(StandardCharsets.UTF_8);
        final byte[] envelope = createEnvelope("test-key-v1", bytes, testKeyPair);
        withInstalled("0.68.0", "stable", OptionalLong.of(50));
        assertEquals(50L, verifier.authenticate(bytes, envelope).buildNumber());
        assertEquals(
                "BUILD_NOT_NEWER",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> verifier.verify(bytes, envelope, defaultContext, Instant.EPOCH))
                        .errorCode());
        final byte[] badSignature = createEnvelope(
                "test-key-v1", bytes, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertEquals(
                "SIGNATURE_INVALID",
                assertThrows(
                                UpdateManifestVerifier.UpdateManifestVerificationException.class,
                                () -> verifier.authenticate(bytes, badSignature))
                        .errorCode());
    }

    @Test
    void consumesRealPythonReleaseToolOutput(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path output)
            throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null
                && !java.nio.file.Files.isRegularFile(root.resolve("scripts/release/build-framework-update.py"))) {
            root = root.getParent();
        }
        assertNotNull(root, "Must find repository fixture generator");
        final Process process = new ProcessBuilder(
                        "python3",
                        "-c",
                        "from pathlib import Path; import sys; from scripts.test.test_release_tooling import write_framework_java_fixture; write_framework_java_fixture(Path(sys.argv[1]))",
                        output.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Offline fixture timed out");
            assertEquals(
                    0,
                    process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
        final var keys = JSON.readTree(java.nio.file.Files.readAllBytes(output.resolve("public-keys.json")));
        final var publicKey = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(
                        Base64.getDecoder().decode(keys.get("offline-test").textValue())));
        final var toolVerifier = new UpdateManifestVerifier(Map.of("offline-test", publicKey));
        final byte[] bytes = java.nio.file.Files.readAllBytes(output.resolve("turboism-0.68.0-manifest.json"));
        final byte[] envelope = java.nio.file.Files.readAllBytes(output.resolve("turboism-0.68.0-manifest.json.sig"));
        final var trusted = toolVerifier.verify(bytes, envelope, defaultContext, Instant.EPOCH);
        assertArrayEquals(bytes, trusted.manifest().toCanonicalBytes(), "Python and Java bytes must match exactly");
        assertEquals("0.68.0", trusted.version());
        final var feed = UpdateDiscoveryParserTest.incrementalDocument();
        final var release = (ObjectNode) feed.get("release");
        release.put("version", trusted.version()).put("sourceRevision", defaultContextSource());
        ((ObjectNode) release.get("incremental"))
                .set(
                        "descriptor",
                        JSON.readTree(
                                java.nio.file.Files.readAllBytes(output.resolve("turboism-0.68.0-incremental.json"))));
        final var discovered =
                new UpdateDiscoveryParser().parseNode(feed).candidate().orElseThrow();
        assertEquals(trusted.sourceRevision(), discovered.sourceRevision().orElseThrow());
        assertEquals(
                bytes.length, discovered.incremental().orElseThrow().manifest().bytes());
        assertEquals(
                java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                discovered.incremental().orElseThrow().manifest().sha256());
        final var layout =
                JSON.readTree(java.nio.file.Files.readAllBytes(root.resolve("packaging/framework-update-layout.json")));
        for (final var path : layout.get("frameworkRootFiles")) {
            assertTrue(
                    trusted.entries().stream()
                            .anyMatch(entry -> entry.relativePath().equals(path.textValue())),
                    "Complete package root mapping: " + path);
        }
        assertTrue(trusted.entries().stream()
                .noneMatch(entry -> entry.relativePath().startsWith("plugins/")
                        || entry.relativePath().equals("config.json")
                        || entry.relativePath().equals("installed-build.json")));
    }

    private static byte[] createEnvelope(final String keyId, final byte[] manifestBytes, final KeyPair keyPair)
            throws Exception {
        final byte[] hash = MessageDigest.getInstance("SHA-256").digest(manifestBytes);
        final String sha256Hex = HexFormat.of().formatHex(hash);

        final Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifestBytes);
        final byte[] signature = signer.sign();

        final String envelopeJson =
                String.format("""
                {
                  "format": "turboism-update-manifest-sig-v1",
                  "schemaVersion": 1,
                  "algorithm": "Ed25519",
                  "keyId": "%s",
                  "manifestSha256": "%s",
                  "signature": "%s"
                }
                """, keyId, sha256Hex, Base64.getEncoder().encodeToString(signature));
        return envelopeJson.getBytes(StandardCharsets.UTF_8);
    }
}
