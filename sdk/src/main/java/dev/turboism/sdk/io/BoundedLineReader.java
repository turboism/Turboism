package dev.turboism.sdk.io;

import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

/**
 * Reads newline-delimited character streams without allocating past a caller-supplied line limit.
 *
 * <p>{@link #readLine()} accepts a line of at most {@code maximumChars} characters and reports an
 * overrun as soon as the {@code maximumChars + 1} character is observed. {@link #readLineTruncated()}
 * is for non-protocol text: it drains the complete line while retaining at most the limit.</p>
 */
public final class BoundedLineReader implements AutoCloseable {

    private final Reader source;
    private final int maximumChars;
    private int pushedBack = -1;

    public BoundedLineReader(final Reader source, final int maximumChars) {
        this.source = Objects.requireNonNull(source, "source");
        if (maximumChars < 0) {
            throw new IllegalArgumentException("maximumChars must not be negative");
        }
        this.maximumChars = maximumChars;
    }

    /**
     * Returns the next complete line without its line terminator, or {@code null} at EOF.
     *
     * @throws LineTooLongException when a line exceeds the configured maximum. The reader remains
     *     positioned immediately after the first character beyond the allowed materialized prefix;
     *     call {@link #discardLine()} before reading the next line.
     */
    public String readLine() throws IOException {
        final StringBuilder line = new StringBuilder(Math.min(maximumChars, 256));
        int character;
        while ((character = readCharacter()) != -1) {
            if (isLineTerminator(character)) {
                consumeFollowingLineFeed(character);
                return line.toString();
            }
            if (line.length() == maximumChars) {
                throw new LineTooLongException(maximumChars);
            }
            line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }

    /**
     * Returns the next line while retaining no more than the configured maximum, or {@code null}
     * at EOF. The returned flag records whether the complete line was larger.
     */
    public Line readLineTruncated() throws IOException {
        final StringBuilder line = new StringBuilder(Math.min(maximumChars, 256));
        boolean truncated = false;
        int character;
        while ((character = readCharacter()) != -1) {
            if (isLineTerminator(character)) {
                consumeFollowingLineFeed(character);
                return new Line(line.toString(), truncated);
            }
            if (line.length() < maximumChars) {
                line.append((char) character);
            } else {
                truncated = true;
            }
        }
        return line.isEmpty() && !truncated ? null : new Line(line.toString(), truncated);
    }

    /** Drains the remainder of the current line without materializing it. */
    public void discardLine() throws IOException {
        int character;
        while ((character = readCharacter()) != -1) {
            if (isLineTerminator(character)) {
                consumeFollowingLineFeed(character);
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    private int readCharacter() throws IOException {
        if (pushedBack != -1) {
            final int character = pushedBack;
            pushedBack = -1;
            return character;
        }
        return source.read();
    }

    private void consumeFollowingLineFeed(final int terminator) throws IOException {
        if (terminator != '\r') {
            return;
        }
        final int next = readCharacter();
        if (next != -1 && next != '\n') {
            pushedBack = next;
        }
    }

    private static boolean isLineTerminator(final int character) {
        return character == '\n' || character == '\r';
    }

    /** A materialized protocol line exceeded its configured maximum length. */
    public static final class LineTooLongException extends IOException {
        public LineTooLongException(final int maximumChars) {
            super("Line exceeded the maximum length of " + maximumChars + " characters");
        }
    }

    /** A bounded representation of a drained line. */
    public record Line(String text, boolean truncated) {
        public Line {
            text = Objects.requireNonNull(text, "text");
        }
    }
}
