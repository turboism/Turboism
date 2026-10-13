package dev.turboism.pluginmanagement;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.plugin.PluginJarContract;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogDependency;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogLocalization;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cryptographic and schema-v2 verifier for the Plugin Directory catalog.
 *
 * <p>Implements the normative verification order from {@code plugin-directory-api-v2.md}:
 * <ol>
 *   <li>reject an unknown format, schema version, algorithm, or key ID in the detached signature;</li>
 *   <li>hash the exact identity-encoded catalog bytes;</li>
 *   <li>compare {@code catalogSha256} in constant time;</li>
 *   <li>verify Ed25519 over the exact catalog bytes;</li>
 *   <li>parse catalog JSON only after steps 1–4 pass;</li>
 *   <li>validate strict schema v2 and all semantic rules;</li>
 *   <li>reject replay or downgrade against the persisted {@code catalogVersion}.</li>
 * </ol>
 */
public final class PluginCatalogVerifier {

    /** Maximum accepted catalog JSON size (5 MiB). */
    public static final int MAX_CATALOG_BYTES = 5 * 1024 * 1024;

    /** Maximum accepted detached signature envelope size (16 KiB). */
    public static final int MAX_SIGNATURE_BYTES = 16 * 1024;

    /** Maximum accepted trusted-keys manifest size (64 KiB). */
    public static final int MAX_TRUSTED_KEYS_BYTES = 64 * 1024;

    /** Maximum number of trusted keys in an allowlist. */
    public static final int MAX_TRUSTED_KEYS = 16;

    /** Maximum number of plugins in a catalog. */
    public static final int MAX_PLUGINS = 10_000;

    /** Maximum number of releases per plugin. */
    public static final int MAX_RELEASES = 100;

    /** Maximum number of tags per release. */
    public static final int MAX_TAGS = 12;

    /** Maximum number of dependencies per release. */
    public static final int MAX_DEPENDENCIES = 100;

    /** Maximum number of permissions per release. */
    public static final int MAX_PERMISSIONS = 100;

    /** Maximum number of Cubism versions per release. */
    public static final int MAX_CUBISM_VERSIONS = 100;

    /** Maximum accepted JAR artifact size (16 MiB). */
    public static final long MAX_ARTIFACT_BYTES = 16L * 1024L * 1024L;

    /** Bundled resource path for the production trusted Ed25519 public key allowlist. */
    public static final String TRUSTED_KEYS_RESOURCE = "/META-INF/turboism/plugin-catalog-trusted-keys.json";

    /** Trusted production key ID for the official Plugin Directory v2 catalog. */
    public static final String PRODUCTION_KEY_ID = "turboism-official-v1";

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+");
    private static final Pattern KEBAB_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SOURCE_REVISION_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern HOST_VERSION_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar");
    private static final Pattern ARTIFACT_URL_PATTERN = Pattern.compile(
            "https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/?#]+/([A-Za-z0-9][A-Za-z0-9._-]*\\.jar)");
    private static final Pattern UTC_DATETIME_PATTERN =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z");

    private static final Set<String> ENVELOPE_KEYS =
            Set.of("format", "schemaVersion", "algorithm", "keyId", "catalogSha256", "signature");
    private static final Set<String> CATALOG_KEYS =
            Set.of("format", "schemaVersion", "catalogVersion", "publishedAt", "plugins");
    private static final Set<String> PLUGIN_KEYS = Set.of(
            "id",
            "slug",
            "name",
            "summary",
            "trust",
            "author",
            "license",
            "repository",
            "support",
            "localizations",
            "releases");
    private static final Set<String> LOCALIZATION_LOCALES = Set.of("en", "zh-Hans", "zh-Hant", "ja", "ko");
    private static final Set<String> LOCALIZATION_KEYS = Set.of("name", "summary");
    private static final Set<String> RELEASE_REQUIRED_KEYS = Set.of(
            "version",
            "channel",
            "status",
            "publishedAt",
            "category",
            "tags",
            "turboismApi",
            "requiresCubism",
            "cubismVersions",
            "platforms",
            "dependencies",
            "permissions",
            "releaseUrl",
            "artifact");
    private static final Set<String> RELEASE_ALLOWED_KEYS = Set.of(
            "version",
            "channel",
            "status",
            "publishedAt",
            "category",
            "tags",
            "turboismApi",
            "requiresCubism",
            "cubismVersions",
            "platforms",
            "dependencies",
            "permissions",
            "releaseUrl",
            "sourceRevision",
            "publishedEvents",
            "subscribedEvents",
            "embeddedContracts",
            "artifact",
            "hotReload");
    private static final Set<String> ARTIFACT_REQUIRED_KEYS =
            Set.of("mediaType", "fileName", "url", "sha256", "descriptorSha256", "size");
    private static final Set<String> ARTIFACT_ALLOWED_KEYS = Set.of(
            "mediaType",
            "fileName",
            "url",
            "sha256",
            "descriptorSha256",
            "size",
            "descriptor",
            "publishedEvents",
            "subscribedEvents",
            "embeddedContracts");
    private static final Set<String> DEPENDENCY_REQUIRED_KEYS = Set.of("id", "version", "type", "ordering");
    private static final Set<String> DEPENDENCY_ALLOWED_KEYS = Set.of("id", "version", "type", "ordering", "reason");
    private static final Set<String> PERMISSION_KEYS = Set.of("id", "scope", "reason");
    private static final Set<String> TRUST_VALUES =
            Set.of(CatalogPlugin.TRUST_OFFICIAL, CatalogPlugin.TRUST_REVIEWED_THIRD_PARTY);
    private static final Set<String> CHANNEL_VALUES =
            Set.of(CatalogRelease.CHANNEL_STABLE, CatalogRelease.CHANNEL_PREVIEW);
    private static final Set<String> STATUS_VALUES = Set.of(CatalogRelease.STATUS_ACTIVE, CatalogRelease.STATUS_YANKED);
    private static final Set<String> PLATFORM_VALUES = Set.of("windows-x64");
    private static final Set<String> DEPENDENCY_TYPES =
            Set.of(CatalogDependency.TYPE_REQUIRED, CatalogDependency.TYPE_OPTIONAL);
    private static final Set<String> ORDERING_VALUES = Set.of("none", "before", "after");
    private static final Set<String> PERMISSION_SCOPES = Set.of("application", "user");

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Map<String, TrustedKey> trustedKeys;
    private final boolean requireProduction;

    /** Creates a production verifier loaded with the bundled {@code turboism-official-v1} public key. */
    public PluginCatalogVerifier() {
        this(loadBundledTrustedKeys(), true);
    }

    /** Creates a verifier with an explicit trusted-key allowlist and production-key requirement flag. */
    public PluginCatalogVerifier(final Map<String, TrustedKey> trustedKeys, final boolean requireProduction) {
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        if (trustedKeys.isEmpty() || trustedKeys.size() > MAX_TRUSTED_KEYS) {
            throw new IllegalArgumentException("trustedKeys size must be between 1 and " + MAX_TRUSTED_KEYS);
        }
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.requireProduction = requireProduction;
    }

    /** Loads and validates the bundled trusted-keys JSON resource. */
    public static Map<String, TrustedKey> loadBundledTrustedKeys() {
        try (InputStream stream = PluginCatalogVerifier.class.getResourceAsStream(TRUSTED_KEYS_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing bundled trusted keys resource: " + TRUSTED_KEYS_RESOURCE);
            }
            final byte[] bytes = stream.readNBytes(MAX_TRUSTED_KEYS_BYTES + 1);
            return parseTrustedKeys(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException("unable to read bundled trusted keys resource", failure);
        }
    }

    /** Parses and validates a trusted-keys allowlist JSON document. */
    public static Map<String, TrustedKey> parseTrustedKeys(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_TRUSTED_KEYS_BYTES) {
            throw new IllegalArgumentException("trusted key allowlist size is out of bounds");
        }
        final ObjectNode manifest = parseStrictObject(bytes, "PLUGIN_CATALOG_KEYS_INVALID", "trusted key allowlist");
        if (manifest.isEmpty() || manifest.size() > MAX_TRUSTED_KEYS) {
            throw new IllegalArgumentException("trusted key allowlist count is out of bounds");
        }
        final Map<String, TrustedKey> keys = new LinkedHashMap<>();
        final var fields = manifest.fields();
        while (fields.hasNext()) {
            final var entry = fields.next();
            final String keyId = entry.getKey();
            if (keyId == null
                    || keyId.isEmpty()
                    || keyId.length() > 100
                    || !KEY_ID_PATTERN.matcher(keyId).matches()) {
                throw new IllegalArgumentException("invalid trusted key id: " + keyId);
            }
            if (!(entry.getValue() instanceof ObjectNode node)) {
                throw new IllegalArgumentException("trusted key entry must be an object: " + keyId);
            }
            requireKeysOnly(node, Set.of("pem", "purpose"), "PLUGIN_CATALOG_KEYS_INVALID", keyId);
            final String pem = requireString(node, "pem", 1, 4096, "PLUGIN_CATALOG_KEYS_INVALID");
            final String purpose = requireString(node, "purpose", 1, 32, "PLUGIN_CATALOG_KEYS_INVALID");
            if (!"production".equals(purpose) && !"test".equals(purpose)) {
                throw new IllegalArgumentException("trusted key purpose must be production or test: " + keyId);
            }
            final PublicKey publicKey = parseEd25519PublicKeyFromPem(pem);
            keys.put(keyId, new TrustedKey(keyId, pem, purpose, publicKey));
        }
        return Map.copyOf(keys);
    }

    /** Parses an X.509 SPKI Ed25519 public key from PEM text. */
    public static PublicKey parseEd25519PublicKeyFromPem(final String pem) {
        Objects.requireNonNull(pem, "pem");
        final String stripped = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("PEM public key is empty");
        }
        try {
            final byte[] der = Base64.getDecoder().decode(stripped);
            final KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException invalid) {
            throw new IllegalArgumentException("invalid Ed25519 public key PEM", invalid);
        }
    }

    /** Returns the immutable trusted-key allowlist configured on this verifier. */
    public Map<String, TrustedKey> trustedKeys() {
        return trustedKeys;
    }

    /**
     * Verifies {@code catalogBytes} and {@code sigBytes} without consulting a persistence store.
     *
     * @param catalogBytes identity-encoded {@code catalog.json} bytes
     * @param sigBytes identity-encoded {@code catalog.json.sig} bytes
     * @return the verified, immutable {@link PluginCatalog}
     * @throws PluginCatalogVerificationException when any step fails
     */
    public PluginCatalog verify(final byte[] catalogBytes, final byte[] sigBytes) {
        Objects.requireNonNull(catalogBytes, "catalogBytes");
        Objects.requireNonNull(sigBytes, "sigBytes");

        // Step 1: Validate signature envelope format, schemaVersion, algorithm, and keyId first.
        if (sigBytes.length == 0 || sigBytes.length > MAX_SIGNATURE_BYTES) {
            throw reject("PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID", "signature envelope size is out of bounds");
        }
        final SignatureEnvelope envelope = parseAndValidateEnvelope(sigBytes);
        final TrustedKey trustedKey = trustedKeys.get(envelope.keyId());
        if (trustedKey == null) {
            throw reject("PLUGIN_CATALOG_UNKNOWN_KEY_ID", "unknown signing key id: " + envelope.keyId());
        }
        if (requireProduction && !"production".equals(trustedKey.purpose())) {
            throw reject("PLUGIN_CATALOG_NON_PRODUCTION_KEY", "non-production key id: " + envelope.keyId());
        }

        // Step 2: Check catalog size bound and hash the exact identity-encoded catalog bytes.
        if (catalogBytes.length == 0 || catalogBytes.length > MAX_CATALOG_BYTES) {
            throw reject("PLUGIN_CATALOG_SIZE_INVALID", "catalog size is out of bounds");
        }
        final byte[] actualHashBytes = sha256Bytes(catalogBytes);
        final byte[] expectedHashBytes = HexFormat.of().parseHex(envelope.catalogSha256());

        // Step 3: Constant-time comparison of catalogSha256 BEFORE Ed25519 verification or JSON parsing.
        if (!MessageDigest.isEqual(expectedHashBytes, actualHashBytes)) {
            throw reject("PLUGIN_CATALOG_HASH_MISMATCH", "catalogSha256 does not match catalog bytes");
        }

        // Step 4: Verify Ed25519 signature over the exact catalog bytes BEFORE parsing catalog JSON.
        if (!verifyEd25519(catalogBytes, envelope.signatureBytes(), trustedKey.publicKey())) {
            throw reject("PLUGIN_CATALOG_SIGNATURE_INVALID", "Ed25519 signature verification failed");
        }

        // Step 5 & 6: Parse catalog JSON only after signature verification passes, then validate schema v2.
        final String actualSha256Hex = HexFormat.of().formatHex(actualHashBytes);
        return parseAndValidateCatalog(catalogBytes, actualSha256Hex, envelope.keyId());
    }

    /**
     * Verifies {@code catalogBytes} and {@code sigBytes}, enforces replay/downgrade protection
     * against {@code stateStore}, and persists the updated watermark on success.
     *
     * @param catalogBytes identity-encoded {@code catalog.json} bytes
     * @param sigBytes identity-encoded {@code catalog.json.sig} bytes
     * @param stateStore persisted catalog watermark store
     * @param etag optional HTTP ETag from the catalog response
     * @return the verified, immutable {@link PluginCatalog}
     * @throws PluginCatalogVerificationException when verification or rollback/replay protection fails
     */
    public PluginCatalog verifyAndAdvance(
            final byte[] catalogBytes,
            final byte[] sigBytes,
            final PluginCatalogStateStore stateStore,
            final Optional<String> etag) {
        return verifyWithState(catalogBytes, sigBytes, stateStore, etag, false);
    }

    /**
     * Verifies {@code catalogBytes} and {@code sigBytes}, requiring a strictly higher
     * {@code catalogVersion} than any previously persisted watermark (rejecting both downgrades
     * and exact replays of an already-applied catalog version).
     *
     * @param catalogBytes identity-encoded {@code catalog.json} bytes
     * @param sigBytes identity-encoded {@code catalog.json.sig} bytes
     * @param stateStore persisted catalog watermark store
     * @param etag optional HTTP ETag from the catalog response
     * @return the verified, immutable {@link PluginCatalog}
     * @throws PluginCatalogVerificationException when verification, downgrade, or replay fails
     */
    public PluginCatalog verifyStrictlyNewer(
            final byte[] catalogBytes,
            final byte[] sigBytes,
            final PluginCatalogStateStore stateStore,
            final Optional<String> etag) {
        return verifyWithState(catalogBytes, sigBytes, stateStore, etag, true);
    }

    private PluginCatalog verifyWithState(
            final byte[] catalogBytes,
            final byte[] sigBytes,
            final PluginCatalogStateStore stateStore,
            final Optional<String> etag,
            final boolean requireStrictlyHigherVersion) {
        Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(etag, "etag");
        final PluginCatalog verified = verify(catalogBytes, sigBytes);
        final PluginCatalogStateStore.State current = stateStore.read();
        if (current.catalogVersion().isPresent()) {
            final long previousVersion = current.catalogVersion().getAsLong();
            if (verified.catalogVersion() < previousVersion) {
                throw reject(
                        "PLUGIN_CATALOG_ROLLBACK_REJECTED",
                        "catalogVersion " + verified.catalogVersion() + " is older than persisted " + previousVersion);
            }
            if (verified.catalogVersion() == previousVersion) {
                if (requireStrictlyHigherVersion) {
                    throw reject(
                            "PLUGIN_CATALOG_REPLAY_REJECTED",
                            "catalogVersion " + verified.catalogVersion() + " was replayed without increment");
                }
                final String previousSha = current.catalogSha256().orElse("");
                final Instant previousPublishedAt = current.publishedAt().orElse(Instant.EPOCH);
                if (!verified.catalogSha256().equals(previousSha)
                        || !verified.publishedAt().equals(previousPublishedAt)) {
                    throw reject(
                            "PLUGIN_CATALOG_REPLAY_REJECTED",
                            "catalogVersion " + verified.catalogVersion() + " was replayed with mutated content");
                }
            } else if (current.publishedAt().isPresent()
                    && verified.publishedAt().isBefore(current.publishedAt().orElseThrow())) {
                throw reject(
                        "PLUGIN_CATALOG_ROLLBACK_REJECTED", "catalog publishedAt regressed below persisted timestamp");
            }
        }
        stateStore.save(current.withVerifiedCatalog(verified, catalogBytes, sigBytes, etag));
        return verified;
    }

    private static SignatureEnvelope parseAndValidateEnvelope(final byte[] sigBytes) {
        final ObjectNode root =
                parseStrictObject(sigBytes, "PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID", "signature envelope");
        requireKeysOnly(root, ENVELOPE_KEYS, "PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID", "signature");
        requireKeysPresent(root, ENVELOPE_KEYS, "PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID", "signature");

        final String format = requireString(root, "format", 1, 100, "PLUGIN_CATALOG_SIGNATURE_FORMAT_INVALID");
        if (!PluginCatalog.SIGNATURE_FORMAT.equals(format)) {
            throw reject("PLUGIN_CATALOG_SIGNATURE_FORMAT_INVALID", "unsupported signature format: " + format);
        }
        final long schemaVersion = requireInt(root, "schemaVersion", 1, 100, "PLUGIN_CATALOG_SIGNATURE_SCHEMA_INVALID");
        if (schemaVersion != PluginCatalog.SCHEMA_VERSION) {
            throw reject(
                    "PLUGIN_CATALOG_SIGNATURE_SCHEMA_INVALID", "unsupported signature schemaVersion: " + schemaVersion);
        }
        final String algorithm = requireString(root, "algorithm", 1, 32, "PLUGIN_CATALOG_SIGNATURE_ALGORITHM_INVALID");
        if (!PluginCatalog.ALGORITHM_ED25519.equals(algorithm)) {
            throw reject("PLUGIN_CATALOG_SIGNATURE_ALGORITHM_INVALID", "unsupported algorithm: " + algorithm);
        }
        final String keyId = requireString(root, "keyId", 1, 100, "PLUGIN_CATALOG_UNKNOWN_KEY_ID");
        if (!KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw reject("PLUGIN_CATALOG_UNKNOWN_KEY_ID", "malformed keyId: " + keyId);
        }
        final String catalogSha256 =
                requireString(root, "catalogSha256", 64, 64, "PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID");
        if (!SHA256_PATTERN.matcher(catalogSha256).matches()) {
            throw reject("PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID", "catalogSha256 is not lowercase 64-hex");
        }
        final String signatureBase64 =
                requireString(root, "signature", 88, 88, "PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID");
        final byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException badBase64) {
            throw reject("PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID", "signature is not valid base64");
        }
        if (signatureBytes.length != 64) {
            throw reject("PLUGIN_CATALOG_SIGNATURE_ENVELOPE_INVALID", "Ed25519 signature must be 64 bytes");
        }
        return new SignatureEnvelope(keyId, catalogSha256, signatureBytes);
    }

    private static boolean verifyEd25519(
            final byte[] catalogBytes, final byte[] signatureBytes, final PublicKey publicKey) {
        try {
            final Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(catalogBytes);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException failure) {
            return false;
        }
    }

    private static PluginCatalog parseAndValidateCatalog(
            final byte[] catalogBytes, final String catalogSha256, final String keyId) {
        final ObjectNode root = parseStrictObject(catalogBytes, "PLUGIN_CATALOG_SCHEMA_INVALID", "catalog");
        requireKeysOnly(root, CATALOG_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", "catalog");
        requireKeysPresent(root, CATALOG_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", "catalog");

        final String format = requireString(root, "format", 1, 100, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!PluginCatalog.FORMAT.equals(format)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", "unexpected catalog format: " + format);
        }
        final long schemaVersion = requireInt(root, "schemaVersion", 1, 100, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (schemaVersion != PluginCatalog.SCHEMA_VERSION) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", "unexpected catalog schemaVersion: " + schemaVersion);
        }
        final long catalogVersion =
                requireInt(root, "catalogVersion", 1, Long.MAX_VALUE, "PLUGIN_CATALOG_SCHEMA_INVALID");
        final Instant publishedAt = parseUtcInstant(
                requireString(root, "publishedAt", 20, 20, "PLUGIN_CATALOG_SCHEMA_INVALID"), "catalog.publishedAt");
        final ArrayNode pluginsArray =
                requireArray(root, "plugins", 0, MAX_PLUGINS, "PLUGIN_CATALOG_SCHEMA_INVALID", "catalog.plugins");

        final List<CatalogPlugin> plugins = new ArrayList<>(pluginsArray.size());
        final Set<String> seenIds = new HashSet<>();
        final Set<String> seenSlugs = new HashSet<>();
        for (int index = 0; index < pluginsArray.size(); index++) {
            final JsonNode item = pluginsArray.get(index);
            final String path = "plugins[" + index + "]";
            if (!(item instanceof ObjectNode pluginNode)) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be an object");
            }
            final CatalogPlugin plugin = parsePlugin(pluginNode, path);
            if (!seenIds.add(plugin.id())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", "duplicate plugin id: " + plugin.id());
            }
            if (!seenSlugs.add(plugin.slug())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", "duplicate plugin slug: " + plugin.slug());
            }
            plugins.add(plugin);
        }

        return new PluginCatalog(
                format, (int) schemaVersion, catalogVersion, publishedAt, catalogSha256, keyId, plugins);
    }

    private static CatalogPlugin parsePlugin(final ObjectNode node, final String path) {
        requireKeysOnly(node, PLUGIN_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        requireKeysPresent(node, PLUGIN_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);

        final String id = requireString(node, "id", 1, 128, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!PLUGIN_ID_PATTERN.matcher(id).matches()
                || dev.turboism.internal.core.CorePluginManagement.CORE_PLUGIN_ID.equals(id)
                || PluginJarContract.RETIRED_PLUGIN_IDS.contains(id)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".id is invalid or reserved: " + id);
        }
        final String slug = requireString(node, "slug", 1, 100, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!KEBAB_PATTERN.matcher(slug).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".slug must be lowercase kebab-case");
        }
        final String name = requireString(node, "name", 1, 120, "PLUGIN_CATALOG_SCHEMA_INVALID");
        final String summary = requireString(node, "summary", 1, 500, "PLUGIN_CATALOG_SCHEMA_INVALID");
        final String trust = requireString(node, "trust", 1, 64, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!TRUST_VALUES.contains(trust)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".trust is invalid: " + trust);
        }
        final String author = requireString(node, "author", 1, 120, "PLUGIN_CATALOG_SCHEMA_INVALID");
        final String license = requireString(node, "license", 1, 100, "PLUGIN_CATALOG_SCHEMA_INVALID");
        final String repository = requireHttpsUrl(node, "repository", path + ".repository");
        final String support = requireHttpsUrl(node, "support", path + ".support");

        final JsonNode localizationsNode = node.get("localizations");
        if (!(localizationsNode instanceof ObjectNode locObject)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".localizations must be an object");
        }
        requireKeysOnly(locObject, LOCALIZATION_LOCALES, "PLUGIN_CATALOG_SCHEMA_INVALID", path + ".localizations");
        final Map<String, CatalogLocalization> localizations = new LinkedHashMap<>();
        final var locFields = locObject.fields();
        while (locFields.hasNext()) {
            final var entry = locFields.next();
            if (!(entry.getValue() instanceof ObjectNode lNode)) {
                throw reject(
                        "PLUGIN_CATALOG_SCHEMA_INVALID",
                        path + ".localizations." + entry.getKey() + " must be an object");
            }
            requireKeysOnly(
                    lNode,
                    LOCALIZATION_KEYS,
                    "PLUGIN_CATALOG_SCHEMA_INVALID",
                    path + ".localizations." + entry.getKey());
            requireKeysPresent(
                    lNode,
                    LOCALIZATION_KEYS,
                    "PLUGIN_CATALOG_SCHEMA_INVALID",
                    path + ".localizations." + entry.getKey());
            localizations.put(
                    entry.getKey(),
                    new CatalogLocalization(
                            requireString(lNode, "name", 1, 120, "PLUGIN_CATALOG_SCHEMA_INVALID"),
                            requireString(lNode, "summary", 1, 500, "PLUGIN_CATALOG_SCHEMA_INVALID")));
        }

        final ArrayNode releasesArray =
                requireArray(node, "releases", 1, MAX_RELEASES, "PLUGIN_CATALOG_SCHEMA_INVALID", path + ".releases");
        final List<CatalogRelease> releases = new ArrayList<>(releasesArray.size());
        final Set<String> seenVersions = new HashSet<>();
        PluginVersion previousVersion = null;
        for (int r = 0; r < releasesArray.size(); r++) {
            final JsonNode releaseItem = releasesArray.get(r);
            final String releasePath = path + ".releases[" + r + "]";
            if (!(releaseItem instanceof ObjectNode releaseNode)) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", releasePath + " must be an object");
            }
            final CatalogRelease release =
                    parseRelease(releaseNode, releasePath, CatalogPlugin.TRUST_OFFICIAL.equals(trust));
            if (!seenVersions.add(release.version())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", releasePath + " duplicate version: " + release.version());
            }
            final PluginVersion currentVersion = PluginVersion.parse(release.version());
            if (previousVersion != null && previousVersion.compareTo(currentVersion) >= 0) {
                throw reject(
                        "PLUGIN_CATALOG_SCHEMA_INVALID",
                        releasePath + " versions must be strictly ascending: " + release.version());
            }
            previousVersion = currentVersion;
            releases.add(release);
        }

        return new CatalogPlugin(
                id, slug, name, summary, trust, author, license, repository, support, localizations, releases);
    }

    private static CatalogRelease parseRelease(final ObjectNode node, final String path, final boolean officialPlugin) {
        requireKeysOnly(node, RELEASE_ALLOWED_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        requireKeysPresent(node, RELEASE_REQUIRED_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);

        final String version = requireString(node, "version", 1, 64, "PLUGIN_CATALOG_SCHEMA_INVALID");
        try {
            PluginVersion.parse(version);
        } catch (IllegalArgumentException badVersion) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".version is not strict MAJOR.MINOR.PATCH");
        }
        final String channel = requireString(node, "channel", 1, 32, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!CHANNEL_VALUES.contains(channel)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".channel is invalid: " + channel);
        }
        final String status = requireString(node, "status", 1, 32, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!STATUS_VALUES.contains(status)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".status is invalid: " + status);
        }
        final Instant publishedAt = parseUtcInstant(
                requireString(node, "publishedAt", 20, 20, "PLUGIN_CATALOG_SCHEMA_INVALID"), path + ".publishedAt");
        final String category = requireToken(node, "category", path + ".category");
        if (officialPlugin && !PluginCategoryRegistry.isRegistered(category)) {
            throw reject(
                    "PLUGIN_CATALOG_SCHEMA_INVALID", path + ".category is not in the official registry: " + category);
        }

        final ArrayNode tagsArray =
                requireArray(node, "tags", 0, MAX_TAGS, "PLUGIN_CATALOG_SCHEMA_INVALID", path + ".tags");
        final List<String> tags = new ArrayList<>(tagsArray.size());
        final Set<String> seenTags = new HashSet<>();
        for (int i = 0; i < tagsArray.size(); i++) {
            final String tag = requireTokenNode(tagsArray.get(i), path + ".tags[" + i + "]");
            if (!seenTags.add(tag)) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".tags contains duplicate tag: " + tag);
            }
            tags.add(tag);
        }

        final String turboismApi = requireString(node, "turboismApi", 1, 128, "PLUGIN_CATALOG_SCHEMA_INVALID");
        try {
            VersionRange.parse(turboismApi);
        } catch (IllegalArgumentException badRange) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".turboismApi is invalid: " + turboismApi);
        }

        final boolean requiresCubism = requireBoolean(node, "requiresCubism", path + ".requiresCubism");
        final ArrayNode cubismArray = requireArray(
                node,
                "cubismVersions",
                0,
                MAX_CUBISM_VERSIONS,
                "PLUGIN_CATALOG_SCHEMA_INVALID",
                path + ".cubismVersions");
        final List<String> cubismVersions = new ArrayList<>(cubismArray.size());
        final Set<String> seenCubism = new HashSet<>();
        for (int i = 0; i < cubismArray.size(); i++) {
            final JsonNode item = cubismArray.get(i);
            if (item == null
                    || !item.isTextual()
                    || !HOST_VERSION_PATTERN.matcher(item.textValue()).matches()) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".cubismVersions[" + i + "] is invalid");
            }
            if (!seenCubism.add(item.textValue())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".cubismVersions contains duplicate version");
            }
            cubismVersions.add(item.textValue());
        }
        if (requiresCubism && cubismVersions.isEmpty()) {
            throw reject(
                    "PLUGIN_CATALOG_SCHEMA_INVALID",
                    path + ".cubismVersions must not be empty when requiresCubism is true");
        }
        if (!requiresCubism && !cubismVersions.isEmpty()) {
            throw reject(
                    "PLUGIN_CATALOG_SCHEMA_INVALID",
                    path + ".cubismVersions must be empty when requiresCubism is false");
        }

        final ArrayNode platformsArray =
                requireArray(node, "platforms", 1, 16, "PLUGIN_CATALOG_SCHEMA_INVALID", path + ".platforms");
        final List<String> platforms = new ArrayList<>(platformsArray.size());
        final Set<String> seenPlatforms = new HashSet<>();
        for (int i = 0; i < platformsArray.size(); i++) {
            final JsonNode item = platformsArray.get(i);
            if (item == null || !item.isTextual() || !PLATFORM_VALUES.contains(item.textValue())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".platforms[" + i + "] is invalid");
            }
            if (!seenPlatforms.add(item.textValue())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".platforms contains duplicate platform");
            }
            platforms.add(item.textValue());
        }

        final ArrayNode depArray = requireArray(
                node, "dependencies", 0, MAX_DEPENDENCIES, "PLUGIN_CATALOG_SCHEMA_INVALID", path + ".dependencies");
        final List<CatalogDependency> dependencies = new ArrayList<>(depArray.size());
        final Set<String> seenDeps = new HashSet<>();
        for (int i = 0; i < depArray.size(); i++) {
            final CatalogDependency dep = parseDependency(depArray.get(i), path + ".dependencies[" + i + "]");
            if (!seenDeps.add(dep.id())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".dependencies duplicate id: " + dep.id());
            }
            dependencies.add(dep);
        }

        final ArrayNode permArray = requireArray(
                node, "permissions", 0, MAX_PERMISSIONS, "PLUGIN_CATALOG_SCHEMA_INVALID", path + ".permissions");
        final List<CatalogPermission> permissions = new ArrayList<>(permArray.size());
        final Set<String> seenPerms = new HashSet<>();
        for (int i = 0; i < permArray.size(); i++) {
            final CatalogPermission perm = parsePermission(permArray.get(i), path + ".permissions[" + i + "]");
            if (!seenPerms.add(perm.id())) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".permissions duplicate id: " + perm.id());
            }
            permissions.add(perm);
        }

        final String releaseUrl = requireHttpsUrl(node, "releaseUrl", path + ".releaseUrl");
        final Optional<String> sourceRevision;
        if (node.has("sourceRevision")) {
            final String rev = requireString(node, "sourceRevision", 40, 40, "PLUGIN_CATALOG_SCHEMA_INVALID");
            if (!SOURCE_REVISION_PATTERN.matcher(rev).matches()) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".sourceRevision must be 40-char lowercase hex");
            }
            sourceRevision = Optional.of(rev);
        } else {
            sourceRevision = Optional.empty();
        }

        final CatalogArtifact artifact = parseArtifact(node.get("artifact"), path + ".artifact");
        final boolean hotReload =
                node.has("hotReload") ? requireBoolean(node, "hotReload", path + ".hotReload") : false;
        return new CatalogRelease(
                version,
                channel,
                status,
                publishedAt,
                category,
                tags,
                turboismApi,
                requiresCubism,
                cubismVersions,
                platforms,
                dependencies,
                permissions,
                releaseUrl,
                sourceRevision,
                artifact,
                hotReload);
    }

    private static CatalogDependency parseDependency(final JsonNode item, final String path) {
        if (!(item instanceof ObjectNode node)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be an object");
        }
        requireKeysOnly(node, DEPENDENCY_ALLOWED_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        requireKeysPresent(node, DEPENDENCY_REQUIRED_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        final String id = requireString(node, "id", 1, 128, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!PLUGIN_ID_PATTERN.matcher(id).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".id is invalid: " + id);
        }
        final String version = requireString(node, "version", 1, 128, "PLUGIN_CATALOG_SCHEMA_INVALID");
        try {
            VersionRange.parse(version);
        } catch (IllegalArgumentException badRange) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".version is invalid: " + version);
        }
        final String type = requireString(node, "type", 1, 32, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!DEPENDENCY_TYPES.contains(type)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".type is invalid: " + type);
        }
        final String ordering = requireString(node, "ordering", 1, 32, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!ORDERING_VALUES.contains(ordering)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".ordering is invalid: " + ordering);
        }
        final Optional<String> reason = node.has("reason")
                ? Optional.of(requireString(node, "reason", 1, 500, "PLUGIN_CATALOG_SCHEMA_INVALID"))
                : Optional.empty();
        return new CatalogDependency(id, version, type, ordering, reason);
    }

    private static CatalogPermission parsePermission(final JsonNode item, final String path) {
        if (!(item instanceof ObjectNode node)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be an object");
        }
        requireKeysOnly(node, PERMISSION_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        requireKeysPresent(node, PERMISSION_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        final String id = requireString(node, "id", 1, 128, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!PLUGIN_ID_PATTERN.matcher(id).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".id is invalid: " + id);
        }
        final String scope = requireString(node, "scope", 1, 32, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!PERMISSION_SCOPES.contains(scope)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".scope is invalid: " + scope);
        }
        final String reason = requireString(node, "reason", 1, 500, "PLUGIN_CATALOG_SCHEMA_INVALID");
        return new CatalogPermission(id, scope, reason);
    }

    private static CatalogArtifact parseArtifact(final JsonNode item, final String path) {
        if (!(item instanceof ObjectNode node)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be an object");
        }
        requireKeysOnly(node, ARTIFACT_ALLOWED_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        requireKeysPresent(node, ARTIFACT_REQUIRED_KEYS, "PLUGIN_CATALOG_SCHEMA_INVALID", path);
        final String mediaType = requireString(node, "mediaType", 1, 100, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!CatalogArtifact.MEDIA_TYPE.equals(mediaType)) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".mediaType is invalid: " + mediaType);
        }
        final String fileName = requireString(node, "fileName", 5, 255, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".fileName is invalid: " + fileName);
        }
        final String url = requireString(node, "url", 1, 2048, "PLUGIN_CATALOG_SCHEMA_INVALID");
        final Matcher urlMatcher = ARTIFACT_URL_PATTERN.matcher(url);
        if (!urlMatcher.matches() || !fileName.equals(urlMatcher.group(1))) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".url is not an approved GitHub Release JAR URL");
        }
        final String sha256 = requireString(node, "sha256", 64, 64, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!SHA256_PATTERN.matcher(sha256).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".sha256 is invalid");
        }
        final String descriptorSha256 =
                requireString(node, "descriptorSha256", 64, 64, "PLUGIN_CATALOG_SCHEMA_INVALID");
        if (!SHA256_PATTERN.matcher(descriptorSha256).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + ".descriptorSha256 is invalid");
        }
        final long size = requireInt(node, "size", 1L, MAX_ARTIFACT_BYTES, "PLUGIN_CATALOG_SCHEMA_INVALID");
        return new CatalogArtifact(mediaType, fileName, url, sha256, descriptorSha256, size);
    }

    private static ObjectNode parseStrictObject(final byte[] bytes, final String code, final String label) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            throw reject(code, label + " must not start with a UTF-8 BOM");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw reject(code, label + " is not valid UTF-8", invalidUtf8);
        }
        try {
            final JsonNode parsed = JSON.readTree(text);
            if (!(parsed instanceof ObjectNode object)) {
                throw reject(code, label + " must be a JSON object");
            }
            return object;
        } catch (JsonProcessingException malformed) {
            throw reject(code, label + " is not valid strict JSON", malformed);
        }
    }

    private static void requireKeysOnly(
            final ObjectNode node, final Set<String> allowed, final String code, final String path) {
        final var names = node.fieldNames();
        while (names.hasNext()) {
            final String key = names.next();
            if (!allowed.contains(key)) {
                throw reject(code, path + " contains unknown field: " + key);
            }
        }
    }

    private static void requireKeysPresent(
            final ObjectNode node, final Set<String> required, final String code, final String path) {
        for (String key : required) {
            if (!node.has(key)) {
                throw reject(code, path + " is missing required field: " + key);
            }
        }
    }

    private static String requireString(
            final ObjectNode node,
            final String key,
            final int minCodePoints,
            final int maxCodePoints,
            final String code) {
        final JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) {
            throw reject(code, key + " must be a string");
        }
        final String text = value.textValue();
        final int length = text.codePointCount(0, text.length());
        if (length < minCodePoints || length > maxCodePoints) {
            throw reject(code, key + " length is out of bounds");
        }
        return text;
    }

    private static long requireInt(
            final ObjectNode node, final String key, final long min, final long max, final String code) {
        final JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw reject(code, key + " must be an integer");
        }
        final long number = value.longValue();
        if (number < min || number > max) {
            throw reject(code, key + " is out of bounds: " + number);
        }
        return number;
    }

    private static boolean requireBoolean(final ObjectNode node, final String key, final String path) {
        final JsonNode value = node.get(key);
        if (value == null || !value.isBoolean()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static ArrayNode requireArray(
            final ObjectNode node,
            final String key,
            final int minItems,
            final int maxItems,
            final String code,
            final String path) {
        final JsonNode value = node.get(key);
        if (!(value instanceof ArrayNode array)) {
            throw reject(code, path + " must be an array");
        }
        if (array.size() < minItems || array.size() > maxItems) {
            throw reject(code, path + " size is out of bounds: " + array.size());
        }
        return array;
    }

    private static String requireToken(final ObjectNode node, final String key, final String path) {
        return requireTokenNode(node.get(key), path);
    }

    private static String requireTokenNode(final JsonNode value, final String path) {
        if (value == null || !value.isTextual()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be a string token");
        }
        final String text = value.textValue();
        final int length = text.codePointCount(0, text.length());
        if (length < 2 || length > 32 || !KEBAB_PATTERN.matcher(text).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be a 2..32 char kebab-case token");
        }
        return text;
    }

    private static String requireHttpsUrl(final ObjectNode node, final String key, final String path) {
        final String raw = requireString(node, key, 1, 2048, "PLUGIN_CATALOG_SCHEMA_INVALID");
        try {
            final URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be an absolute https URL");
            }
            return raw;
        } catch (IllegalArgumentException badUri) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must be an absolute https URL", badUri);
        }
    }

    private static Instant parseUtcInstant(final String value, final String path) {
        if (!UTC_DATETIME_PATTERN.matcher(value).matches()) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " must match YYYY-MM-DDTHH:MM:SSZ");
        }
        try {
            final Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " calendar date rolled over");
            }
            return parsed;
        } catch (DateTimeParseException invalid) {
            throw reject("PLUGIN_CATALOG_SCHEMA_INVALID", path + " is not a valid UTC timestamp", invalid);
        }
    }

    static byte[] sha256Bytes(final byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static PluginCatalogVerificationException reject(final String code, final String message) {
        return new PluginCatalogVerificationException(code, message);
    }

    private static PluginCatalogVerificationException reject(
            final String code, final String message, final Throwable cause) {
        return new PluginCatalogVerificationException(code, message, cause);
    }

    private record SignatureEnvelope(String keyId, String catalogSha256, byte[] signatureBytes) {}

    /**
     * One trusted Ed25519 public key entry.
     *
     * @param keyId lowercase kebab-case key identifier
     * @param pem SPKI PEM string
     * @param purpose key purpose ({@code "production"} or {@code "test"})
     * @param publicKey parsed JDK {@link PublicKey}
     */
    public record TrustedKey(String keyId, String pem, String purpose, PublicKey publicKey) {
        public TrustedKey {
            keyId = Objects.requireNonNull(keyId, "keyId");
            pem = Objects.requireNonNull(pem, "pem");
            purpose = Objects.requireNonNull(purpose, "purpose");
            publicKey = Objects.requireNonNull(publicKey, "publicKey");
        }
    }

    /** Failure raised when catalog signature, hash, schema, or replay verification rejects input. */
    public static final class PluginCatalogVerificationException extends IllegalArgumentException {
        private final String code;

        /** Creates a verification failure with a stable diagnostic code and detail message. */
        public PluginCatalogVerificationException(final String code, final String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** Creates a verification failure with a stable diagnostic code, detail message, and cause. */
        public PluginCatalogVerificationException(final String code, final String message, final Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** Returns the stable machine-readable rejection code. */
        public String code() {
            return code;
        }
    }
}
