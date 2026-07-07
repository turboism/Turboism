package dev.turboism.core.version;

import java.util.Objects;

/**
 * Immutable SemVer-like version with three numeric components.
 */
public final class PluginVersion implements Comparable<PluginVersion> {

    private final int major;
    private final int minor;
    private final int patch;

    private PluginVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public static PluginVersion parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("version must not be empty");
        }
        String[] parts = value.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("version must be MAJOR.MINOR.PATCH: " + value);
        }
        try {
            return new PluginVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("version components must be integers: " + value, e);
        }
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    @Override
    public int compareTo(PluginVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) return c;
        c = Integer.compare(minor, other.minor);
        if (c != 0) return c;
        return Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginVersion that)) return false;
        return major == that.major && minor == that.minor && patch == that.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
