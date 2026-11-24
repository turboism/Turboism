package dev.turboism.update;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic ordering and format validation for installed framework version strings. */
final class UpdateVersionOrdering {

    private static final int MAX_VERSION_LENGTH = 128;
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*))?$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    private UpdateVersionOrdering() {}

    /** Returns whether {@code version} carries a canonical numeric core and optional pre-release tokens. */
    static boolean isValid(final String version) {
        return version != null
                && version.length() <= MAX_VERSION_LENGTH
                && VERSION_PATTERN.matcher(version).matches();
    }

    /**
     * Compares two valid framework versions by SemVer precedence.
     *
     * @param left candidate or current version
     * @param right baseline or previously seen version
     * @return negative when {@code left < right}, zero when equal, positive when {@code left > right}
     */
    static int compare(final String left, final String right) {
        final ParsedVersion first = parse(left);
        final ParsedVersion second = parse(right);
        final int core = first.core().compareTo(second.core());
        if (core != 0) {
            return core;
        }
        if (first.preRelease().isEmpty() && second.preRelease().isEmpty()) {
            return 0;
        }
        if (first.preRelease().isEmpty()) {
            return 1;
        }
        if (second.preRelease().isEmpty()) {
            return -1;
        }
        return comparePreRelease(first.preRelease(), second.preRelease());
    }

    private static int comparePreRelease(final String left, final String right) {
        final String[] leftParts = left.split("\\.");
        final String[] rightParts = right.split("\\.");
        final int shared = Math.min(leftParts.length, rightParts.length);
        for (int index = 0; index < shared; index++) {
            final String leftToken = leftParts[index];
            final String rightToken = rightParts[index];
            final boolean leftNumeric = DIGITS.matcher(leftToken).matches();
            final boolean rightNumeric = DIGITS.matcher(rightToken).matches();
            if (leftNumeric && rightNumeric) {
                final int numeric = new BigInteger(leftToken).compareTo(new BigInteger(rightToken));
                if (numeric != 0) {
                    return numeric;
                }
            } else if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            } else {
                final int lexical = leftToken.compareTo(rightToken);
                if (lexical != 0) {
                    return lexical;
                }
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static ParsedVersion parse(final String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("version exceeds maximum length");
        }
        final Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid framework version: " + value);
        }
        final UpdateVersion core =
                UpdateVersion.parse(matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3));
        final String preRelease = matcher.group(4) == null ? "" : matcher.group(4);
        return new ParsedVersion(core, preRelease);
    }

    private record ParsedVersion(UpdateVersion core, String preRelease) {}
}
