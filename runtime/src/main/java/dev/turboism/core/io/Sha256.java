package dev.turboism.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

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
     * Returns the raw SHA-256 digest bytes of {@code bytes}.
     *
     * @param bytes the bytes to digest
     * @return the 32-byte digest
     */
    public static byte[] bytes(final byte[] bytes) {
        return digest().digest(Objects.requireNonNull(bytes, "bytes"));
    }

    /**
     * Returns the lowercase hexadecimal SHA-256 digest of {@code bytes}.
     *
     * @param bytes the bytes to digest
     * @return the digest as lowercase hexadecimal text
     */
    public static String hex(final byte[] bytes) {
        return HexFormat.of().formatHex(bytes(bytes));
    }

    /**
     * Returns the lowercase hexadecimal SHA-256 digest of UTF-8 text.
     *
     * @param text the text to digest
     * @return the digest as lowercase hexadecimal text
     */
    public static String hex(final String text) {
        return hex(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Finalizes {@code digest} and formats the result as lowercase hexadecimal text.
     *
     * @param digest the populated digest instance
     * @return the digest as lowercase hexadecimal text
     */
    public static String hex(final MessageDigest digest) {
        return HexFormat.of().formatHex(Objects.requireNonNull(digest, "digest").digest());
    }

    /**
     * Returns the lowercase hexadecimal SHA-256 digest of a file.
     *
     * @param file the file to digest
     * @param options open options such as {@link LinkOption#NOFOLLOW_LINKS}
     * @return the digest as lowercase hexadecimal text
     * @throws IOException when the file cannot be read
     */
    public static String hex(final Path file, final LinkOption... options) throws IOException {
        Objects.requireNonNull(file, "file");
        final MessageDigest digest = digest();
        final byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(file, options)) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest);
    }

    /**
     * Returns the lowercase hexadecimal SHA-256 digest of a file.
     *
     * @param file the file to digest
     * @return the digest as lowercase hexadecimal text
     * @throws IOException when the file cannot be read
     */
    public static String hexOf(final Path file) throws IOException {
        return hex(file);
    }

    /**
     * Wraps {@code delegate} so every written byte also updates {@code digest}.
     *
     * @param delegate the downstream output stream
     * @param digest the digest to update on writes
     * @return a tee stream that closes {@code delegate} on close
     */
    public static OutputStream digestingOutput(final OutputStream delegate, final MessageDigest digest) {
        return new DigestingOutputStream(
                Objects.requireNonNull(delegate, "delegate"), Objects.requireNonNull(digest, "digest"));
    }

    private static final class DigestingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final MessageDigest digest;

        private DigestingOutputStream(final OutputStream delegate, final MessageDigest digest) {
            this.delegate = delegate;
            this.digest = digest;
        }

        @Override
        public void write(final int value) throws IOException {
            delegate.write(value);
            digest.update((byte) value);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            delegate.write(bytes, offset, length);
            digest.update(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private Sha256() {}
}
