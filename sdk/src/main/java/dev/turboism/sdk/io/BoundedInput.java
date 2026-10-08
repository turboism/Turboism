package dev.turboism.sdk.io;

import dev.turboism.sdk.Incubating;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fail-closed bounded byte reads for untrusted streams.
 *
 * <p>{@link #readNBytes(InputStream, long)} materializes the remainder of a stream while never
 * holding more than {@code maximumBytes + 1} bytes, and reports overflow as an exception rather
 * than a flag a caller can forget to check. The {@link #readNBytes(InputStream, long, Supplier)}
 * variant lets a domain reader surface overflow under its own failure code.</p>
 */
@Incubating
public final class BoundedInput {

    private BoundedInput() {}

    /**
     * Reads the remainder of the stream into one array, bounded by {@code maximumBytes}.
     *
     * @param input the stream to drain; it is not closed by this method
     * @param maximumBytes maximum accepted content size in bytes, in
     *     {@code [0, Integer.MAX_VALUE)}
     * @return the stream bytes, at most {@code maximumBytes} long
     * @throws InputSizeLimitException when the stream supplies more than {@code maximumBytes}
     * @throws IOException on a read failure
     * @throws IllegalArgumentException when {@code maximumBytes} is out of range
     */
    public static byte[] readNBytes(final InputStream input, final long maximumBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximumBytes < 0 || maximumBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumBytes must be in [0, Integer.MAX_VALUE)");
        }
        final byte[] bytes = input.readNBytes((int) maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new InputSizeLimitException(maximumBytes);
        }
        return bytes;
    }

    /**
     * Bounded read whose overflow is reported as a caller-chosen exception, so a domain reader
     * keeps its own failure code instead of losing it to a generic limit error.
     *
     * @param input the stream to drain; it is not closed by this method
     * @param maximumBytes maximum accepted content size in bytes
     * @param oversized produces the exception thrown when the stream exceeds the bound
     * @param <X> the caller's failure type
     * @return the stream bytes, at most {@code maximumBytes} long
     * @throws X the supplied exception when the stream supplies more than {@code maximumBytes}
     * @throws IOException on a read failure
     */
    public static <X extends Exception> byte[] readNBytes(
            final InputStream input, final long maximumBytes, final Supplier<? extends X> oversized)
            throws IOException, X {
        Objects.requireNonNull(oversized, "oversized");
        try {
            return readNBytes(input, maximumBytes);
        } catch (InputSizeLimitException exceeded) {
            throw oversized.get();
        }
    }

    /** A stream supplied more bytes than the caller's configured ceiling. */
    public static final class InputSizeLimitException extends IOException {
        public InputSizeLimitException(final long maximumBytes) {
            super("Input exceeded the maximum length of " + maximumBytes + " bytes");
        }
    }
}
