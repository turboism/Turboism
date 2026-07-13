package dev.turboism.core.version;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** v1 version range: exact version or bounded half-open interval {@code [a,b)}. */
public final class VersionRange {
    private static final String VERSION = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
    private static final Pattern EXACT = Pattern.compile(VERSION);
    private static final Pattern INTERVAL = Pattern.compile("\\[(" + VERSION + "),(" + VERSION + ")\\)");

    private final PluginVersion lower;
    private final PluginVersion upper;
    private final boolean exact;

    private VersionRange(PluginVersion lower, PluginVersion upper, boolean exact) {
        this.lower = lower;
        this.upper = upper;
        this.exact = exact;
    }

    public static VersionRange parse(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("version range must not be empty");
        if (EXACT.matcher(value).matches()) {
            PluginVersion version = PluginVersion.parse(value);
            return new VersionRange(version, version, true);
        }
        Matcher interval = INTERVAL.matcher(value);
        if (!interval.matches()) throw new IllegalArgumentException("unsupported version range: " + value);
        PluginVersion lower = PluginVersion.parse(interval.group(1));
        PluginVersion upper = PluginVersion.parse(interval.group(2));
        if (lower.compareTo(upper) >= 0) throw new IllegalArgumentException("version range inverted or empty");
        return new VersionRange(lower, upper, false);
    }

    public boolean contains(PluginVersion version) {
        Objects.requireNonNull(version);
        if (exact) return lower.equals(version);
        return version.compareTo(lower) >= 0 && version.compareTo(upper) < 0;
    }

    @Override
    public String toString() {
        return exact ? lower.toString() : "[" + lower + "," + upper + ")";
    }
}
