package dev.turboism.plugin.uitheme.b1.domain;

import java.util.Arrays;
import java.util.Objects;

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

    public String name() {
        return name;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    int size() {
        return bytes.length;
    }
}
