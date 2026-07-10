package dev.turboism.mapping.verification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    public static HostArtifactDigest from(final Path artifact) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        final MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(artifact)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return new HostArtifactDigest(
            Files.size(artifact),
            HexFormat.of().formatHex(digest.digest())
        );
    }

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
