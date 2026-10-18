package dev.turboism.test.config;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigSchemaEditor;
import dev.turboism.sdk.config.ConfigSchemaValidationError;
import dev.turboism.sdk.config.ConfigSchemaValidationException;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * In-memory implementation of {@link PluginConfigRegistry} supporting both typed
 * schema configurations and legacy scoped-string configurations.
 */
public class InMemoryConfigRegistry implements PluginConfigRegistry {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private final Map<ConfigKey<?>, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> scopedStrings = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong(0);

    private volatile ConfigSchema schema;
    private volatile List<ConfigMigration> migrations = List.of();
    private volatile ConfigSchemaEditor editor;
    private volatile String failWriteStringKey;

    public ConfigSchema lastSchema() {
        return schema;
    }

    public List<ConfigMigration> lastMigrations() {
        return migrations;
    }

    public ConfigSchemaEditor lastEditor() {
        return editor;
    }

    public long revision() {
        return revision.get();
    }

    public <T> InMemoryConfigRegistry set(final ConfigKey<T> key, final T value) {
        Objects.requireNonNull(key, "key");
        values.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(final ConfigKey<T> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable((T) values.get(key));
    }

    public InMemoryConfigRegistry setString(final String relativePath, final String key, final String value) {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        scopedStrings
                .computeIfAbsent(relativePath, k -> new ConcurrentHashMap<>())
                .put(key, value);
        return this;
    }

    public Optional<String> getString(final String relativePath, final String key) {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(key, "key");
        final Map<String, String> doc = scopedStrings.get(relativePath);
        return doc != null ? Optional.ofNullable(doc.get(key)) : Optional.empty();
    }

    public void setFailWriteStringKey(final String key) {
        this.failWriteStringKey = key;
    }

    @Override
    public CompletionStage<Void> registerSchema(final ConfigSchema schema, final List<ConfigMigration> migrations) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(migrations, "migrations");
        for (final ConfigKey<?> key : schema.keys()) {
            if (!schema.configId().equals(key.configId())
                    || !IDENTIFIER.matcher(key.name()).matches()) {
                throw new ConfigSchemaValidationException(ConfigSchemaValidationError.INVALID_KEY);
            }
        }
        this.schema = schema;
        this.migrations = List.copyOf(migrations);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> registerUserEditableSchema(
            final ConfigSchema schema, final List<ConfigMigration> migrations, final ConfigSchemaEditor editor) {
        this.editor = editor;
        return registerSchema(schema, migrations);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
        Objects.requireNonNull(key, "key");
        final boolean stored = values.containsKey(key);
        final T current = stored ? (T) values.get(key) : key.defaultValue();
        final ConfigValueSource source = stored ? ConfigValueSource.STORED : ConfigValueSource.DEFAULT_MISSING;
        return CompletableFuture.completedFuture(
                new ConfigReadResult<>(new ConfigValue<>(current, source, revision.get()), Optional.empty()));
    }

    @Override
    public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key, final T value, final long expectedRevision) {
        Objects.requireNonNull(key, "key");
        if (expectedRevision != revision.get()) {
            return CompletableFuture.completedFuture(new ConfigWriteResult(
                    false,
                    revision.get(),
                    Optional.of(new dev.turboism.sdk.config.ConfigError(
                            dev.turboism.sdk.config.ConfigErrorCode.REVISION_CONFLICT,
                            "revision conflict",
                            key.name()))));
        }
        values.put(key, value);
        final long nextRev = revision.incrementAndGet();
        return CompletableFuture.completedFuture(new ConfigWriteResult(true, nextRev, Optional.empty()));
    }

    @Override
    public Registration readScope(final String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        return () -> {};
    }

    @Override
    public Registration writeScope(final String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        return () -> {};
    }

    @Override
    public Optional<String> readString(final String relativePath, final String key) {
        return getString(relativePath, key);
    }

    @Override
    public void writeString(final String relativePath, final String key, final String value)
            throws PluginConfigException {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(key, "key");
        if (key.equals(failWriteStringKey)) {
            throw new PluginConfigException("write rejected for " + key);
        }
        setString(relativePath, key, value);
    }
}
