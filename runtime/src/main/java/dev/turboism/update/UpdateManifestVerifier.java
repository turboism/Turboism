package dev.turboism.update;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cryptographic and release identity verifier for in-Cubism framework update manifests.
 */
public final class UpdateManifestVerifier {

    public static final String SIGNATURE_FORMAT = "turboism-update-manifest-sig-v1";
    public static final int SIGNATURE_SCHEMA_VERSION = 1;
    public static final int MAX_ENVELOPE_BYTES = 16 * 1024; // 16 KiB
    public static final int ED25519_SIGNATURE_BYTES = 64;

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private static final Set<String> REQUIRED_ENVELOPE_KEYS =
            Set.of("format", "schemaVersion", "algorithm", "keyId", "manifestSha256", "signature");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Map<String, PublicKey> trustedKeys;
    private final Set<String> revokedKeys;

    public UpdateManifestVerifier(final Map<String, PublicKey> trustedKeys) {
        this(trustedKeys, Set.of());
    }

    /** Historical keys may remain trusted across rotation; explicit revocation overrides trust. */
    public UpdateManifestVerifier(final Map<String, PublicKey> trustedKeys, final Set<String> revokedKeys) {
        this.revokedKeys = Set.copyOf(revokedKeys);
        this.trustedKeys = Map.copyOf(Objects.requireNonNull(trustedKeys, "trustedKeys"));
        if (this.trustedKeys.isEmpty()) {
            throw new IllegalArgumentException("trustedKeys must not be empty");
        }
    }

    /**
     * Verifies manifest bytes and detached signature envelope against trusted keys and expected context.
     */
    public TrustedUpdateManifest verify(
            final byte[] manifestBytes,
            final byte[] signatureEnvelopeBytes,
            final UpdateVerificationContext context,
            final Instant now)
            throws UpdateManifestVerificationException {

        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(now, "now");
        final UpdateManifest manifest = authenticate(manifestBytes, signatureEnvelopeBytes);
        validateContextPolicy(manifest, context);
        return new TrustedUpdateManifest(manifest, manifest.keyId(), now, context.installedFrameworkHashes());
    }

    /**
     * Authenticates the exact published bytes and schema, without new-release admission.
     * This can inspect an old signed installation manifest but does not establish installation
     * ownership or bind it to a local build/root. It does not return a planner-authorizing result.
     */
    public UpdateManifest authenticate(final byte[] manifestBytes, final byte[] signatureEnvelopeBytes)
            throws UpdateManifestVerificationException {
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        Objects.requireNonNull(signatureEnvelopeBytes, "signatureEnvelopeBytes");

        // Step 0: Check manifest bytes length before hash and signature calculation
        if (manifestBytes.length == 0 || manifestBytes.length > UpdateManifest.MAX_MANIFEST_BYTES) {
            throw new UpdateManifestVerificationException(
                    "MANIFEST_SIZE_INVALID", "Manifest bytes size outside bounds: " + manifestBytes.length);
        }

        // Step 1: Parse and validate detached signature envelope
        final SignatureEnvelope envelope = parseEnvelope(signatureEnvelopeBytes);

        // Step 2: Check algorithm and keyId
        if (!"Ed25519".equals(envelope.algorithm())) {
            throw new UpdateManifestVerificationException(
                    "UNSUPPORTED_ALGORITHM", "Unsupported signature algorithm: " + envelope.algorithm());
        }
        if (revokedKeys.contains(envelope.keyId())) {
            throw new UpdateManifestVerificationException("REVOKED_KEY", "Signing key is revoked");
        }
        final PublicKey publicKey = trustedKeys.get(envelope.keyId());
        if (publicKey == null) {
            throw new UpdateManifestVerificationException(
                    "UNKNOWN_KEY", "Signing keyId is not recognized or trusted: " + envelope.keyId());
        }

        // Step 3: Constant-time hash check over exact manifest bytes
        final byte[] computedHash = sha256Bytes(manifestBytes);
        final byte[] expectedHash = HexFormat.of().parseHex(envelope.manifestSha256());
        if (!MessageDigest.isEqual(computedHash, expectedHash)) {
            throw new UpdateManifestVerificationException(
                    "MANIFEST_HASH_MISMATCH", "Manifest SHA-256 digest does not match signature envelope");
        }

        // Step 4: Verify Ed25519 signature over exact manifest bytes
        if (!verifyEd25519(manifestBytes, envelope.signatureBytes(), publicKey)) {
            throw new UpdateManifestVerificationException(
                    "SIGNATURE_INVALID", "Ed25519 signature verification failed over manifest bytes");
        }

        // Step 5: Strict manifest JSON parse
        final UpdateManifest manifest;
        try {
            manifest = UpdateManifest.parse(manifestBytes);
        } catch (IOException | IllegalArgumentException parseError) {
            throw new UpdateManifestVerificationException(
                    "MALFORMED_MANIFEST", "Failed to strictly parse manifest: " + parseError.getMessage(), parseError);
        }

        // Step 6: Verify keyId consistency between envelope and manifest body
        if (!manifest.keyId().equals(envelope.keyId())) {
            throw new UpdateManifestVerificationException(
                    "KEY_ID_MISMATCH",
                    "Key ID in manifest (" + manifest.keyId() + ") does not match envelope (" + envelope.keyId() + ")");
        }

        return manifest;
    }

    private static SignatureEnvelope parseEnvelope(final byte[] envelopeBytes)
            throws UpdateManifestVerificationException {
        if (envelopeBytes.length == 0 || envelopeBytes.length > MAX_ENVELOPE_BYTES) {
            throw new UpdateManifestVerificationException(
                    "ENVELOPE_SIZE_INVALID", "Signature envelope size outside bounds: " + envelopeBytes.length);
        }
        final JsonNode tree;
        try {
            tree = STRICT_MAPPER.readTree(UpdateManifest.strictUtf8(envelopeBytes));
        } catch (IOException e) {
            throw new UpdateManifestVerificationException(
                    "MALFORMED_ENVELOPE", "Signature envelope is not valid JSON: " + e.getMessage(), e);
        }
        if (!(tree instanceof ObjectNode root)) {
            throw new UpdateManifestVerificationException(
                    "MALFORMED_ENVELOPE", "Signature envelope must be a JSON object");
        }

        for (final String key : REQUIRED_ENVELOPE_KEYS) {
            if (!root.has(key)) {
                throw new UpdateManifestVerificationException(
                        "MISSING_ENVELOPE_FIELD", "Missing required field: " + key);
            }
        }

        if (root.size() != REQUIRED_ENVELOPE_KEYS.size()) {
            throw new UpdateManifestVerificationException("MALFORMED_ENVELOPE", "Unknown envelope field");
        }
        final JsonNode formatNode = root.get("format");
        if (formatNode == null || !formatNode.isTextual() || !SIGNATURE_FORMAT.equals(formatNode.textValue())) {
            throw new UpdateManifestVerificationException(
                    "UNSUPPORTED_FORMAT", "Unsupported signature format: " + formatNode);
        }

        final JsonNode schemaNode = root.get("schemaVersion");
        if (schemaNode == null
                || !schemaNode.isIntegralNumber()
                || !schemaNode.canConvertToInt()
                || schemaNode.intValue() != SIGNATURE_SCHEMA_VERSION) {
            throw new UpdateManifestVerificationException(
                    "UNSUPPORTED_SCHEMA_VERSION", "Unsupported schema version: " + schemaNode);
        }

        final JsonNode algoNode = root.get("algorithm");
        if (algoNode == null || !algoNode.isTextual()) {
            throw new UpdateManifestVerificationException("MALFORMED_ENVELOPE", "algorithm must be string");
        }
        final String algorithm = algoNode.textValue();

        final JsonNode keyIdNode = root.get("keyId");
        if (keyIdNode == null
                || !keyIdNode.isTextual()
                || keyIdNode.textValue().length() > 64
                || !KEY_ID_PATTERN.matcher(keyIdNode.textValue()).matches()) {
            throw new UpdateManifestVerificationException("INVALID_KEY_ID", "Invalid keyId format: " + keyIdNode);
        }
        final String keyId = keyIdNode.textValue();

        final JsonNode hashNode = root.get("manifestSha256");
        if (hashNode == null || !hashNode.isTextual()) {
            throw new UpdateManifestVerificationException("INVALID_HASH", "manifestSha256 must be string");
        }
        final String manifestSha256 = hashNode.textValue().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(manifestSha256).matches()) {
            throw new UpdateManifestVerificationException("INVALID_HASH", "Invalid manifestSha256 hex string");
        }

        final JsonNode sigNode = root.get("signature");
        if (sigNode == null || !sigNode.isTextual()) {
            throw new UpdateManifestVerificationException("INVALID_SIGNATURE", "signature must be string");
        }
        final byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(sigNode.textValue());
        } catch (IllegalArgumentException e) {
            throw new UpdateManifestVerificationException(
                    "INVALID_SIGNATURE_BASE64", "Signature is not valid Base64", e);
        }
        if (signatureBytes.length != ED25519_SIGNATURE_BYTES) {
            throw new UpdateManifestVerificationException(
                    "INVALID_SIGNATURE_LENGTH",
                    "Ed25519 signature must be exactly 64 bytes, got: " + signatureBytes.length);
        }

        return new SignatureEnvelope(
                formatNode.textValue(), schemaNode.intValue(), algorithm, keyId, manifestSha256, signatureBytes);
    }

    private static void validateContextPolicy(final UpdateManifest manifest, final UpdateVerificationContext context)
            throws UpdateManifestVerificationException {

        // Exact expected release identity matching
        if (!manifest.version().equals(context.expectedVersion())) {
            throw new UpdateManifestVerificationException(
                    "VERSION_MISMATCH",
                    "Manifest version (" + manifest.version() + ") does not match expected ("
                            + context.expectedVersion() + ")");
        }
        if (!manifest.channel().equals(context.expectedChannel())) {
            throw new UpdateManifestVerificationException(
                    "CHANNEL_MISMATCH",
                    "Manifest channel (" + manifest.channel() + ") does not match expected ("
                            + context.expectedChannel() + ")");
        }
        if (manifest.buildNumber() != context.expectedBuildNumber()) {
            throw new UpdateManifestVerificationException(
                    "BUILD_NUMBER_MISMATCH",
                    "Manifest buildNumber (" + manifest.buildNumber() + ") does not match expected ("
                            + context.expectedBuildNumber() + ")");
        }
        if (!manifest.sourceRevision().equals(context.expectedSourceRevision())) {
            throw new UpdateManifestVerificationException(
                    "SOURCE_REVISION_MISMATCH",
                    "Manifest sourceRevision (" + manifest.sourceRevision() + ") does not match expected ("
                            + context.expectedSourceRevision() + ")");
        }

        // Reject replay/downgrade independently of the expected identity (which can itself be old).
        final InstalledBuild installed = context.installedBuild();
        if (installed.version().isEmpty()) {
            throw new UpdateManifestVerificationException("INSTALLED_IDENTITY_UNKNOWN", "Installed version is unknown");
        }
        if (!installed.channel().equals(manifest.channel())) {
            throw new UpdateManifestVerificationException(
                    "INSTALLED_CHANNEL_MISMATCH", "Cross-channel update not admitted");
        }
        final int versionOrder = UpdateVersion.parseInstalled(manifest.version())
                .compareTo(installed.version().orElseThrow());
        if (versionOrder < 0) {
            throw new UpdateManifestVerificationException("VERSION_DOWNGRADE", "Target numeric version is older");
        }
        if (installed.buildNumber().isPresent()) {
            if (manifest.buildNumber() <= installed.buildNumber().getAsLong()) {
                // Equal builds are rejected even if version or source differs: never reinterpret an identity.
                throw new UpdateManifestVerificationException("BUILD_NOT_NEWER", "Target build must be strictly newer");
            }
        } else if (versionOrder <= 0) {
            throw new UpdateManifestVerificationException(
                    "VERSION_NOT_NEWER", "Historical install requires newer numeric version");
        }

        // Semantic check: minimumUpdaterVersion <= currentUpdaterVersion
        final UpdateVersion minUpdater;
        final UpdateVersion currentUpdater;
        try {
            minUpdater = UpdateVersion.parseInstalled(manifest.minimumUpdaterVersion());
            currentUpdater = UpdateVersion.parseInstalled(context.currentUpdaterVersion());
        } catch (IllegalArgumentException e) {
            throw new UpdateManifestVerificationException(
                    "MALFORMED_VERSION", "Failed to semantically parse updater version: " + e.getMessage(), e);
        }
        if (currentUpdater.compareTo(minUpdater) < 0) {
            throw new UpdateManifestVerificationException(
                    "UPDATER_VERSION_TOO_OLD",
                    "Current updater version (" + context.currentUpdaterVersion()
                            + ") is older than manifest requirement (" + manifest.minimumUpdaterVersion() + ")");
        }

        // This slice admits only the concrete Windows x64 contract. 'all' is not a bypass.
        if (!"windows-x64".equals(manifest.targetPlatform())
                || !manifest.targetPlatform().equals(context.supportedPlatform())) {
            throw new UpdateManifestVerificationException(
                    "PLATFORM_UNSUPPORTED", "Target and supported platform must be windows-x64");
        }
    }

    private static boolean verifyEd25519(final byte[] data, final byte[] signature, final PublicKey key) {
        try {
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            return false;
        }
    }

    private static byte[] sha256Bytes(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing from JVM", e);
        }
    }

    private record SignatureEnvelope(
            String format,
            int schemaVersion,
            String algorithm,
            String keyId,
            String manifestSha256,
            byte[] signatureBytes) {}

    public static final class UpdateManifestVerificationException extends Exception {
        private final String errorCode;

        public UpdateManifestVerificationException(final String errorCode, final String message) {
            super(errorCode + ": " + message);
            this.errorCode = errorCode;
        }

        public UpdateManifestVerificationException(
                final String errorCode, final String message, final Throwable cause) {
            super(errorCode + ": " + message, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
