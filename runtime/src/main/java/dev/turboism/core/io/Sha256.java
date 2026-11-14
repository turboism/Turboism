package dev.turboism.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes SHA-256 digests and their lowercase hexadecimal text form. */
public final class Sha256 {

    private static final String ALGORITHM = "SHA-256";
    private static final String UNAVAILABLE_MESSAGE = "SHA-256 is unavailable";
    private static final int BUFFER_BYTES = 8192;

    /**
     * Returns a fresh SHA-256 digest for callers that feed bytes incrementally.
     *
     * @return a new digest instance
     * @throws IllegalStateException when the JDK does not provide SHA-256
     */
    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(UNAVAILABLE_MESSAGE, unavailable);
        }
    }

    /**
     * Returns the lowercase hexadecimal SHA-256 digest of {@code bytes}.
     *
     * @param bytes the bytes to digest
     * @return the digest as lowercase hexadecimal text
     */
    public static String hex(final byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /**
     * Returns the lowercase hexadecimal SHA-256 digest of a file.
     *
     * @param file the file to digest
     * @return the digest as lowercase hexadecimal text
     * @throws IOException when the file cannot be read
     */
    public static String hexOf(final Path file) throws IOException {
        final MessageDigest digest = digest();
        final byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Sha256() {}
}
