package dev.turboism.sdk.config;

import java.util.List;
import java.util.Objects;

/**
 * Factories for the bounded value types a plugin may store in typed configuration.
 *
 * <p>Every codec carries a type id that encodes its bounds, so the runtime can validate stored
 * values against the exact shape the plugin declared instead of trusting whatever is on disk.
 * Bounds are checked when the codec is created, so an impossible declaration fails at
 * registration rather than at first read.</p>
 */
public final class ConfigCodecs {

    private static final ConfigCodec<Boolean> BOOLEAN = () -> "boolean";

    private ConfigCodecs() {
    }

    /**
     * Codec for a boolean setting.
     *
     * @return the shared boolean codec
     */
    public static ConfigCodec<Boolean> booleanValue() {
        return BOOLEAN;
    }

    /**
     * Plain-text value codec (bounded length), e.g. algorithm ids.
     *
     * @param maximumLength the longest accepted value, must be positive
     * @return a codec bounded to that length
     * @throws IllegalArgumentException when {@code maximumLength} is not positive
     */
    public static ConfigCodec<String> stringValue(final int maximumLength) {
        if (maximumLength < 1) {
            throw new IllegalArgumentException("maximumLength must be positive");
        }
        return new TypeIdCodec<>("string:" + maximumLength);
    }

    /**
     * Codec for an integer constrained to an inclusive range.
     *
     * @param minimum lowest accepted value
     * @param maximum highest accepted value
     * @return a codec bounded to that range
     * @throws IllegalArgumentException when {@code minimum} exceeds {@code maximum}
     */
    public static ConfigCodec<Integer> boundedInt(
        final int minimum,
        final int maximum
    ) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        return new TypeIdCodec<>("int:" + minimum + ":" + maximum);
    }

    /**
     * Codec for one constant of an enum type.
     *
     * @param <E> the enum type
     * @param enumType the enum class whose constants are accepted
     * @return a codec bounded to that enum
     * @throws NullPointerException when {@code enumType} is null
     */
    public static <E extends Enum<E>> ConfigCodec<E> enumValue(
        final Class<E> enumType
    ) {
        return new TypeIdCodec<>(
            "enum:" + Objects.requireNonNull(enumType, "enumType").getName()
        );
    }

    /**
     * Codec for a string list bounded in both entry count and entry length.
     *
     * @param maximumEntries the most entries accepted, must not be negative
     * @param maximumEntryLength the longest accepted entry, must be positive
     * @return a codec bounded to those limits
     * @throws IllegalArgumentException when either bound is out of range
     */
    public static ConfigCodec<List<String>> boundedStringList(
        final int maximumEntries,
        final int maximumEntryLength
    ) {
        if (maximumEntries < 0) {
            throw new IllegalArgumentException("maximumEntries must not be negative");
        }
        if (maximumEntryLength <= 0) {
            throw new IllegalArgumentException("maximumEntryLength must be positive");
        }
        return new TypeIdCodec<>(
            "string-list:" + maximumEntries + ":" + maximumEntryLength
        );
    }

    private record TypeIdCodec<T>(String typeId) implements ConfigCodec<T> {
        private TypeIdCodec {
            Objects.requireNonNull(typeId, "typeId");
        }
    }
}
