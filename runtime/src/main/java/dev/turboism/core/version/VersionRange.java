package dev.turboism.core.version;

import java.util.Objects;

/**
 * v1 version range. Supports exact version or half-open interval [a,b).
 */
public final class VersionRange {

    private final PluginVersion lower;
    private final boolean lowerInclusive;
    private final PluginVersion upper;
    private final boolean upperInclusive;

    private VersionRange(PluginVersion lower, boolean lowerInclusive, PluginVersion upper, boolean upperInclusive) {
        this.lower = lower;
        this.lowerInclusive = lowerInclusive;
        this.upper = upper;
        this.upperInclusive = upperInclusive;
    }

    public static VersionRange parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("version range must not be empty");
        }
        value = value.trim();
        if (value.startsWith("[") || value.startsWith("(")) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("interval range must contain a comma: " + value);
            }
            String left = value.substring(1, comma).trim();
            String right = value.substring(comma + 1, value.length() - 1).trim();
            boolean lowerInc = value.startsWith("[");
            boolean upperInc = value.endsWith("]");
            PluginVersion lower = PluginVersion.parse(left);
            PluginVersion upper = PluginVersion.parse(right);
            return new VersionRange(lower, lowerInc, upper, upperInc);
        }
        PluginVersion exact = PluginVersion.parse(value);
        return new VersionRange(exact, true, exact, true);
    }

    public boolean contains(PluginVersion version) {
        Objects.requireNonNull(version);
        int lowerCmp = version.compareTo(lower);
        if (lowerCmp < 0) return false;
        if (lowerCmp == 0 && !lowerInclusive) return false;
        if (upper == null) return true;
        int upperCmp = version.compareTo(upper);
        if (upperCmp > 0) return false;
        return upperCmp != 0 || upperInclusive;
    }

    @Override
    public String toString() {
        if (lower.equals(upper) && lowerInclusive && upperInclusive) {
            return lower.toString();
        }
        return (lowerInclusive ? "[" : "(") + lower + "," + upper + (upperInclusive ? "]" : ")");
    }
}
