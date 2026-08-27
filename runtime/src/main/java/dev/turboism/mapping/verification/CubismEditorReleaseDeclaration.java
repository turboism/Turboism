package dev.turboism.mapping.verification;

import java.util.Objects;

/**
 * Host-declared Cubism Editor release identity, read from the Editor JAR without initializing or
 * exposing any Cubism class.
 *
 * <p>The declaration mirrors the exact class-file constants of {@code com/live2d/cubism/h}
 * (product, version, date and build integer) and is deliberately independent from
 * {@link HostArtifactDigest}. The semantic release and exact artifact identity must agree before
 * admission, but neither value is derived from the other.</p>
 *
 * @param product declared Editor product string
 * @param version exact declared Editor version
 * @param date declared release date
 * @param build declared release build integer
 */
public record CubismEditorReleaseDeclaration(
    String product,
    String version,
    String date,
    int build
) {

    /** Validates that the release declaration is complete and has a positive build. */
    public CubismEditorReleaseDeclaration {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(date, "date");
        if (build <= 0) {
            throw new IllegalArgumentException("build must be positive");
        }
    }
}
