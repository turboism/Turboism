package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.PreviewApi;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Toolkit-neutral controls supported by the shared Turboism settings window. */
@PreviewApi
public sealed interface SettingsControl permits
    SettingsControl.Choice,
    SettingsControl.Toggle,
    SettingsControl.Text {

    String id();

    String label();

    record Option(String value, String label) {
        public Option {
            value = requireText(value, "value", 256);
            label = requireText(label, "label", 256);
        }
        @Override public String toString() { return label; }
    }

    record Choice(
        String id,
        String label,
        List<Option> options,
        SettingsBinding<String> binding,
        SettingsChangeValidator<String> validator
    ) implements SettingsControl {
        public Choice {
            id = requireId(id);
            label = requireText(label, "label", 256);
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.isEmpty() || options.size() > 128) {
                throw new IllegalArgumentException("options must contain between 1 and 128 entries");
            }
            final HashSet<String> values = new HashSet<>();
            for (Option option : options) {
                Objects.requireNonNull(option, "option");
                if (!values.add(option.value())) {
                    throw new IllegalArgumentException("option values must be unique");
                }
            }
            binding = Objects.requireNonNull(binding, "binding");
            validator = Objects.requireNonNull(validator, "validator");
        }

        public Choice(
            final String id,
            final String label,
            final List<Option> options,
            final SettingsBinding<String> binding
        ) {
            this(id, label, options, binding, SettingsChangeValidator.acceptAll());
        }
    }

    record Toggle(
        String id,
        String label,
        SettingsBinding<Boolean> binding,
        SettingsChangeValidator<Boolean> validator
    ) implements SettingsControl {
        public Toggle {
            id = requireId(id);
            label = requireText(label, "label", 256);
            binding = Objects.requireNonNull(binding, "binding");
            validator = Objects.requireNonNull(validator, "validator");
        }

        public Toggle(
            final String id,
            final String label,
            final SettingsBinding<Boolean> binding
        ) {
            this(id, label, binding, SettingsChangeValidator.acceptAll());
        }
    }

    record Text(
        String id,
        String label,
        int columns,
        SettingsBinding<String> binding,
        SettingsChangeValidator<String> validator
    ) implements SettingsControl {
        public Text {
            id = requireId(id);
            label = requireText(label, "label", 256);
            if (columns < 1 || columns > 128) {
                throw new IllegalArgumentException("columns must be between 1 and 128");
            }
            binding = Objects.requireNonNull(binding, "binding");
            validator = Objects.requireNonNull(validator, "validator");
        }

        public Text(
            final String id,
            final String label,
            final int columns,
            final SettingsBinding<String> binding
        ) {
            this(id, label, columns, binding, SettingsChangeValidator.acceptAll());
        }
    }

    private static String requireId(final String value) {
        final String id = requireText(value, "id", 128);
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("id contains unsupported characters");
        }
        return id;
    }

    private static String requireText(final String value, final String name, final int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1-" + max + " characters");
        }
        return value;
    }
}
