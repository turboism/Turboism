package dev.turboism.config;

import dev.turboism.sdk.config.ConfigError;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchemaEditor;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import dev.turboism.ui.settings.SettingsContributionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates config-editor metadata and publishes synchronous settings bindings over typed config. */
final class ConfigSchemaSettingsPublisher implements AutoCloseable {

    private static final Pattern STRING = Pattern.compile("string:([1-9][0-9]*)");
    private static final Pattern INTEGER = Pattern.compile("int:(-?[0-9]+):(-?[0-9]+)");
    private static final String TAB_PREFIX = "plugin-config.";
    private static final int HASH_HEX_LENGTH = 48;

    private final RuntimeTypedPluginConfigRegistry registry;
    private final String pluginId;
    private final String pluginName;
    private final SettingsContributionStore store;
    private final Object lifecycleLock = new Object();
    private final List<Registration> registrations = new ArrayList<>();
    private int nextIndex;
    private boolean active = true;

    ConfigSchemaSettingsPublisher(
        final RuntimeTypedPluginConfigRegistry registry,
        final String pluginId,
        final String pluginName,
        final SettingsContributionStore store
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pluginId = requireText(pluginId, "pluginId");
        this.pluginName = requireText(pluginName, "pluginName");
        this.store = Objects.requireNonNull(store, "store");
    }

    PreparedEditor prepare(
        final RegisteredSchema schema,
        final ConfigSchemaEditor editor
    ) {
        Objects.requireNonNull(schema, "schema");
        final List<IndexedField> declared = new ArrayList<>();
        int ordinal = 0;
        for (ConfigSchemaEditor.Field field : Objects.requireNonNull(editor, "editor").fields()) {
            final ConfigKey<?> key = schema.keys.get(field.key());
            if (key == null) {
                throw unsupported(field.key(), "does not reference a key in the schema");
            }
            validateCompatibility(field, key);
            declared.add(new IndexedField(field, key, ordinal++));
        }
        declared.sort(Comparator
            .comparing((IndexedField value) -> value.field().index().isEmpty())
            .thenComparingInt(value -> value.field().index().orElse(Integer.MAX_VALUE))
            .thenComparingInt(IndexedField::ordinal));
        final int base;
        synchronized (lifecycleLock) {
            try {
                base = nextIndex;
                nextIndex = Math.addExact(nextIndex, declared.size());
            } catch (ArithmeticException failure) {
                throw new IllegalStateException("config settings contribution order is exhausted", failure);
            }
        }
        return new PreparedEditor(schema.schema.configId(), base, List.copyOf(declared));
    }

    void publish(final PreparedEditor prepared) {
        final ConfigRevisionBridge bridge = new ConfigRevisionBridge(registry);
        final SettingsTab tab = new SettingsTab(tabId(pluginId), pluginName);
        final List<Registration> added = new ArrayList<>();
        try {
            for (int ordinal = 0; ordinal < prepared.fields().size(); ordinal++) {
                final IndexedField field = prepared.fields().get(ordinal);
                final String id = contributionId(prepared.configId(), field.field().key());
                added.add(store.register(pluginId, new SettingsContribution(
                    id,
                    tab,
                    OptionalInt.of(Math.addExact(prepared.baseIndex(), ordinal)),
                    control(id, field, bridge)
                )));
            }
        } catch (RuntimeException failure) {
            closeReverse(added);
            throw failure;
        }
        synchronized (lifecycleLock) {
            if (active) {
                registrations.addAll(added);
                return;
            }
        }
        closeReverse(added);
    }

    static String tabId(final String pluginId) {
        return TAB_PREFIX + sha256(requireText(pluginId, "pluginId")).substring(0, HASH_HEX_LENGTH);
    }

    @Override
    public void close() {
        final List<Registration> removed;
        synchronized (lifecycleLock) {
            if (!active) return;
            active = false;
            removed = new ArrayList<>(registrations);
            registrations.clear();
        }
        closeReverse(removed);
    }

    private static SettingsControl control(
        final String id,
        final IndexedField indexed,
        final ConfigRevisionBridge bridge
    ) {
        final ConfigSchemaEditor.Field field = indexed.field();
        final ConfigKey<?> key = indexed.key();
        if (field instanceof ConfigSchemaEditor.Toggle toggle) {
            return new SettingsControl.Toggle(
                id,
                toggle.label(),
                new ConfigBinding<>(
                    booleanKey(key), bridge, Function.identity(), Function.identity()
                )
            );
        }
        if (field instanceof ConfigSchemaEditor.Text text) {
            return new SettingsControl.Text(
                id,
                text.label(),
                text.columns(),
                textBinding(key, bridge)
            );
        }
        if (field instanceof ConfigSchemaEditor.Choice choice) {
            return new SettingsControl.Choice(
                id,
                choice.label(),
                choice.options().stream()
                    .map(option -> new SettingsControl.Option(option.value(), option.label()))
                    .toList(),
                choiceBinding(key, choice.options(), bridge)
            );
        }
        throw unsupported(field.key(), "uses an unsupported field kind");
    }

    private static SettingsBinding<String> textBinding(
        final ConfigKey<?> key,
        final ConfigRevisionBridge bridge
    ) {
        final String type = key.codec().typeId();
        if (STRING.matcher(type).matches()) {
            return new ConfigBinding<>(
                stringKey(key), bridge, Function.identity(), Function.identity()
            );
        }
        final Matcher integer = INTEGER.matcher(type);
        if (!integer.matches()) throw unsupported(key.name(), "is not compatible with text");
        final int minimum = Integer.parseInt(integer.group(1));
        final int maximum = Integer.parseInt(integer.group(2));
        return new ConfigBinding<>(
            integerKey(key),
            bridge,
            Object::toString,
            value -> parseInteger(key.name(), value, minimum, maximum)
        );
    }

    private static SettingsBinding<String> choiceBinding(
        final ConfigKey<?> key,
        final List<ConfigSchemaEditor.Option> options,
        final ConfigRevisionBridge bridge
    ) {
        final Set<String> allowed = options.stream()
            .map(ConfigSchemaEditor.Option::value)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final String fallback = choiceValue(key.defaultValue());
        final Function<String, String> displayed = value -> allowed.contains(value) ? value : fallback;
        final String type = key.codec().typeId();
        if (STRING.matcher(type).matches()) {
            return new ConfigBinding<>(stringKey(key), bridge, displayed, Function.identity());
        }
        if (!type.startsWith("enum:")) {
            throw unsupported(key.name(), "is not compatible with choice");
        }
        return new ConfigBinding<>(
            enumKey(key),
            bridge,
            value -> displayed.apply(value.name()),
            value -> enumValue(key, value)
        );
    }

    private static void validateCompatibility(
        final ConfigSchemaEditor.Field field,
        final ConfigKey<?> key
    ) {
        final String type = key.codec().typeId();
        if (field instanceof ConfigSchemaEditor.Toggle) {
            if (!"boolean".equals(type)) {
                throw unsupported(field.key(), "toggle requires the boolean codec");
            }
            return;
        }
        if (field instanceof ConfigSchemaEditor.Text) {
            if (!STRING.matcher(type).matches() && !INTEGER.matcher(type).matches()) {
                throw unsupported(field.key(), "text requires a string or bounded-int codec");
            }
            return;
        }
        if (field instanceof ConfigSchemaEditor.Choice choice) {
            final Matcher string = STRING.matcher(type);
            if (string.matches()) {
                final int maximum = Integer.parseInt(string.group(1));
                if (choice.options().stream().anyMatch(option -> option.value().length() > maximum)) {
                    throw unsupported(field.key(), "choice option exceeds the string codec bound");
                }
                requireDefaultOption(choice, key);
                return;
            }
            if (type.startsWith("enum:") && key.defaultValue() instanceof Enum<?> defaultValue
                && type.substring("enum:".length()).equals(defaultValue.getDeclaringClass().getName())) {
                for (ConfigSchemaEditor.Option option : choice.options()) {
                    enumValue(key, option.value());
                }
                requireDefaultOption(choice, key);
                return;
            }
            throw unsupported(field.key(), "choice requires a string or enum codec");
        }
        throw unsupported(field.key(), "uses an unsupported field kind");
    }

    private static void requireDefaultOption(
        final ConfigSchemaEditor.Choice choice,
        final ConfigKey<?> key
    ) {
        final String defaultValue = choiceValue(key.defaultValue());
        if (choice.options().stream().noneMatch(option -> option.value().equals(defaultValue))) {
            throw unsupported(choice.key(), "choice options must include the key default value");
        }
    }

    private static String choiceValue(final Object value) {
        return value instanceof Enum<?> enumValue ? enumValue.name() : (String) value;
    }

    private static int parseInteger(
        final String key,
        final String value,
        final int minimum,
        final int maximum
    ) {
        final int parsed;
        try {
            parsed = Integer.parseInt(Objects.requireNonNull(value, "value"));
        } catch (NumberFormatException failure) {
            throw unsupported(key, "requires an integer between " + minimum + " and " + maximum);
        }
        if (parsed < minimum || parsed > maximum) {
            throw unsupported(key, "requires an integer between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Enum enumValue(final ConfigKey<?> key, final String value) {
        if (!(key.defaultValue() instanceof Enum<?> defaultValue)) {
            throw unsupported(key.name(), "enum choice has no enum default value");
        }
        try {
            return Enum.valueOf((Class) defaultValue.getDeclaringClass(), value);
        } catch (IllegalArgumentException failure) {
            throw unsupported(key.name(), "choice option is not an enum constant: " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConfigKey<Boolean> booleanKey(final ConfigKey<?> key) {
        return (ConfigKey<Boolean>) key;
    }

    @SuppressWarnings("unchecked")
    private static ConfigKey<String> stringKey(final ConfigKey<?> key) {
        return (ConfigKey<String>) key;
    }

    @SuppressWarnings("unchecked")
    private static ConfigKey<Integer> integerKey(final ConfigKey<?> key) {
        return (ConfigKey<Integer>) key;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ConfigKey<Enum> enumKey(final ConfigKey<?> key) {
        return (ConfigKey) key;
    }

    private static String contributionId(final String configId, final String key) {
        return "config." + sha256(configId + '\0' + key).substring(0, HASH_HEX_LENGTH);
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IllegalArgumentException unsupported(final String key, final String reason) {
        return new IllegalArgumentException("config editor field " + key + " " + reason);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void closeReverse(final List<Registration> values) {
        for (int index = values.size() - 1; index >= 0; index--) values.get(index).close();
    }

    record PreparedEditor(String configId, int baseIndex, List<IndexedField> fields) {
    }

    private record IndexedField(ConfigSchemaEditor.Field field, ConfigKey<?> key, int ordinal) {
    }

    private static final class ConfigBinding<T, U> implements SettingsBinding<U> {
        private final ConfigKey<T> key;
        private final ConfigRevisionBridge bridge;
        private final Function<? super T, ? extends U> display;
        private final Function<? super U, ? extends T> parse;
        private U initial;
        private boolean initialized;

        private ConfigBinding(
            final ConfigKey<T> key,
            final ConfigRevisionBridge bridge,
            final Function<? super T, ? extends U> display,
            final Function<? super U, ? extends T> parse
        ) {
            this.key = key;
            this.bridge = bridge;
            this.display = display;
            this.parse = parse;
        }

        @Override
        public synchronized U read() {
            initial = display.apply(bridge.read(key));
            initialized = true;
            return initial;
        }

        @Override
        public synchronized void write(final U value) {
            if (!initialized) read();
            if (Objects.equals(initial, value)) return;
            bridge.write(key, parse.apply(value));
            initial = value;
        }
    }

    private static final class ConfigRevisionBridge {
        private final RuntimeTypedPluginConfigRegistry registry;
        private long revision;

        private ConfigRevisionBridge(final RuntimeTypedPluginConfigRegistry registry) {
            this.registry = registry;
        }

        synchronized <T> T read(final ConfigKey<T> key) {
            final ConfigReadResult<T> result = await(registry.read(key), "read", key.name());
            revision = result.value().revision();
            return result.value().value();
        }

        synchronized <T> void write(final ConfigKey<T> key, final T value) {
            ConfigWriteResult result = writeOnce(key, value);
            if (!result.written()
                && result.error().map(ConfigError::code)
                    .filter(ConfigErrorCode.REVISION_CONFLICT::equals).isPresent()) {
                read(key);
                result = writeOnce(key, value);
            }
            revision = result.revision();
            if (!result.written()) {
                final ConfigError error = result.error().orElseThrow();
                throw new IllegalStateException(
                    "config settings write failed for " + key.name() + ": " + error.code()
                );
            }
        }

        private <T> ConfigWriteResult writeOnce(final ConfigKey<T> key, final T value) {
            final ConfigWriteResult result = await(
                registry.write(key, value, revision),
                "write",
                key.name()
            );
            revision = result.revision();
            return result;
        }

        private static <T> T await(
            final java.util.concurrent.CompletionStage<T> stage,
            final String operation,
            final String key
        ) {
            try {
                return stage.toCompletableFuture().join();
            } catch (CompletionException failure) {
                final Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                throw new IllegalStateException(
                    "config settings " + operation + " failed for " + key,
                    cause
                );
            }
        }
    }
}
