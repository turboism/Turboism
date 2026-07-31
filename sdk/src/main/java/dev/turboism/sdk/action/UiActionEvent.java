package dev.turboism.sdk.action;

import java.util.Objects;

/** Typed value emitted by a Turboism-owned panel control. */
public record UiActionEvent(String sourceId, Value value) {

    public UiActionEvent {
        sourceId = requireText(sourceId, "sourceId");
        value = Objects.requireNonNull(value, "value");
    }

    public static UiActionEvent text(final String sourceId, final String value) {
        return new UiActionEvent(sourceId, new TextValue(value));
    }

    public static UiActionEvent selection(final String sourceId, final String value) {
        return new UiActionEvent(sourceId, new SelectionValue(value));
    }

    public static UiActionEvent toggle(final String sourceId, final boolean value) {
        return new UiActionEvent(sourceId, new ToggleValue(value));
    }

    public sealed interface Value permits TextValue, SelectionValue, ToggleValue { }

    public record TextValue(String value) implements Value {
        public TextValue {
            value = Objects.requireNonNull(value, "value");
        }
    }

    public record SelectionValue(String value) implements Value {
        public SelectionValue {
            value = requireText(value, "value");
        }
    }

    public record ToggleValue(boolean value) implements Value { }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
