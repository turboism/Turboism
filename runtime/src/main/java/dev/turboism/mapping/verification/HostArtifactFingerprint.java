package dev.turboism.mapping.verification;

import dev.turboism.internal.Requirements;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Exact-version, hash-anchored identity for a local host artifact. */
public record HostArtifactFingerprint(String cubismVersion, long size, String sha256) {

    public HostArtifactFingerprint {
        cubismVersion = Requirements.text(cubismVersion, "cubismVersion");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        sha256 = requireSha256(sha256);
    }

    /**
     * Measures a file and pairs the measurement with a caller-asserted version.
     * The version is not derived from the file and is not validated against it.
     *
     * @param cubismVersion version the caller attributes to this artifact
     * @param artifact file to digest
     * @return a fingerprint combining that version with the measured size and
     *     hash
     * @throws IOException if the file cannot be read
     */
    public static HostArtifactFingerprint from(final String cubismVersion, final Path artifact) throws IOException {
        final HostArtifactDigest digest = HostArtifactDigest.cached(artifact);
        return new HostArtifactFingerprint(cubismVersion, digest.size(), digest.sha256());
    }

    /**
     * @param expected fingerprint to compare against
     * @return whether version, size, and hash all agree; all three must match,
     *     so a same-bytes artifact claimed under a different version does not
     *     count as a match
     * @throws NullPointerException if {@code expected} is {@code null}
     */
    public boolean matches(final HostArtifactFingerprint expected) {
        Objects.requireNonNull(expected, "expected");
        return cubismVersion.equals(expected.cubismVersion) && size == expected.size && sha256.equals(expected.sha256);
    }

    private static String requireSha256(final String value) {
        final String normalized = Requirements.text(value, "sha256").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }
}
