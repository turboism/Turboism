package dev.turboism.sdk.config;

import java.util.List;
import java.util.Objects;

public final class ConfigCodecs {

    private static final ConfigCodec<Boolean> BOOLEAN = () -> "boolean";

    private ConfigCodecs() {
    }

    public static ConfigCodec<Boolean> booleanValue() {
        return BOOLEAN;
    }

    /** Plain-text value codec (bounded length), e.g. algorithm ids. */
    public static ConfigCodec<String> stringValue(final int maximumLength) {
        if (maximumLength < 1) {
            throw new IllegalArgumentException("maximumLength must be positive");
        }
        return new TypeIdCodec<>("string:" + maximumLength);
    }

    public static ConfigCodec<Integer> boundedInt(
        final int minimum,
        final int maximum
    ) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        return new TypeIdCodec<>("int:" + minimum + ":" + maximum);
    }

    public static <E extends Enum<E>> ConfigCodec<E> enumValue(
        final Class<E> enumType
    ) {
        return new TypeIdCodec<>(
            "enum:" + Objects.requireNonNull(enumType, "enumType").getName()
        );
    }

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
