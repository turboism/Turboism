package dev.turboism.config;

import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigError;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigMigrationException;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigSchemaValidationError;
import dev.turboism.sdk.config.ConfigSchemaValidationException;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.cleanup.CleanupEvidenceCollector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** Typed-config facade layered beside the preserved legacy config registry. */
public final class RuntimeTypedPluginConfigRegistry
    implements PluginConfigRegistry, AutoCloseable {

    private static final Pattern IDENTIFIER = Pattern.compile(
        "[a-z0-9][a-z0-9._-]{0,127}"
    );

    private final PluginConfigRegistry legacy;
    private final String pluginId;
    private final Set<String> permissions;
    private final TypedConfigDocumentStore store;
    private final TypedConfigIoExecutor io;
    private final CleanupEvidenceCollector cleanupEvidence;
    private final TypedConfigFailureReporter failureReporter;
    private final Object lifecycleLock = new Object();
    private final Map<String, RegisteredSchema> schemas = new HashMap<>();
    private final Map<String, String> paths = new HashMap<>();
    private boolean active = true;

    public RuntimeTypedPluginConfigRegistry(
        final PluginConfigRegistry legacy,
        final String pluginId,
        final Path configRoot,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope
    ) {
        this(
            legacy,
            pluginId,
            configRoot,
            permissions,
            tasks,
            scope,
            new CleanupEvidenceCollector(),
            RuntimeFailureSink.noop()
        );
    }

    public RuntimeTypedPluginConfigRegistry(
        final PluginConfigRegistry legacy,
        final String pluginId,
        final Path configRoot,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector cleanupEvidence
    ) {
        this(
            legacy,
            pluginId,
            configRoot,
            permissions,
            tasks,
            scope,
            cleanupEvidence,
            RuntimeFailureSink.noop()
        );
    }

    public RuntimeTypedPluginConfigRegistry(
        final PluginConfigRegistry legacy,
        final String pluginId,
        final Path configRoot,
        final Set<String> permissions,
        final RuntimePluginTaskScheduler tasks,
        final DisposableScope scope,
        final CleanupEvidenceCollector cleanupEvidence,
        final RuntimeFailureSink failureSink
    ) {
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        this.pluginId = requireText(pluginId, "pluginId");
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        this.failureReporter = new TypedConfigFailureReporter(this.pluginId, failureSink);
        try {
            this.store = new TypedConfigDocumentStore(
                Objects.requireNonNull(configRoot, "configRoot")
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Typed config storage is unavailable.");
        }
        this.io = new TypedConfigIoExecutor(this.pluginId, tasks);
        try {
            Objects.requireNonNull(scope, "scope").register(this);
        } catch (RuntimeException exception) {
            io.close();
            throw exception;
        }
    }

    @Override
    public Registration readScope(final String relativePath) {
        return legacy.readScope(relativePath);
    }

    @Override
    public Registration writeScope(final String relativePath) {
        return legacy.writeScope(relativePath);
    }

    @Override
    public Optional<String> readString(
        final String relativePath,
        final String key
    ) {
        return legacy.readString(relativePath, key);
    }

    @Override
    public void writeString(
        final String relativePath,
        final String key,
        final String value
    ) throws PluginConfigException {
        legacy.writeString(relativePath, key, value);
    }

    @Override
    public CompletionStage<Void> registerSchema(
        final ConfigSchema schema,
        final List<ConfigMigration> migrations
    ) {
        final RegisteredSchema candidate;
        try {
            candidate = validate(schema, migrations);
        } catch (ConfigSchemaValidationException failure) {
            failureReporter.schemaValidationFailed(failure);
            throw failure;
        }
        synchronized (lifecycleLock) {
            if (schemas.containsKey(candidate.schema.configId())) {
                final ConfigSchemaValidationException failure = validation(
                    ConfigSchemaValidationError.DUPLICATE_CONFIG_ID
                );
                failureReporter.schemaValidationFailed(failure);
                throw failure;
            }
            if (paths.containsKey(candidate.schema.relativePath())) {
                final ConfigSchemaValidationException failure = validation(
                    ConfigSchemaValidationError.DUPLICATE_PATH
                );
                failureReporter.schemaValidationFailed(failure);
                throw failure;
            }
            if (!active) {
                return registrationFailure(ConfigRegistrationError.RUNTIME_UNAVAILABLE, null);
            }
            if (!has(PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE)) {
                return registrationFailure(
                    ConfigRegistrationError.PERMISSION_DENIED,
                    PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE
                );
            }
            try {
                schemas.put(candidate.schema.configId(), candidate);
                paths.put(candidate.schema.relativePath(), candidate.schema.configId());
                return io.submit(
                    () -> materializeDefaults(candidate),
                    () -> Materialization.UNAVAILABLE
                ).thenCompose(this::completeRegistration);
            } catch (RuntimeException exception) {
                schemas.remove(candidate.schema.configId());
                paths.remove(candidate.schema.relativePath());
                return registrationFailure(ConfigRegistrationError.REGISTRATION_FAILED, null);
            }
        }
    }

    @Override
    public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
        final ConfigKey<T> requested = Objects.requireNonNull(key, "key");
        if (!isActive()) {
            return io.immediate(failureReporter.observe(
                defaultUnavailable(requested, ConfigErrorCode.RUNTIME_UNAVAILABLE),
                "config.read",
                null
            ));
        }
        final RegisteredKey<T> registered = registeredKey(requested);
        if (registered == null) {
            return io.immediate(failureReporter.observe(
                defaultUnavailable(requested, ConfigErrorCode.SCHEMA_NOT_REGISTERED),
                "config.read",
                null
            ));
        }
        if (!has(PermissionIds.TURBOISM_CONFIG_PLUGIN_READ)) {
            return io.immediate(failureReporter.observe(
                defaultUnavailable(registered.key, ConfigErrorCode.PERMISSION_DENIED),
                "config.read",
                PermissionIds.TURBOISM_CONFIG_PLUGIN_READ
            ));
        }
        return io.submit(
            () -> failureReporter.observe(readNow(registered), "config.read", null),
            () -> failureReporter.observe(
                defaultUnavailable(registered.key, ConfigErrorCode.RUNTIME_UNAVAILABLE),
                "config.read",
                null
            )
        );
    }

    @Override
    public <T> CompletionStage<ConfigWriteResult> write(
        final ConfigKey<T> key,
        final T value,
        final long expectedRevision
    ) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        final ConfigKey<T> requested = Objects.requireNonNull(key, "key");
        if (!isActive()) {
            return io.immediate(failureReporter.observe(
                writeFailure(requested.name(), ConfigErrorCode.RUNTIME_UNAVAILABLE, 0),
                "config.write",
                null
            ));
        }
        final RegisteredKey<T> registered = registeredKey(requested);
        if (registered == null) {
            return io.immediate(failureReporter.observe(
                writeFailure(requested.name(), ConfigErrorCode.SCHEMA_NOT_REGISTERED, 0),
                "config.write",
                null
            ));
        }
        if (!has(PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE)) {
            return io.immediate(failureReporter.observe(
                writeFailure(registered.key.name(), ConfigErrorCode.PERMISSION_DENIED, 0),
                "config.write",
                PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE
            ));
        }
        final Optional<String> encoded = TypedConfigCodecSupport.encode(
            registered.key,
            value
        );
        return io.submit(
            () -> failureReporter.observe(
                encoded.isPresent()
                    ? writeNow(registered, encoded.orElseThrow(), expectedRevision)
                    : invalidWriteNow(registered, expectedRevision),
                "config.write",
                null
            ),
            () -> failureReporter.observe(
                writeFailure(
                    registered.key.name(),
                    ConfigErrorCode.RUNTIME_UNAVAILABLE,
                    0
                ),
                "config.write",
                null
            )
        );
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!active) {
                return;
            }
            active = false;
        }
        io.close();
        synchronized (lifecycleLock) {
            final int removed = schemas.size();
            schemas.clear();
            paths.clear();
            cleanupEvidence.configSchemasUnregistered(removed);
        }
    }

    private <T> ConfigReadResult<T> readNow(final RegisteredKey<T> registered) {
        try {
            final LoadedDocument loaded = loadCurrent(registered.schema);
            if (loaded.error != null) {
                return defaultRead(
                    registered.key,
                    loaded.source,
                    loaded.error,
                    loaded.revision
                );
            }
            final String encoded = loaded.values.get(registered.key.name());
            if (encoded == null) {
                return new ConfigReadResult<>(
                    new ConfigValue<>(
                        defaultValue(registered.key),
                        ConfigValueSource.DEFAULT_MISSING,
                        loaded.revision
                    ),
                    Optional.empty()
                );
            }
            final Optional<Object> decoded = TypedConfigCodecSupport.decode(
                registered.key,
                encoded
            );
            if (decoded.isEmpty()) {
                return defaultRead(
                    registered.key,
                    ConfigValueSource.DEFAULT_INVALID,
                    ConfigErrorCode.INVALID_VALUE,
                    loaded.revision
                );
            }
            @SuppressWarnings("unchecked")
            final T value = (T) decoded.orElseThrow();
            return new ConfigReadResult<>(
                new ConfigValue<>(value, ConfigValueSource.STORED, loaded.revision),
                Optional.empty()
            );
        } catch (IOException exception) {
            return defaultUnavailable(registered.key, ConfigErrorCode.PERSISTENCE_FAILED);
        }
    }

    private ConfigWriteResult invalidWriteNow(
        final RegisteredKey<?> registered,
        final long expectedRevision
    ) {
        return writeFailure(
            registered.key.name(),
            ConfigErrorCode.INVALID_VALUE,
            currentRevision(registered.schema)
        );
    }

    private ConfigWriteResult writeNow(
        final RegisteredKey<?> registered,
        final String encoded,
        final long expectedRevision
    ) {
        try {
            final LoadedDocument loaded = loadCurrentForWrite(registered.schema);
            if (loaded.error != null) {
                return writeFailure(
                    registered.key.name(),
                    ConfigErrorCode.PERSISTENCE_FAILED,
                    loaded.revision
                );
            }
            if (loaded.revision != expectedRevision) {
                return writeFailure(
                    registered.key.name(),
                    ConfigErrorCode.REVISION_CONFLICT,
                    loaded.revision
                );
            }
            final Map<String, String> values = new LinkedHashMap<>(loaded.values);
            values.put(registered.key.name(), encoded);
            final long revision = Math.addExact(loaded.revision, 1);
            store.writeAtomic(
                registered.schema.schema.relativePath(),
                new TypedConfigDocumentStore.StoredDocument(
                    registered.schema.schema.version(),
                    revision,
                    values
                )
            );
            return new ConfigWriteResult(true, revision, Optional.empty());
        } catch (IOException | ArithmeticException exception) {
            return writeFailure(
                registered.key.name(),
                ConfigErrorCode.PERSISTENCE_FAILED,
                currentRevision(registered.schema)
            );
        }
    }

    private long currentRevision(final RegisteredSchema schema) {
        try {
            return store.read(schema.schema.relativePath())
                .map(TypedConfigDocumentStore.StoredDocument::revision)
                .orElse(0L);
        } catch (IOException exception) {
            return 0L;
        }
    }

    private LoadedDocument loadCurrentForWrite(
        final RegisteredSchema schema
    ) throws IOException {
        final Optional<TypedConfigDocumentStore.StoredDocument> stored = store.read(
            schema.schema.relativePath()
        );
        if (stored.isEmpty()) {
            return LoadedDocument.success(0, Map.of());
        }
        return migrate(schema, stored.orElseThrow(), true);
    }

    private LoadedDocument loadCurrent(final RegisteredSchema schema) throws IOException {
        final Optional<TypedConfigDocumentStore.StoredDocument> stored = store.read(
            schema.schema.relativePath()
        );
        if (stored.isEmpty()) {
            return LoadedDocument.success(0, Map.of());
        }
        final TypedConfigDocumentStore.StoredDocument document = stored.orElseThrow();
        final LoadedDocument loaded = migrate(schema, document, false);
        if (loaded.error == null && document.schemaVersion() < schema.schema.version()) {
            final long revision;
            try {
                revision = Math.addExact(document.revision(), 1);
            } catch (ArithmeticException exception) {
                return LoadedDocument.failure(
                    document.revision(),
                    ConfigValueSource.DEFAULT_UNAVAILABLE,
                    ConfigErrorCode.PERSISTENCE_FAILED
                );
            }
            store.writeAtomic(
                schema.schema.relativePath(),
                new TypedConfigDocumentStore.StoredDocument(
                    schema.schema.version(),
                    revision,
                    loaded.values
                )
            );
            return LoadedDocument.success(revision, loaded.values);
        }
        return loaded;
    }

    private LoadedDocument migrate(
        final RegisteredSchema schema,
        final TypedConfigDocumentStore.StoredDocument stored,
        final boolean forWrite
    ) {
        if (stored.schemaVersion() > schema.schema.version()) {
            return forWrite
                ? LoadedDocument.failure(
                    stored.revision(),
                    ConfigValueSource.DEFAULT_UNAVAILABLE,
                    ConfigErrorCode.PERSISTENCE_FAILED
                )
                : LoadedDocument.failure(
                    stored.revision(),
                    ConfigValueSource.DEFAULT_FUTURE_VERSION,
                    ConfigErrorCode.FUTURE_SCHEMA_VERSION
                );
        }
        ConfigDocument document = new ConfigDocument(
            stored.schemaVersion(),
            stored.encodedValues()
        );
        try {
            while (document.schemaVersion() < schema.schema.version()) {
                final ConfigMigration migration = schema.migrations.get(
                    document.schemaVersion()
                );
                if (migration == null) {
                    return LoadedDocument.failure(
                        stored.revision(),
                        ConfigValueSource.DEFAULT_MIGRATION_FAILED,
                        ConfigErrorCode.MIGRATION_GAP
                    );
                }
                document = Objects.requireNonNull(
                    migration.migrate(document),
                    "migration result"
                );
                if (document.schemaVersion() != migration.toVersion()
                    || !validEncodedValues(schema, document.encodedValues())) {
                    throw new ConfigMigrationException("migration output is invalid");
                }
            }
            if (!validEncodedValues(schema, document.encodedValues())) {
                return LoadedDocument.failure(
                    stored.revision(),
                    ConfigValueSource.DEFAULT_UNAVAILABLE,
                    ConfigErrorCode.PERSISTENCE_FAILED
                );
            }
            return LoadedDocument.success(stored.revision(), document.encodedValues());
        } catch (ConfigMigrationException | RuntimeException exception) {
            return LoadedDocument.failure(
                stored.revision(),
                ConfigValueSource.DEFAULT_MIGRATION_FAILED,
                ConfigErrorCode.MIGRATION_FAILED
            );
        }
    }

    private static boolean validEncodedValues(
        final RegisteredSchema schema,
        final Map<String, String> values
    ) {
        if (values == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            final ConfigKey<?> key = schema.keys.get(entry.getKey());
            if (key == null || entry.getValue() == null
                || TypedConfigCodecSupport.decode(key, entry.getValue()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private RegisteredSchema validate(
        final ConfigSchema schema,
        final List<ConfigMigration> migrations
    ) {
        if (schema == null || schema.keys() == null) {
            throw validation(ConfigSchemaValidationError.INVALID_SCHEMA);
        }
        if (!validIdentifier(schema.configId())) {
            throw validation(ConfigSchemaValidationError.INVALID_CONFIG_ID);
        }
        try {
            new StoragePath(StorageRoot.DATA, schema.relativePath());
        } catch (RuntimeException exception) {
            throw validation(ConfigSchemaValidationError.INVALID_PATH);
        }
        if (schema.version() < 1) {
            throw validation(ConfigSchemaValidationError.INVALID_VERSION);
        }
        final Map<String, ConfigKey<?>> keys = new LinkedHashMap<>();
        for (ConfigKey<?> key : schema.keys()) {
            if (key == null || key.configId() == null || key.name() == null) {
                throw validation(ConfigSchemaValidationError.INVALID_KEY);
            }
            if (!schema.configId().equals(key.configId()) || !validIdentifier(key.name())) {
                throw validation(ConfigSchemaValidationError.INVALID_KEY);
            }
            if (keys.putIfAbsent(key.name(), snapshotKey(key)) != null) {
                throw validation(ConfigSchemaValidationError.DUPLICATE_KEY);
            }
            if (!TypedConfigCodecSupport.isRecognized(key.codec())) {
                throw validation(ConfigSchemaValidationError.INVALID_CODEC);
            }
            if (!TypedConfigCodecSupport.isValidDefault(key)) {
                throw validation(ConfigSchemaValidationError.INVALID_DEFAULT_VALUE);
            }
        }
        if (migrations == null) {
            throw validation(ConfigSchemaValidationError.INVALID_MIGRATION);
        }
        final Map<Integer, ConfigMigration> transitions = new HashMap<>();
        for (ConfigMigration migration : migrations) {
            if (migration == null || migration.fromVersion() < 1
                || migration.toVersion() != migration.fromVersion() + 1
                || migration.toVersion() > schema.version()) {
                throw validation(ConfigSchemaValidationError.INVALID_MIGRATION);
            }
            if (transitions.putIfAbsent(migration.fromVersion(), migration) != null) {
                throw validation(ConfigSchemaValidationError.MIGRATION_BRANCH);
            }
        }
        for (int version = 1; version < schema.version(); version++) {
            if (!transitions.containsKey(version)) {
                throw validation(ConfigSchemaValidationError.MIGRATION_GAP);
            }
        }
        if (transitions.size() != Math.max(0, schema.version() - 1)) {
            throw validation(ConfigSchemaValidationError.INVALID_MIGRATION);
        }
        final ConfigSchema snapshot = new ConfigSchema(
            schema.configId(),
            schema.relativePath(),
            schema.version(),
            new ArrayList<>(keys.values())
        );
        return new RegisteredSchema(snapshot, Map.copyOf(keys), Map.copyOf(transitions));
    }

    private static ConfigKey<?> snapshotKey(final ConfigKey<?> key) {
        return snapshotTypedKey(key);
    }

    private static <T> ConfigKey<T> snapshotTypedKey(final ConfigKey<T> key) {
        @SuppressWarnings("unchecked")
        final T defaultValue = (T) TypedConfigCodecSupport.immutableDefault(key);
        return new ConfigKey<>(
            key.configId(),
            key.name(),
            defaultValue,
            key.codec()
        );
    }

    private <T> RegisteredKey<T> registeredKey(final ConfigKey<T> requested) {
        synchronized (lifecycleLock) {
            if (!active) {
                return null;
            }
            final RegisteredSchema schema = schemas.get(requested.configId());
            if (schema == null) {
                return null;
            }
            final ConfigKey<?> candidate = schema.keys.get(requested.name());
            if (candidate == null || requested.codec() == null
                || candidate.codec() == null
                || !candidate.codec().typeId().equals(requested.codec().typeId())) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final ConfigKey<T> typed = (ConfigKey<T>) candidate;
            return new RegisteredKey<>(schema, typed);
        }
    }

    /**
     * Runs on the typed-config I/O lane: persists every declared encoded default
     * as the missing document at revision 0, or leaves an existing document
     * untouched so migration stays a read-time behavior.
     */
    private Materialization materializeDefaults(final RegisteredSchema schema) {
        try {
            if (store.read(schema.schema.relativePath()).isEmpty()) {
                final Map<String, String> defaults = new LinkedHashMap<>();
                for (ConfigKey<?> key : schema.keys.values()) {
                    defaults.put(
                        key.name(),
                        TypedConfigCodecSupport.encode(
                            key,
                            TypedConfigCodecSupport.immutableDefault(key)
                        ).orElseThrow()
                    );
                }
                store.writeAtomic(
                    schema.schema.relativePath(),
                    new TypedConfigDocumentStore.StoredDocument(
                        schema.schema.version(),
                        0,
                        defaults
                    )
                );
            }
            return Materialization.DONE;
        } catch (IOException | RuntimeException failure) {
            rollbackPublication(schema);
            return Materialization.FAILED;
        }
    }

    private void rollbackPublication(final RegisteredSchema schema) {
        synchronized (lifecycleLock) {
            schemas.remove(schema.schema.configId());
            paths.remove(schema.schema.relativePath());
        }
    }

    private CompletionStage<Void> completeRegistration(
        final Materialization materialization
    ) {
        return switch (materialization) {
            case DONE -> io.immediate(null);
            case FAILED -> registrationFailure(
                ConfigRegistrationError.REGISTRATION_FAILED,
                null
            );
            case UNAVAILABLE -> registrationFailure(
                ConfigRegistrationError.RUNTIME_UNAVAILABLE,
                null
            );
        };
    }

    private enum Materialization {
        DONE,
        FAILED,
        UNAVAILABLE
    }

    private boolean isActive() {
        synchronized (lifecycleLock) {
            return active;
        }
    }

    private boolean has(final String permission) {
        return permissions.contains(permission);
    }

    private static boolean validIdentifier(final String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static ConfigSchemaValidationException validation(
        final ConfigSchemaValidationError error
    ) {
        return new ConfigSchemaValidationException(error);
    }

    private CompletionStage<Void> registrationFailure(
        final ConfigRegistrationError error,
        final String permissionId
    ) {
        failureReporter.schemaRegistrationFailed(error, permissionId);
        return io.failed(registration(error));
    }

    private static ConfigRegistrationException registration(
        final ConfigRegistrationError error
    ) {
        return new ConfigRegistrationException(error);
    }

    private static ConfigError error(
        final ConfigErrorCode code,
        final String key
    ) {
        return new ConfigError(code, message(code), key);
    }

    private static String message(final ConfigErrorCode code) {
        return switch (code) {
            case SCHEMA_NOT_REGISTERED -> "Typed config schema is not registered.";
            case INVALID_VALUE -> "Typed config value is invalid.";
            case FUTURE_SCHEMA_VERSION -> "Typed config schema version is newer than supported.";
            case MIGRATION_GAP -> "Typed config migration chain is incomplete.";
            case MIGRATION_FAILED -> "Typed config migration failed safely.";
            case REVISION_CONFLICT -> "Typed config revision did not match.";
            case PERMISSION_DENIED -> "Typed config permission was denied.";
            case PERSISTENCE_FAILED -> "Typed config persistence failed safely.";
            case RUNTIME_UNAVAILABLE -> "Typed config runtime is unavailable.";
        };
    }

    private static ConfigWriteResult writeFailure(
        final String key,
        final ConfigErrorCode code,
        final long revision
    ) {
        return new ConfigWriteResult(
            false,
            revision,
            Optional.of(error(code, key))
        );
    }

    private static <T> ConfigReadResult<T> defaultUnavailable(
        final ConfigKey<T> key,
        final ConfigErrorCode code
    ) {
        return defaultRead(key, ConfigValueSource.DEFAULT_UNAVAILABLE, code, 0);
    }

    private static <T> ConfigReadResult<T> defaultRead(
        final ConfigKey<T> key,
        final ConfigValueSource source,
        final ConfigErrorCode code,
        final long revision
    ) {
        return new ConfigReadResult<>(
            new ConfigValue<>(defaultValue(key), source, revision),
            Optional.of(error(code, key.name()))
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T defaultValue(final ConfigKey<T> key) {
        return (T) TypedConfigCodecSupport.immutableDefault(key);
    }

}
