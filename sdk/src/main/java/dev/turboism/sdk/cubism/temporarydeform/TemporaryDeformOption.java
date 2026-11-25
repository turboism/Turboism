package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;
import java.util.List;
import java.util.Objects;

/** Declarative control descriptors rendered inside Cubism's Tool Details panel while a custom temporary deform tool is active. */
@CubismEditor(from = "5.0.00")
public sealed interface TemporaryDeformOption permits TemporaryDeformOption.Choice, TemporaryDeformOption.Toggle {

    /** Unique identifier within the owning tool. */
    String id();

    /** Localized label displayed beside the control. */
    String label();

    /** Single-choice option rendered as a selector in Tool Details. */
    @CubismEditor(from = "5.0.00")
    record Choice(String id, String label, List<ChoiceItem> items, String selectedValue)
            implements TemporaryDeformOption {
        public Choice {
            id = requireText(id, "id");
            label = requireText(label, "label");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("items must not be empty");
            }
            items.forEach(item -> Objects.requireNonNull(item, "item"));
            selectedValue = requireText(selectedValue, "selectedValue");
            final String selected = selectedValue;
            final boolean matches = items.stream().anyMatch(item -> item.value().equals(selected));
            if (!matches) {
                throw new IllegalArgumentException("selectedValue must match one item value: " + selected);
            }
        }
    }

    /** Individual item inside a {@link Choice} option. */
    @CubismEditor(from = "5.0.00")
    record ChoiceItem(String value, String label) {
        public ChoiceItem {
            value = requireText(value, "value");
            label = requireText(label, "label");
        }
    }

    /** Boolean toggle option rendered as a checkbox in Tool Details. */
    @CubismEditor(from = "5.0.00")
    record Toggle(String id, String label, boolean selected) implements TemporaryDeformOption {
        public Toggle {
            id = requireText(id, "id");
            label = requireText(label, "label");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
