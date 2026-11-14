package dev.turboism.internal;

import java.util.Objects;

/** Holds the argument guards shared by runtime and bootstrap value types. */
public final class Requirements {

    /**
     * Returns the value after rejecting a null or blank text.
     *
     * @param value the text to check
     * @param name the parameter name reported by the failure
     * @return the accepted text, unchanged
     */
    public static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Returns the value after rejecting a non-finite float.
     *
     * @param value the number to check
     * @param name the parameter name reported by the failure
     * @return the accepted number, unchanged
     */
    public static float finite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    /**
     * Returns the value after rejecting a zero or negative count.
     *
     * @param value the count to check
     * @param name the parameter name reported by the failure
     * @return the accepted count, unchanged
     */
    public static int positive(final int value, final String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * Returns the value after rejecting a negative count.
     *
     * @param value the count to check
     * @param name the parameter name reported by the failure
     * @return the accepted count, unchanged
     */
    public static int nonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private Requirements() {}
}
