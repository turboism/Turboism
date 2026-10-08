package dev.turboism.plugin.acp;

import java.util.List;
import java.util.Objects;

/** Detached ACP select option supplied by the agent rather than a Turboism-owned catalog. */
record AcpConfigOption(
        String id, String name, String category, String description, String currentValue, List<Choice> choices) {
    AcpConfigOption {
        id = requireText(id, "id");
        name = requireText(name, "name");
        category = Objects.requireNonNullElse(category, "");
        description = Objects.requireNonNullElse(description, "");
        currentValue = requireText(currentValue, "currentValue");
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("choices must not be empty");
        }
    }

    AcpConfigOption(final String id, final String name, final String currentValue, final List<Choice> choices) {
        this(id, name, "", "", currentValue, choices);
    }

    record Choice(String value, String name) {
        Choice {
            value = requireText(value, "value");
            name = requireText(name, "name");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.length() > 8192) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }
}
