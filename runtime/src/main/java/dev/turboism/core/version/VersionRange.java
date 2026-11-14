package dev.turboism.core.version;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1 version range: an exact version, a bounded half-open interval {@code [a,b)}, or an
 * unbounded half-open interval {@code [a,)} that admits every version at or above {@code a}.
 *
 * <p>The unbounded form exists for plugin descriptors that must keep working on every future
 * framework release: {@code turboismApi} names the oldest API a plugin links and imposes no
 * artificial upper bound, so a framework upgrade never rejects an already compatible plugin.</p>
 */
public final class VersionRange {
    private static final String VERSION = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
    private static final Pattern EXACT = Pattern.compile(VERSION);
    private static final Pattern INTERVAL = Pattern.compile("\\[(" + VERSION + "),(" + VERSION + ")?\\)");
    private static final String UNBOUNDED_SUFFIX = ",)";

    private final PluginVersion lower;
    private final PluginVersion upper;
    private final boolean exact;

    private VersionRange(PluginVersion lower, PluginVersion upper, boolean exact) {
        this.lower = lower;
        this.upper = upper;
        this.exact = exact;
    }

    /**
     * @param value an exact {@code MAJOR.MINOR.PATCH} version, a bounded half-open interval
     *     written {@code [lower,upper)}, or an unbounded half-open interval written
     *     {@code [lower,)}; components must not carry leading zeros
     * @return the parsed range
     * @throws IllegalArgumentException if the value is null or empty, matches none of the three
     *     forms, or names an interval whose lower bound is not strictly below its upper bound
     */
    public static VersionRange parse(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("version range must not be empty");
        if (EXACT.matcher(value).matches()) {
            PluginVersion version = PluginVersion.parse(value);
            return new VersionRange(version, version, true);
        }
        Matcher interval = INTERVAL.matcher(value);
        if (!interval.matches()) throw new IllegalArgumentException("unsupported version range: " + value);
        PluginVersion lower = PluginVersion.parse(interval.group(1));
        String upperText = interval.group(2);
        if (upperText == null) return new VersionRange(lower, null, false);
        PluginVersion upper = PluginVersion.parse(upperText);
        if (lower.compareTo(upper) >= 0) throw new IllegalArgumentException("version range inverted or empty");
        return new VersionRange(lower, upper, false);
    }

    /**
     * @param value candidate range text, possibly {@code null}
     * @return whether {@link #parse(String)} accepts the value, so callers can validate
     *     descriptor text without catching exceptions
     */
    public static boolean isValid(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    /**
     * @param version the version to test
     * @return whether the version is admitted: equality for an exact range, otherwise
     *     {@code lower <= version < upper} with the upper bound excluded; an unbounded range
     *     admits every version at or above its lower bound
     * @throws NullPointerException if {@code version} is {@code null}
     */
    public boolean contains(PluginVersion version) {
        Objects.requireNonNull(version);
        if (exact) return lower.equals(version);
        if (version.compareTo(lower) < 0) return false;
        return upper == null || version.compareTo(upper) < 0;
    }

    @Override
    public String toString() {
        if (exact) return lower.toString();
        return upper == null ? "[" + lower + UNBOUNDED_SUFFIX : "[" + lower + "," + upper + ")";
    }
}
