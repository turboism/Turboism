package dev.turboism.compatibility.policy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable release facts generated from reviewed-releases.json; do not edit. */
public final class ReviewedReleaseTable {

    /** Exact observed build, Core tuple and explicit full-runtime decision for one release. */
    public record Release(
            String version, int build, int coreMajor, int coreMinor, int corePatch, boolean fullRuntime) {}

    private static final List<Release> RELEASES = List.of(
            new Release("5.0.00", 500000005, 5, 0, 0, true),
            new Release("5.0.01", 500010000, 5, 0, 0, true),
            new Release("5.0.02", 500020000, 5, 0, 0, true),
            new Release("5.0.03", 500030001, 5, 0, 0, true),
            new Release("5.0.04", 500040000, 5, 0, 0, true),
            new Release("5.0.05", 500050001, 5, 0, 0, true),
            new Release("5.0.06", 500060001, 5, 0, 0, true),
            new Release("5.1.00", 501000004, 5, 0, 0, true),
            new Release("5.1.01", 501010000, 5, 0, 0, true),
            new Release("5.1.02", 501020001, 5, 0, 0, true),
            new Release("5.1.03", 501030000, 5, 0, 0, true),
            new Release("5.2.00", 502000003, 5, 0, 256, true),
            new Release("5.2.01", 502010000, 5, 0, 256, true),
            new Release("5.2.02", 502020000, 5, 0, 256, true),
            new Release("5.2.03", 502030002, 5, 0, 256, true),
            new Release("5.3.00", 503000005, 6, 0, 257, true),
            new Release("5.3.01", 503010000, 6, 0, 257, true),
            new Release("5.3.02", 503020001, 6, 0, 257, true),
            new Release("5.3.03", 503030001, 6, 0, 257, true),
            new Release("5.3.04", 503040001, 6, 0, 513, true));

    /** @return all reviewed releases in table order */
    public static List<Release> all() {
        return RELEASES;
    }

    /** @return all exact reviewed version labels, oldest first */
    public static List<String> versions() {
        return RELEASES.stream().map(Release::version).toList();
    }

    /** @return the exact reviewed release, or empty for an unknown version */
    public static Optional<Release> find(final String version) {
        Objects.requireNonNull(version, "version");
        return RELEASES.stream()
                .filter(release -> release.version().equals(version))
                .findFirst();
    }

    private ReviewedReleaseTable() {}
}
