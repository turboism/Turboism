package dev.turboism.mapping.verification;

import java.util.Objects;
import java.util.Optional;

/**
 * Semantic identity a Cubism host artifact declares about itself.
 *
 * <p>This is the single place where the product name, release version, release
 * date and build number the host baked into its own bytecode surface as facts.
 * The identity is <em>declared</em>, never derived: no component here is
 * computed from a whole-artifact digest, so an unreviewed build keeps its true
 * version and build instead of being pinned to a reviewed label.</p>
 *
 * @param product the declared product string, such as {@code "Live2D Cubism Editor"}
 * @param version the declared semantic version, such as {@code "5.3.02"}
 * @param date the declared release date ({@code yyyy/MM/dd}), empty when the host
 *     declaration omits one
 * @param build the declared build number, such as {@code 503020001}
 * @param declarationClass JVM internal name of the class the declaration was
 *     read from, such as {@code "com/live2d/cubism/h"}
 * @param artifact size/SHA-256 digest of the artifact that declared this identity
 */
public record CubismHostIdentity(
    String product,
    String version,
    Optional<String> date,
    int build,
    String declarationClass,
    HostArtifactDigest artifact
) {

    public CubismHostIdentity {
        product = requireText(product, "product");
        version = requireText(version, "version");
        date = Objects.requireNonNull(date, "date");
        if (build <= 0) {
            throw new IllegalArgumentException("build must be positive");
        }
        declarationClass = requireText(declarationClass, "declarationClass");
        artifact = Objects.requireNonNull(artifact, "artifact");
    }

    /**
     * Whether the declared product is the Cubism Editor family.
     *
     * @return {@code true} when the product string carries the Cubism Editor
     *     marker; other Live2D products do not qualify
     */
    public boolean isCubismEditor() {
        return product.contains(CubismEditorReleaseDetector.PRODUCT_MARKER);
    }

    /**
     * The major component of the declared version, or {@code -1} when the
     * version does not start with digits.
     */
    public int majorVersion() {
        final int dot = version.indexOf('.');
        final String major = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    /**
     * The {@code major.minor} prefix of the declared version, or the whole
     * version when it has no minor component.
     */
    public String majorMinorVersion() {
        final int first = version.indexOf('.');
        if (first < 0) return version;
        final int second = version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    /**
     * A short stable label for logs and diagnostics that never pretends the
     * identity is reviewed: {@code version+build} carry the declared values.
     *
     * @return a label such as {@code "5.3.02+503020001"}
     */
    public String label() {
        return version + "+" + build;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
