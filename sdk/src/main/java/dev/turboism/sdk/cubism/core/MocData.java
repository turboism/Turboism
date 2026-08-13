package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Defensive-copy wrapper for MOC bytes submitted for inspection. */
@PreviewApi
public final class MocData {
    private final byte[] bytes;

    private MocData(final byte[] bytes) {
        this.bytes = bytes;
    }

    public static MocData copyOf(final byte[] bytes) {
        final byte[] copy = Objects.requireNonNull(bytes, "bytes").clone();
        if (copy.length == 0) {
            throw new IllegalArgumentException("MOC data must not be empty.");
        }
        return new MocData(copy);
    }

    public int size() {
        return bytes.length;
    }

    public byte[] toByteArray() {
        return bytes.clone();
    }
}
