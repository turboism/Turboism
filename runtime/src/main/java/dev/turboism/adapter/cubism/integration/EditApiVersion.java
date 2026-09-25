package dev.turboism.adapter.cubism.integration;

import java.util.Objects;

/**
 * Dotted {@code major.minor.patch} protocol version carried in the official envelope's
 * {@code Version} field.
 *
 * <p>The host's own version table ({@code j$g}) ends at {@code 1.0.x}; anything it cannot parse
 * is {@code UNKNOWN} and answered with {@code UnsupportedVersion}. This type implements the
 * Turboism-side gate instead: editing-API methods require {@code >= 1.1.0} and the comparison
 * never touches host state.</p>
 */
public final class EditApiVersion implements Comparable<EditApiVersion> {

    /** The first protocol version that carries the editing API. */
    public static final EditApiVersion EDIT_API_MINIMUM = new EditApiVersion(1, 1, 0);

    private final int major;
    private final int minor;
    private final int patch;

    private EditApiVersion(final int major, final int minor, final int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * Parses a dotted version of the form {@code major.minor[.patch]}.
     *
     * @param text the raw {@code Version} value; may be null
     * @return the parsed version, or {@code null} when the text is absent, blank, has a
     *         non-numeric component, or carries more than three components
     */
    public static EditApiVersion parse(final String text) {
        if (text == null) return null;
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        final String[] parts = trimmed.split("\\.", -1);
        if (parts.length < 2 || parts.length > 3) return null;
        final int[] values = new int[3];
        for (int i = 0; i < parts.length; i++) {
            final String part = parts[i];
            if (part.isEmpty() || part.length() > 9) return null;
            for (int c = 0; c < part.length(); c++) {
                if (!Character.isDigit(part.charAt(c))) return null;
            }
            values[i] = Integer.parseInt(part);
        }
        return new EditApiVersion(values[0], values[1], values[2]);
    }

    /** {@return whether this version is at or above {@code floor}} */
    public boolean atLeast(final EditApiVersion floor) {
        return compareTo(Objects.requireNonNull(floor, "floor")) >= 0;
    }

    @Override
    public int compareTo(final EditApiVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) return result;
        result = Integer.compare(minor, other.minor);
        if (result != 0) return result;
        return Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof EditApiVersion version)) return false;
        return major == version.major && minor == version.minor && patch == version.patch;
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
