package dev.turboism.sdk;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Reviewed version selections generated from reviewed-releases.json; do not edit. */
public final class ReviewedVersions {

    private static final List<String> VERSIONS = """
            5.0.00
            5.0.01
            5.0.02
            5.0.03
            5.0.04
            5.0.05
            5.0.06
            5.1.00
            5.1.01
            5.1.02
            5.1.03
            5.2.00
            5.2.01
            5.2.02
            5.2.03
            5.3.00
            5.3.01
            5.3.02
            5.3.03
            5.3.04
            """.lines().toList();

    /** @return reviewed releases starting at the exact reviewed boundary */
    public static Set<String> since(final String first) {
        return between(first, VERSIONS.get(VERSIONS.size() - 1));
    }

    /** @return reviewed releases in the named major.minor line */
    public static Set<String> line(final String line) {
        return VERSIONS.stream()
                .filter(version -> version.startsWith(line + "."))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** @return reviewed releases between two exact reviewed boundaries, inclusive */
    public static Set<String> between(final String first, final String last) {
        final int start = VERSIONS.indexOf(first);
        final int end = VERSIONS.indexOf(last);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid reviewed version range: " + first + ".." + last);
        }
        return Set.copyOf(VERSIONS.subList(start, end + 1));
    }

    private ReviewedVersions() {}
}
