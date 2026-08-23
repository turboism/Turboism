package dev.turboism.plugin.uitheme.b1.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * One named file inside a theme package.
 *
 * <p>Effectively immutable: the byte content is copied on the way in and on the way out, so
 * neither the caller's array nor a caller of {@link #bytes()} can alter what the entry holds.
 */
public final class ThemePackageEntry {

    private final String name;
    private final byte[] bytes;

    public ThemePackageEntry(final String name, final byte[] bytes) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    /**
     * @return the entry's path within the package, never blank
     */
    public String name() {
        return name;
    }

    /**
     * @return a fresh copy of the entry's file content; mutating it does not affect this entry
     */
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    int size() {
        return bytes.length;
    }
}
