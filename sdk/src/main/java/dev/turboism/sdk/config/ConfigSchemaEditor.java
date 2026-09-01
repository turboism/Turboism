package dev.turboism.sdk.config;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Explicit presentation metadata for exposing selected typed-config keys in the shared settings UI.
 *
 * <p>The editor is deliberately opt-in and carries no secret/inferred-field behavior. Every field
 * names one key in the accompanying {@link ConfigSchema}; the runtime validates that reference and
 * its codec compatibility before registering the schema.</p>
 *
 * @param fields non-empty editor fields in declaration order
 */
public record ConfigSchemaEditor(List<Field> fields) {

    public ConfigSchemaEditor {
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
        final HashSet<String> keys = new HashSet<>();
        for (Field field : fields) {
            final Field value = Objects.requireNonNull(field, "field");
            if (!keys.add(value.key())) {
                throw new IllegalArgumentException("editor field keys must be unique");
            }
        }
    }

    /** Presentation metadata common to each supported scalar settings control. */
    public sealed interface Field permits Toggle, Text, Choice {
        String key();
        String label();
        OptionalInt index();
    }

    /** Boolean key rendered as a toggle. */
    public record Toggle(String key, String label, OptionalInt index) implements Field {
        public Toggle {
            key = requireKey(key);
            label = requireText(label, "label", 256);
            index = requireIndex(index);
        }    }

    /** String or bounded-integer key rendered as a text field. */
    public record Text(String key, String label, int columns, OptionalInt index) implements Field {
        public Text {
            key = requireKey(key);
            label = requireText(label, "label", 256);
            if (columns < 1 || columns > 128) {
                throw new IllegalArgumentException("columns must be between 1 and 128");
            }
            index = requireIndex(index);
        }    }

    /** String or enum key rendered as an explicitly-labelled choice. */
    public record Choice(
        String key,
        String label,
        List<Option> options,
        OptionalInt index
    ) implements Field {
        public Choice {
            key = requireKey(key);
            label = requireText(label, "label", 256);
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.isEmpty() || options.size() > 128) {
                throw new IllegalArgumentException("options must contain between 1 and 128 entries");
            }
            final HashSet<String> values = new HashSet<>();
            for (Option option : options) {
                final Option value = Objects.requireNonNull(option, "option");
                if (!values.add(value.value())) {
                    throw new IllegalArgumentException("option values must be unique");
                }
            }
            index = requireIndex(index);
        }    }

    /** One stored choice value and its localized display label. */
    public record Option(String value, String label) {
        public Option {
            value = requireText(value, "value", 256);
            label = requireText(label, "label", 256);
        }
    }

    private static String requireKey(final String value) {
        final String key = requireText(value, "key", 128);
        if (!key.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("key must be a lowercase config key");
        }
        return key;
    }

    private static String requireText(final String value, final String name, final int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                name + " must contain 1-" + maximum + " characters"
            );
        }
        return value;
    }

    private static OptionalInt requireIndex(final OptionalInt value) {
        final OptionalInt index = Objects.requireNonNull(value, "index");
        if (index.isPresent() && index.getAsInt() < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        return index;
    }
}
