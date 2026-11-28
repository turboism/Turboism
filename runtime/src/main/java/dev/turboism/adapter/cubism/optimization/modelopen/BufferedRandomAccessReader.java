package dev.turboism.adapter.cubism.optimization.modelopen;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Objects;

/**
 * Buffers sequential archive reads from {@link RandomAccessFile} while preserving logical offsets,
 * XOR obfuscation semantics, and direct underlying handle coherence.
 */
public final class BufferedRandomAccessReader {

    private static final int DEFAULT_BUFFER_BYTES = 64 * 1024;
    private static final int BYTE_MASK = 0xFF;
    private static final int BYTE_BYTES = 1;
    private static final int SHORT_BYTES = 2;
    private static final int INT_BYTES = 4;
    private static final int LONG_BYTES = 8;
    private static final int SHIFT_8 = 8;
    private static final int SHIFT_16 = 16;
    private static final int SHIFT_24 = 24;
    private static final int SHIFT_32 = 32;
    private static final int SHIFT_40 = 40;
    private static final int SHIFT_48 = 48;
    private static final int SHIFT_56 = 56;

    private final RandomAccessFile raf;
    private final byte[] buffer;
    private long bufferStartOffset;
    private int bufferPos;
    private int bufferLimit;
    private boolean rafOffsetDesynced;
    private boolean closed;

    /**
     * Wraps {@code raf} with a bounded read buffer of default capacity.
     *
     * @param raf underlying host {@link RandomAccessFile}
     */
    public BufferedRandomAccessReader(final RandomAccessFile raf) {
        this(raf, DEFAULT_BUFFER_BYTES);
    }

    BufferedRandomAccessReader(final RandomAccessFile raf, final int bufferSize) {
        this.raf = Objects.requireNonNull(raf, "raf");
        if (bufferSize < LONG_BYTES) {
            throw new IllegalArgumentException("bufferSize must be at least " + LONG_BYTES);
        }
        this.buffer = new byte[bufferSize];
    }

    /**
     * Synchronizes the underlying {@link RandomAccessFile} to the logical offset, invalidates the
     * read buffer, and returns the raw handle.
     *
     * @return synchronized underlying file handle
     * @throws IOException if seeking fails
     */
    public RandomAccessFile syncAndGetFile() throws IOException {
        if (!this.rafOffsetDesynced) {
            final long logicalOffset = this.bufferStartOffset + this.bufferPos;
            if (this.bufferPos != this.bufferLimit) {
                this.raf.seek(logicalOffset);
            }
            this.bufferStartOffset = logicalOffset;
            this.bufferPos = 0;
            this.bufferLimit = 0;
            this.rafOffsetDesynced = true;
        }
        return this.raf;
    }

    /**
     * Invalidates buffered state and closes the underlying {@link RandomAccessFile}.
     *
     * @throws IOException if closing fails
     */
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.bufferPos = 0;
        this.bufferLimit = 0;
        this.rafOffsetDesynced = false;
        this.raf.close();
    }

    /**
     * Positions the logical read cursor at {@code pos}, reusing buffered data when in range.
     *
     * @param pos target byte offset
     * @throws IOException if seeking fails
     */
    public void seek(final long pos) throws IOException {
        if (!this.rafOffsetDesynced
                && pos >= this.bufferStartOffset
                && pos <= this.bufferStartOffset + this.bufferLimit) {
            this.bufferPos = (int) (pos - this.bufferStartOffset);
            return;
        }
        this.raf.seek(pos);
        this.bufferStartOffset = pos;
        this.bufferPos = 0;
        this.bufferLimit = 0;
        this.rafOffsetDesynced = false;
    }

    /**
     * Returns the current logical read offset matching {@link RandomAccessFile#getFilePointer()}.
     *
     * @return logical file offset in bytes
     * @throws IOException if querying the underlying handle fails
     */
    public long getFilePointer() throws IOException {
        if (this.rafOffsetDesynced) {
            return this.raf.getFilePointer();
        }
        return this.bufferStartOffset + this.bufferPos;
    }

    /**
     * Advances the read cursor by {@code count} bytes, throwing {@link EOFException} past EOF.
     *
     * @param count number of bytes to skip; non-positive values do nothing
     * @throws IOException if reading or seeking fails, or EOF is reached early
     */
    public void skip(final int count) throws IOException {
        if (count <= 0) {
            return;
        }
        final int buffered = this.bufferLimit - this.bufferPos;
        if (count <= buffered) {
            this.bufferPos += count;
            return;
        }
        syncExternalOffsetIfNeeded();
        int remaining = count - buffered;
        final long nextOffset = this.bufferStartOffset + this.bufferLimit;
        if (remaining > this.buffer.length) {
            final long fileLength = this.raf.length();
            final long targetOffset = nextOffset + remaining;
            if (targetOffset > fileLength) {
                seek(fileLength);
                throw new EOFException();
            }
            seek(targetOffset);
            return;
        }
        this.bufferStartOffset = nextOffset;
        this.bufferPos = 0;
        this.bufferLimit = 0;
        while (remaining > 0) {
            if (!refillEmptyBuffer()) {
                throw new EOFException();
            }
            final int step = Math.min(remaining, this.bufferLimit);
            this.bufferPos = step;
            remaining -= step;
        }
    }

    /**
     * Reads a single byte and XORs it with {@code key}.
     *
     * @param key archive obfuscation key
     * @return de-obfuscated byte
     * @throws IOException if reading fails or EOF is reached
     */
    public byte readByte(final int key) throws IOException {
        ensureAvailable(BYTE_BYTES);
        return (byte) (this.buffer[this.bufferPos++] ^ key);
    }

    /**
     * Reads a big-endian 16-bit signed integer and XORs it with {@code key}.
     *
     * @param key archive obfuscation key
     * @return de-obfuscated short
     * @throws IOException if reading fails or fewer than 2 bytes remain
     */
    public short readShort(final int key) throws IOException {
        ensureAvailable(SHORT_BYTES);
        final int p = this.bufferPos;
        final int raw = ((this.buffer[p] & BYTE_MASK) << SHIFT_8) | (this.buffer[p + 1] & BYTE_MASK);
        this.bufferPos = p + SHORT_BYTES;
        return (short) (raw ^ key);
    }

    /**
     * Reads a big-endian 32-bit signed integer and XORs it with {@code key}.
     *
     * @param key archive obfuscation key
     * @return de-obfuscated int
     * @throws IOException if reading fails or fewer than 4 bytes remain
     */
    public int readInt(final int key) throws IOException {
        ensureAvailable(INT_BYTES);
        final int p = this.bufferPos;
        final int raw = ((this.buffer[p] & BYTE_MASK) << SHIFT_24)
                | ((this.buffer[p + 1] & BYTE_MASK) << SHIFT_16)
                | ((this.buffer[p + 2] & BYTE_MASK) << SHIFT_8)
                | (this.buffer[p + 3] & BYTE_MASK);
        this.bufferPos = p + INT_BYTES;
        return raw ^ key;
    }

    /**
     * Reads a big-endian 64-bit signed integer and XORs it with the sign-extended Cubism key mask.
     *
     * @param key archive obfuscation key
     * @return de-obfuscated long
     * @throws IOException if reading fails or fewer than 8 bytes remain
     */
    public long readLong(final int key) throws IOException {
        ensureAvailable(LONG_BYTES);
        final int p = this.bufferPos;
        final long raw = (((long) (this.buffer[p] & BYTE_MASK)) << SHIFT_56)
                | (((long) (this.buffer[p + 1] & BYTE_MASK)) << SHIFT_48)
                | (((long) (this.buffer[p + 2] & BYTE_MASK)) << SHIFT_40)
                | (((long) (this.buffer[p + 3] & BYTE_MASK)) << SHIFT_32)
                | (((long) (this.buffer[p + 4] & BYTE_MASK)) << SHIFT_24)
                | (((long) (this.buffer[p + 5] & BYTE_MASK)) << SHIFT_16)
                | (((long) (this.buffer[p + 6] & BYTE_MASK)) << SHIFT_8)
                | ((long) (this.buffer[p + 7] & BYTE_MASK));
        this.bufferPos = p + LONG_BYTES;
        // Mirror Cubism's (i2l << 32) | i2l sign-extended mask calculation verbatim.
        final long mask = (((long) key) << SHIFT_32) | ((long) key);
        return raw ^ mask;
    }

    /**
     * Reads up to {@code len} bytes into {@code reusable} or a newly allocated array, applying
     * {@code key} XOR obfuscation when non-zero.
     *
     * @param len number of bytes to read
     * @param reusable optional destination array reused when its length is at least {@code len}
     * @param key archive obfuscation key
     * @return array containing the read bytes
     * @throws IOException if reading fails
     */
    public byte[] readBytes(final int len, final byte[] reusable, final int key) throws IOException {
        final byte[] out = (reusable == null || reusable.length < len) ? new byte[len] : reusable;
        if (len < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return out;
        }
        syncExternalOffsetIfNeeded();
        int dstOffset = 0;
        int remaining = len;
        final int buffered = this.bufferLimit - this.bufferPos;
        if (buffered > 0) {
            final int toCopy = Math.min(buffered, remaining);
            System.arraycopy(this.buffer, this.bufferPos, out, 0, toCopy);
            this.bufferPos += toCopy;
            dstOffset += toCopy;
            remaining -= toCopy;
        }
        if (remaining > 0) {
            this.bufferStartOffset += this.bufferLimit;
            this.bufferPos = 0;
            this.bufferLimit = 0;
            if (remaining >= this.buffer.length) {
                while (remaining > 0) {
                    final int read = this.raf.read(out, dstOffset, remaining);
                    if (read < 0) {
                        break;
                    }
                    dstOffset += read;
                    remaining -= read;
                    this.bufferStartOffset += read;
                }
            } else {
                while (remaining > 0) {
                    if (!refillEmptyBuffer()) {
                        break;
                    }
                    final int toCopy = Math.min(this.bufferLimit, remaining);
                    System.arraycopy(this.buffer, 0, out, dstOffset, toCopy);
                    this.bufferPos = toCopy;
                    dstOffset += toCopy;
                    remaining -= toCopy;
                }
            }
        }
        if (key != 0) {
            for (int i = 0; i < len; i++) {
                out[i] = (byte) (out[i] ^ key);
            }
        }
        return out;
    }

    private void syncExternalOffsetIfNeeded() throws IOException {
        if (this.rafOffsetDesynced) {
            this.bufferStartOffset = this.raf.getFilePointer();
            this.bufferPos = 0;
            this.bufferLimit = 0;
            this.rafOffsetDesynced = false;
        }
    }

    private void ensureAvailable(final int required) throws IOException {
        if (this.bufferLimit - this.bufferPos >= required) {
            return;
        }
        syncExternalOffsetIfNeeded();
        final int remaining = this.bufferLimit - this.bufferPos;
        if (remaining > 0) {
            System.arraycopy(this.buffer, this.bufferPos, this.buffer, 0, remaining);
            this.bufferStartOffset += this.bufferPos;
        } else {
            this.bufferStartOffset += this.bufferLimit;
        }
        this.bufferPos = 0;
        this.bufferLimit = remaining;
        while (this.bufferLimit < required) {
            final int read = this.raf.read(this.buffer, this.bufferLimit, this.buffer.length - this.bufferLimit);
            if (read < 0) {
                this.bufferPos = this.bufferLimit;
                throw new EOFException();
            }
            this.bufferLimit += read;
        }
    }

    private boolean refillEmptyBuffer() throws IOException {
        this.bufferStartOffset += this.bufferLimit;
        this.bufferPos = 0;
        this.bufferLimit = 0;
        final int read = this.raf.read(this.buffer, 0, this.buffer.length);
        if (read <= 0) {
            return false;
        }
        this.bufferLimit = read;
        return true;
    }
}
