package dev.turboism.update;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical numeric MAJOR.MINOR.PATCH version used by update comparison. */
public record UpdateVersion(int major, int minor, int patch) implements Comparable<UpdateVersion> {
    private static final Pattern CANONICAL = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");

    public UpdateVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must not be negative");
        }
    }

    /** Parses a canonical numeric MAJOR.MINOR.PATCH version. */
    public static UpdateVersion parse(final String value) {
        Objects.requireNonNull(value, "value");
        final Matcher matcher = CANONICAL.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("version must be canonical MAJOR.MINOR.PATCH");
        }
        return new UpdateVersion(component(matcher.group(1)), component(matcher.group(2)), component(matcher.group(3)));
    }

    /** Returns whether {@code value} is canonical and fits the numeric bounds. */
    public static boolean isCanonical(final String value) {
        return value != null && CANONICAL.matcher(value).matches() && componentsFitInt(value);
    }

    /**
     * Parses the leading canonical numeric core of an installed product version,
     * ignoring a release suffix such as {@code -0.nightly.3} or {@code -SNAPSHOT}.
     * Nightly installations must still be able to compare against stable releases.
     */
    public static UpdateVersion parseInstalled(final String value) {
        Objects.requireNonNull(value, "value");
        final Matcher matcher = Pattern.compile(CANONICAL.pattern() + "(?=$|[-+])").matcher(value);
        if (!matcher.find()) {
            throw new IllegalArgumentException("installed version has no canonical numeric core");
        }
        return new UpdateVersion(component(matcher.group(1)), component(matcher.group(2)), component(matcher.group(3)));
    }

    private static int component(final String value) {
        try {
            return Math.toIntExact(Long.parseLong(value));
        } catch (NumberFormatException | ArithmeticException overflow) {
            throw new IllegalArgumentException("version component is too large", overflow);
        }
    }

    private static boolean componentsFitInt(final String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    @Override
    public int compareTo(final UpdateVersion other) {
        Objects.requireNonNull(other, "other");
        int result = Integer.compare(major, other.major);
        if (result != 0) return result;
        result = Integer.compare(minor, other.minor);
        if (result != 0) return result;
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
