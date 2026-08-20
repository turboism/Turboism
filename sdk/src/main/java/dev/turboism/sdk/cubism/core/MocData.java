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

    /**
     * @param bytes the MOC payload; cloned on entry so later mutation of the caller's array cannot
     *     affect the returned instance
     * @return an immutable wrapper around a private copy of the bytes
     * @throws NullPointerException if {@code bytes} is {@code null}
     * @throws IllegalArgumentException if {@code bytes} is empty
     */
    public static MocData copyOf(final byte[] bytes) {
        final byte[] copy = Objects.requireNonNull(bytes, "bytes").clone();
        if (copy.length == 0) {
            throw new IllegalArgumentException("MOC data must not be empty.");
        }
        return new MocData(copy);
    }

    /** @return the MOC payload length in bytes, always at least one. */
    public int size() {
        return bytes.length;
    }

    /**
     * @return a fresh copy of the MOC payload; the caller may mutate it freely without affecting this
     *     instance or any other caller
     */
    public byte[] toByteArray() {
        return bytes.clone();
    }
}
