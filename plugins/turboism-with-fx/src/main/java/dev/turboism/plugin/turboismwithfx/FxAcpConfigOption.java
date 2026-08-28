package dev.turboism.plugin.turboismwithfx;

import java.util.List;
import java.util.Objects;

/** Detached ACP select option supplied by fx rather than a Turboism-owned provider catalog. */
record FxAcpConfigOption(
    String id,
    String name,
    String currentValue,
    List<Choice> choices
) {
    FxAcpConfigOption {
        id = requireText(id, "id");
        name = requireText(name, "name");
        currentValue = requireText(currentValue, "currentValue");
        choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("choices must not be empty");
        }
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
