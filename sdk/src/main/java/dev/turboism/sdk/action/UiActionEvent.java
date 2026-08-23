package dev.turboism.sdk.action;

import java.util.Objects;

/**
 * Typed value emitted by a Turboism-owned panel control.
 *
 * @param sourceId identity of the control that emitted the value
 * @param value the emitted value, one of the sealed {@link Value} shapes
 */
public record UiActionEvent(String sourceId, Value value) {

    /**
     * Validates the event.
     *
     * @throws IllegalArgumentException when {@code sourceId} is blank
     * @throws NullPointerException when {@code value} is null
     */
    public UiActionEvent {
        sourceId = requireText(sourceId, "sourceId");
        value = Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a free-text event.
     *
     * @param sourceId identity of the emitting control
     * @param value the text, which may be empty
     * @return the event
     */
    public static UiActionEvent text(final String sourceId, final String value) {
        return new UiActionEvent(sourceId, new TextValue(value));
    }

    /**
     * Creates a selection event.
     *
     * @param sourceId identity of the emitting control
     * @param value the selected option, which must not be blank
     * @return the event
     */
    public static UiActionEvent selection(final String sourceId, final String value) {
        return new UiActionEvent(sourceId, new SelectionValue(value));
    }

    /**
     * Creates a toggle event.
     *
     * @param sourceId identity of the emitting control
     * @param value the new toggle state
     * @return the event
     */
    public static UiActionEvent toggle(final String sourceId, final boolean value) {
        return new UiActionEvent(sourceId, new ToggleValue(value));
    }

    /** The closed set of values a panel control can emit. */
    public sealed interface Value permits TextValue, SelectionValue, ToggleValue { }

    /**
     * Free text entered by the user.
     *
     * @param value the text, which may be empty but not null
     */
    public record TextValue(String value) implements Value {
        /** @throws NullPointerException when {@code value} is null */
        public TextValue {
            value = Objects.requireNonNull(value, "value");
        }
    }

    /**
     * An option chosen from a fixed set.
     *
     * @param value the selected option
     */
    public record SelectionValue(String value) implements Value {
        /** @throws IllegalArgumentException when {@code value} is blank */
        public SelectionValue {
            value = requireText(value, "value");
        }
    }

    /**
     * A boolean control's new state.
     *
     * @param value the new state
     */
    public record ToggleValue(boolean value) implements Value { }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
