package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.schema.runtimeconfig.RuntimeConfigValidator;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Single Runtime-owned authority for the canonical global config document. */
public final class RuntimeConfigRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 64L * 1024L;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path home;
    private final Path configPath;
    private final Object lock;
    private final Consumer<String> diagnostic;

    public RuntimeConfigRepository(final Path requestedHome, final Consumer<String> diagnostic) {
        home = Objects.requireNonNull(requestedHome, "requestedHome").toAbsolutePath().normalize();
        configPath = home.resolve("config.json").normalize();
        if (!configPath.startsWith(home)) throw new IllegalArgumentException("config path escapes Turboism home");
        lock = LOCKS.computeIfAbsent(configPath, ignored -> new Object());
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /**
     * Reads the canonical config document, materializing the defaults on first use.
     *
     * <p>When {@code config.json} does not exist it is created from {@link #defaults()} through the
     * same bounded, symlink-rejecting atomic write path as {@link #update}; there is deliberately
     * no in-memory fallback, so a creation failure surfaces rather than being papered over. A
     * persisted locale the validator does not allow is dropped from the returned document and
     * reported as {@code RUNTIME_CONFIG_BAD_LOCALE}, leaving the file on disk untouched.</p>
     *
     * @return a detached document; mutating it does not affect the file or other readers
     * @throws IllegalStateException if the file is a symlink, not a regular file, larger than 64
     *     KiB, unparseable, schema-invalid, or could not be created
     */
    public ObjectNode read() {
        synchronized (lock) {
            if (!Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) {
                // First canonical read materializes the defaults through the same
                // bounded, pretty-JSON, symlink-rejecting atomic write path used by
                // update(); a creation failure stays observable as
                // RUNTIME_CONFIG_WRITE_FAILED with no in-memory fallback.
                final ObjectNode created = defaults();
                writeLocked(created);
                return created;
            }
            return readLocked();
        }
    }

    /**
     * @return an immutable set of the plugin IDs the operator has switched off; empty when none
     *     are disabled. Reflects the file as of this call — it is not a live view.
     * @throws IllegalStateException under the same conditions as {@link #read()}
     */
    public Set<String> disabledPlugins() {
        final JsonNode values = read().path("disabledPlugins");
        final Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.textValue()));
        return Set.copyOf(result);
    }

    /**
     * Adds or removes a plugin ID from the persisted disable list, rewriting the list in sorted
     * order so the file stays stable across edits. Enabling a plugin that was never disabled and
     * disabling one already disabled are both no-op writes rather than errors.
     *
     * @param pluginId dotted lowercase plugin ID, validated before anything is written
     * @param enabled true to remove the ID from the disable list, false to add it
     * @throws IllegalArgumentException if {@code pluginId} is null or not a well-formed plugin ID
     * @throws IllegalStateException if the resulting document fails validation or cannot be written
     */
    public void setPluginEnabled(final String pluginId, final boolean enabled) {
        update(root -> {
            final Set<String> ids = new HashSet<>();
            requirePluginId(pluginId);
            root.path("disabledPlugins").forEach(value -> ids.add(value.textValue()));
            if (enabled) ids.remove(pluginId); else ids.add(pluginId);
            final ArrayNode values = root.putArray("disabledPlugins");
            ids.stream().sorted().forEach(values::add);
            return root;
        });
    }

    /**
     * Applies a change to the config document under the per-path lock, so concurrent updaters in
     * this JVM serialize rather than clobber one another.
     *
     * <p>The operator is handed a deep copy of the current document and may mutate it freely. The
     * result is validated in strict write mode before any bytes are written, and the write itself
     * is a temp-file-plus-atomic-move, so the file is never left partially written. Writes are
     * strict where {@link #read()} is tolerant: an unsupported locale fails here.</p>
     *
     * @param change transformation of the current document; must not return null
     * @return a detached copy of the document as persisted
     * @throws NullPointerException if {@code change} returns null
     * @throws IllegalStateException if the updated document is schema-invalid
     *     ({@code RUNTIME_CONFIG_WRITE_INVALID}), exceeds 64 KiB, or cannot be written
     */
    public ObjectNode update(final UnaryOperator<ObjectNode> change) {
        synchronized (lock) {
            final ObjectNode updated = Objects.requireNonNull(change.apply(readLocked().deepCopy()), "updated");
            validate(updated, "RUNTIME_CONFIG_WRITE_INVALID");
            writeLocked(updated);
            return updated.deepCopy();
        }
    }

    private ObjectNode readLocked() {
        if (!Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) return defaults();
        try {
            rejectSymlinkChain(configPath);
            if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)
                || Files.size(configPath) > MAX_BYTES) {
                throw failure("RUNTIME_CONFIG_FILE_REJECTED");
            }
            final byte[] bytes = Files.readAllBytes(configPath);
            if (bytes.length > MAX_BYTES) throw failure("RUNTIME_CONFIG_FILE_REJECTED");
            final JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode object)) throw failure("RUNTIME_CONFIG_INVALID");
            validateForRead(object);
            // Read-mode locale tolerance: an unsupported persisted locale is treated as
            // absent for the in-memory read (structured diagnostic only); the on-disk
            // file stays untouched until an explicit save, and writes stay strict.
            if (object.has("locale")
                && !dev.turboism.core.schema.runtimeconfig.RuntimeConfigValidator
                    .isAllowedLocale(object.path("locale").asText(""))) {
                report("RUNTIME_CONFIG_BAD_LOCALE");
                object.remove("locale");
            }
            return object.deepCopy();
        } catch (IOException failure) {
            throw failure("RUNTIME_CONFIG_UNREADABLE", failure);
        }
    }

    private void writeLocked(final ObjectNode root) {
        final Path parent = configPath.getParent();
        final Path temporary = parent.resolve(".config-" + java.util.UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(parent);
            rejectSymlinkChain(parent);
            final byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            if (bytes.length > MAX_BYTES) throw failure("RUNTIME_CONFIG_TOO_LARGE");
            Files.write(temporary, bytes);
            move(temporary, configPath);
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { failure.addSuppressed(ignored); }
            throw failure("RUNTIME_CONFIG_WRITE_FAILED", failure);
        }
    }

    private void validate(final JsonNode root, final String code) {
        if (!new RuntimeConfigValidator().validate(root, configPath.toString()).isEmpty()) throw failure(code);
    }

    /** Read-mode validation tolerates only the persisted-locale field; everything else fails closed. */
    private void validateForRead(final JsonNode root) {
        if (!new RuntimeConfigValidator().validateForRead(root, configPath.toString()).isEmpty()) {
            throw failure("RUNTIME_CONFIG_INVALID");
        }
    }

    private void rejectSymlinkChain(final Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null && current.startsWith(home)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("symbolic link rejected");
            }
            if (current.equals(home)) break;
            current = current.getParent();
        }
        if (current == null || !current.equals(home)) throw new IOException("path escaped Turboism home");
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(home)) {
            throw new IOException("Turboism home symbolic link rejected");
        }
    }

    private IllegalStateException failure(final String code) {
        report(code);
        return new IllegalStateException(code);
    }

    private IllegalStateException failure(final String code, final Throwable cause) {
        report(code);
        return new IllegalStateException(code, cause);
    }

    private void report(final String code) {
        try { diagnostic.accept(code); } catch (RuntimeException ignored) { }
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void requirePluginId(final String pluginId) {
        if (pluginId == null || !pluginId.matches("^[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+$")) {
            throw new IllegalArgumentException("invalid plugin ID");
        }
    }

    /**
     * Builds the factory-default config document: schema version 1, a single {@code plugins}
     * directory, nothing disabled, {@code INFO} logging, safe mode off, and empty hook lists.
     *
     * @return a fresh mutable document each call; callers may modify it without affecting defaults
     *     handed to anyone else
     */
    public static ObjectNode defaults() {
        final ObjectNode root = JSON.createObjectNode();
        root.put("format", "turboism.runtime.config");
        root.put("schemaVersion", 1);
        root.put("worktreeId", "turboism-runtime");
        root.putArray("pluginDirs").add("plugins");
        root.putArray("disabledPlugins");
        root.put("logLevel", "INFO");
        root.put(
            "maxLogStorageMiB",
            dev.turboism.sdk.runtime.RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB
        );
        root.put("safeMode", false);
        final ObjectNode hooks = root.putObject("hooks");
        hooks.putArray("disabledIds");
        hooks.putArray("denylistedClasses");
        hooks.putObject("startup");
        return root;
    }
}
