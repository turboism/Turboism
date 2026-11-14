package dev.turboism.mapping.verification;

import dev.turboism.core.io.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Version-independent size and SHA-256 digest of a local host artifact. */
public record HostArtifactDigest(long size, String sha256) {

    public HostArtifactDigest {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        sha256 = requireSha256(sha256);
    }

    /**
     * Measures a file on disk, streaming it so artifact size does not bound
     * memory.
     *
     * @param artifact file to digest
     * @return its byte length and lowercase hex SHA-256
     * @throws IOException if the file cannot be read
     * @throws IllegalStateException if SHA-256 is unavailable in this JVM
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static HostArtifactDigest from(final Path artifact) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        final MessageDigest digest = Sha256.digest();
        try (InputStream input = Files.newInputStream(artifact)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return new HostArtifactDigest(Files.size(artifact), HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Returns the digest of a host artifact from the shared cache.
     *
     * <p>Use this method for identity lookups that repeat during one launch. Use {@link #from}
     * when a caller must measure the bytes again, for example a before-and-after comparison.
     *
     * @param artifact file to digest
     * @return its byte length and lowercase hex SHA-256
     * @throws IOException if the file cannot be read, or if it changes during the measurement
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static HostArtifactDigest cached(final Path artifact) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        return HostArtifactDigestCache.shared().digest(artifact);
    }

    static MessageDigest sha256Digest() {
        return Sha256.digest();
    }

    private static String requireSha256(final String value) {
        Objects.requireNonNull(value, "sha256");
        final String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }
}
