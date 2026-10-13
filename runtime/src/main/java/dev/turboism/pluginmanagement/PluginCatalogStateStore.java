package dev.turboism.pluginmanagement;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Atomic, symlink-rejecting persistence for the Plugin Directory v2 catalog watermark,
 * ETag revalidation state, cached signed catalog bytes, and staged update markers under
 * {@code state/runtime/plugin-management/catalog-state.json}.
 */
public final class PluginCatalogStateStore {

    /** Schema version for the v2 catalog state file; never imports or trusts v1 state. */
    public static final int SCHEMA_VERSION = 2;

    /** Relative path below Turboism home for the v2 catalog state file. */
    public static final String RELATIVE_PATH = "state/runtime/plugin-management/catalog-state.json";

    private static final long MAX_STATE_FILE_BYTES = 16L * 1024L * 1024L;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path home;
    private final Path staging;
    private final Path path;
    private final ConfinedPluginFiles files;
    private final Object lock;
    private final Consumer<String> diagnostic;

    /** Creates a catalog state store for the given Turboism home directory. */
    public PluginCatalogStateStore(final Path home) {
        this(home, ignored -> {});
    }

    /** Creates a catalog state store with a diagnostic sink for state-file failures. */
    public PluginCatalogStateStore(final Path home, final Consumer<String> diagnostic) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.staging = this.home.resolve("state/runtime/plugin-management").normalize();
        this.path = this.home.resolve(RELATIVE_PATH).normalize();
        if (!this.path.startsWith(this.home)) {
            throw new IllegalArgumentException("catalog state path escaped home");
        }
        this.files = new ConfinedPluginFiles(this.home);
        this.lock = LOCKS.computeIfAbsent(this.path, ignored -> new Object());
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Returns the normalized path to {@code catalog-state.json}. */
    public Path path() {
        return path;
    }

    /** Reads the persisted catalog state, recovering to {@link State#empty()} when absent. */
    public State read() {
        synchronized (lock) {
            try {
                files.rejectLinks(home);
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    files.rejectLinks(staging);
                }
                files.rejectLinks(path);
                final BasicFileAttributes attributes =
                        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.size() > MAX_STATE_FILE_BYTES) {
                    throw new IOException("catalog state file rejected");
                }
                final byte[] raw = Files.readAllBytes(path);
                if (raw.length > MAX_STATE_FILE_BYTES) {
                    throw new IOException("catalog state file too large");
                }
                final JsonNode parsed = JSON.readTree(raw);
                if (!(parsed instanceof ObjectNode root)) {
                    throw new IOException("catalog state must be a JSON object");
                }
                final JsonNode schema = root.get("schemaVersion");
                if (schema == null
                        || !schema.isIntegralNumber()
                        || !schema.canConvertToInt()
                        || schema.intValue() != SCHEMA_VERSION) {
                    throw new IOException("unsupported catalog state schemaVersion");
                }
                final OptionalLong catalogVersion = optionalPositiveLong(root.get("catalogVersion"));
                final Optional<Instant> publishedAt = optionalInstant(root.get("publishedAt"));
                final Optional<String> catalogSha256 = optionalSha256(root.get("catalogSha256"));
                if (catalogVersion.isPresent() != publishedAt.isPresent()
                        || catalogVersion.isPresent() != catalogSha256.isPresent()) {
                    throw new IOException("incomplete catalog watermark in state");
                }
                final Optional<String> etag = optionalEtag(root.get("etag"));
                final Optional<Instant> lastAutomaticAttempt = optionalInstant(root.get("lastAutomaticAttempt"));
                final Optional<byte[]> cachedCatalogBytes =
                        optionalBase64(root.get("cachedCatalogBase64"), PluginCatalogVerifier.MAX_CATALOG_BYTES);
                final Optional<byte[]> cachedSignatureBytes =
                        optionalBase64(root.get("cachedSignatureBase64"), PluginCatalogVerifier.MAX_SIGNATURE_BYTES);
                final Map<String, String> stagedUpdates = parseStagedUpdates(root.get("stagedUpdates"));
                return new State(
                        catalogVersion,
                        publishedAt,
                        catalogSha256,
                        etag,
                        lastAutomaticAttempt,
                        cachedCatalogBytes,
                        cachedSignatureBytes,
                        stagedUpdates);
            } catch (NoSuchFileException missing) {
                return State.empty();
            } catch (IOException | RuntimeException corrupt) {
                report("PLUGIN_CATALOG_STATE_CORRUPT");
                return State.empty();
            }
        }
    }

    /** Atomically writes the given catalog state to {@code catalog-state.json}. */
    public boolean save(final State state) {
        Objects.requireNonNull(state, "state");
        synchronized (lock) {
            Path temporary = null;
            try {
                files.rejectLinks(home);
                Files.createDirectories(staging);
                files.rejectLinks(staging);
                final ConfinedPluginFiles.ParentIdentity parent = files.parent(path);
                final ObjectNode root = JSON.createObjectNode();
                root.put("schemaVersion", SCHEMA_VERSION);
                state.catalogVersion().ifPresent(version -> root.put("catalogVersion", version));
                state.publishedAt().ifPresent(instant -> root.put("publishedAt", instant.toString()));
                state.catalogSha256().ifPresent(sha -> root.put("catalogSha256", sha));
                state.etag().ifPresent(etag -> root.put("etag", etag));
                state.lastAutomaticAttempt().ifPresent(attempt -> root.put("lastAutomaticAttempt", attempt.toString()));
                state.cachedCatalogBytes()
                        .ifPresent(bytes -> root.put(
                                "cachedCatalogBase64", Base64.getEncoder().encodeToString(bytes)));
                state.cachedSignatureBytes()
                        .ifPresent(bytes -> root.put(
                                "cachedSignatureBase64", Base64.getEncoder().encodeToString(bytes)));
                if (!state.stagedUpdates().isEmpty()) {
                    final ObjectNode updatesNode = root.putObject("stagedUpdates");
                    state.stagedUpdates().forEach(updatesNode::put);
                }
                final byte[] encoded = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
                if (encoded.length > MAX_STATE_FILE_BYTES) {
                    throw new IOException("catalog state exceeds size limit");
                }
                temporary = staging.resolve(".catalog-state-" + UUID.randomUUID() + ".tmp");
                try (var output = files.createNew(temporary, parent)) {
                    output.write(ByteBuffer.wrap(encoded));
                }
                files.move(temporary, path, parent, true);
                return true;
            } catch (IOException | RuntimeException failure) {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // Best-effort cleanup of temp state file.
                    }
                }
                report("PLUGIN_CATALOG_STATE_WRITE_FAILED");
                return false;
            }
        }
    }

    /** Records a staged auto-update version for {@code pluginId} in the persisted state. */
    public void recordStagedUpdate(final String pluginId, final String version) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        synchronized (lock) {
            final State current = read();
            final Map<String, String> updated = new LinkedHashMap<>(current.stagedUpdates());
            updated.put(pluginId, version);
            save(current.withStagedUpdates(updated));
        }
    }

    private void report(final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics are best-effort and never alter state handling.
        }
    }

    private static OptionalLong optionalPositiveLong(final JsonNode node) throws IOException {
        if (node == null || node.isNull()) return OptionalLong.empty();
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IOException("invalid integer field in catalog state");
        }
        final long value = node.longValue();
        if (value < 1L) throw new IOException("catalogVersion must be positive");
        return OptionalLong.of(value);
    }

    private static Optional<Instant> optionalInstant(final JsonNode node) throws IOException {
        if (node == null || node.isNull()) return Optional.empty();
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IOException("invalid timestamp in catalog state");
        }
        try {
            return Optional.of(Instant.parse(node.textValue()));
        } catch (DateTimeParseException invalid) {
            throw new IOException("invalid timestamp in catalog state", invalid);
        }
    }

    private static Optional<String> optionalSha256(final JsonNode node) throws IOException {
        if (node == null || node.isNull()) return Optional.empty();
        if (!node.isTextual() || !SHA256_PATTERN.matcher(node.textValue()).matches()) {
            throw new IOException("invalid sha256 in catalog state");
        }
        return Optional.of(node.textValue());
    }

    private static Optional<String> optionalEtag(final JsonNode node) throws IOException {
        if (node == null || node.isNull()) return Optional.empty();
        if (!node.isTextual()) throw new IOException("invalid etag in catalog state");
        final String text = node.textValue();
        if (text.isBlank() || text.length() > 512 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw new IOException("invalid etag in catalog state");
        }
        return Optional.of(text);
    }

    private static Optional<byte[]> optionalBase64(final JsonNode node, final int maxBytes) throws IOException {
        if (node == null || node.isNull()) return Optional.empty();
        if (!node.isTextual() || node.textValue().isEmpty()) {
            throw new IOException("invalid base64 payload in catalog state");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(node.textValue());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid base64 payload in catalog state", invalid);
        }
        if (decoded.length == 0 || decoded.length > maxBytes) {
            throw new IOException("cached payload out of bounds");
        }
        return Optional.of(decoded);
    }

    private static Map<String, String> parseStagedUpdates(final JsonNode node) throws IOException {
        if (node == null || node.isNull()) return Map.of();
        if (!(node instanceof ObjectNode object)) {
            throw new IOException("stagedUpdates must be an object");
        }
        final Map<String, String> result = new LinkedHashMap<>();
        final var fields = object.fields();
        while (fields.hasNext()) {
            final var entry = fields.next();
            final String key = entry.getKey();
            final JsonNode value = entry.getValue();
            if (key == null
                    || key.isBlank()
                    || value == null
                    || !value.isTextual()
                    || value.textValue().isBlank()) {
                throw new IOException("invalid stagedUpdates entry");
            }
            result.put(key, value.textValue());
        }
        return Map.copyOf(result);
    }

    /**
     * Immutable snapshot of the persisted v2 catalog state.
     *
     * @param catalogVersion highest verified catalogVersion
     * @param publishedAt UTC publication time of the highest verified catalog
     * @param catalogSha256 SHA-256 of the highest verified catalog bytes
     * @param etag HTTP ETag associated with the cached catalog
     * @param lastAutomaticAttempt timestamp of the last automatic check attempt
     * @param cachedCatalogBytes verified catalog bytes cached for 304 revalidation
     * @param cachedSignatureBytes verified signature bytes cached for 304 revalidation
     * @param stagedUpdates map of pluginId to staged update version
     */
    public record State(
            OptionalLong catalogVersion,
            Optional<Instant> publishedAt,
            Optional<String> catalogSha256,
            Optional<String> etag,
            Optional<Instant> lastAutomaticAttempt,
            Optional<byte[]> cachedCatalogBytes,
            Optional<byte[]> cachedSignatureBytes,
            Map<String, String> stagedUpdates) {
        public State {
            catalogVersion = Objects.requireNonNull(catalogVersion, "catalogVersion");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
            catalogSha256 = Objects.requireNonNull(catalogSha256, "catalogSha256");
            etag = Objects.requireNonNull(etag, "etag");
            lastAutomaticAttempt = Objects.requireNonNull(lastAutomaticAttempt, "lastAutomaticAttempt");
            cachedCatalogBytes = Objects.requireNonNull(cachedCatalogBytes, "cachedCatalogBytes")
                    .map(bytes -> bytes.clone());
            cachedSignatureBytes = Objects.requireNonNull(cachedSignatureBytes, "cachedSignatureBytes")
                    .map(bytes -> bytes.clone());
            stagedUpdates = Map.copyOf(Objects.requireNonNull(stagedUpdates, "stagedUpdates"));
            if (catalogVersion.isPresent() && catalogVersion.getAsLong() < 1L) {
                throw new IllegalArgumentException("catalogVersion must be positive");
            }
        }

        /** Returns an empty initial state with no prior catalog watermark. */
        public static State empty() {
            return new State(
                    OptionalLong.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Map.of());
        }

        /** Returns a copy of this state with an updated verified catalog watermark and cache. */
        public State withVerifiedCatalog(
                final PluginCatalog catalog,
                final byte[] rawCatalogBytes,
                final byte[] rawSignatureBytes,
                final Optional<String> newEtag) {
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(rawCatalogBytes, "rawCatalogBytes");
            Objects.requireNonNull(rawSignatureBytes, "rawSignatureBytes");
            Objects.requireNonNull(newEtag, "newEtag");
            return new State(
                    OptionalLong.of(catalog.catalogVersion()),
                    Optional.of(catalog.publishedAt()),
                    Optional.of(catalog.catalogSha256()),
                    newEtag.isPresent() ? newEtag : etag,
                    lastAutomaticAttempt,
                    Optional.of(rawCatalogBytes.clone()),
                    Optional.of(rawSignatureBytes.clone()),
                    stagedUpdates);
        }

        /** Returns a copy of this state with an updated {@code lastAutomaticAttempt} timestamp. */
        public State withLastAutomaticAttempt(final Instant attempt) {
            return new State(
                    catalogVersion,
                    publishedAt,
                    catalogSha256,
                    etag,
                    Optional.of(Objects.requireNonNull(attempt, "attempt")),
                    cachedCatalogBytes,
                    cachedSignatureBytes,
                    stagedUpdates);
        }

        /** Returns a copy of this state with an updated ETag. */
        public State withEtag(final Optional<String> newEtag) {
            Objects.requireNonNull(newEtag, "newEtag");
            return new State(
                    catalogVersion,
                    publishedAt,
                    catalogSha256,
                    newEtag.isPresent() ? newEtag : etag,
                    lastAutomaticAttempt,
                    cachedCatalogBytes,
                    cachedSignatureBytes,
                    stagedUpdates);
        }

        /** Returns a copy of this state with updated staged update markers. */
        public State withStagedUpdates(final Map<String, String> updates) {
            return new State(
                    catalogVersion,
                    publishedAt,
                    catalogSha256,
                    etag,
                    lastAutomaticAttempt,
                    cachedCatalogBytes,
                    cachedSignatureBytes,
                    updates);
        }
    }
}
