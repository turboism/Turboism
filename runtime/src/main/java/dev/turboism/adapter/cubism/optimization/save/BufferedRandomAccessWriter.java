package dev.turboism.adapter.cubism.optimization.save;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Objects;

/**
 * Buffers sequential archive writes to {@link RandomAccessFile} while tracking logical offsets
 * and keeping caller payload arrays immutable during obfuscation.
 */
public final class BufferedRandomAccessWriter {

    private static final int WRITE_BUFFER_BYTES = 256 * 1024;
    private static final int BYTE_MASK = 0xFF;
    private static final int LONG_HIGH_SHIFT = 32;
    private static final byte ZERO_BYTE = 0;

    private final RandomAccessFile raf;
    private final byte[] buffer = new byte[WRITE_BUFFER_BYTES];
    private long bufferStartOffset;
    private int bufferPos;
    private boolean closed;

    /**
     * Wraps {@code raf} with an in-memory sequential write buffer.
     *
     * @param raf underlying host {@link RandomAccessFile}
     */
    public BufferedRandomAccessWriter(final RandomAccessFile raf) {
        this.raf = Objects.requireNonNull(raf, "raf");
    }

    /**
     * Flushes pending bytes and returns the underlying {@link RandomAccessFile} for channel sync.
     *
     * @return flushed underlying file handle
     * @throws IOException if flushing fails
     */
    public RandomAccessFile flushAndGetFile() throws IOException {
        flush();
        return this.raf;
    }

    /**
     * Flushes pending bytes and closes the underlying {@link RandomAccessFile}.
     *
     * @throws IOException if flushing or closing fails
     */
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        try {
            flush();
        } finally {
            this.closed = true;
            this.raf.close();
        }
    }

    /**
     * Seeks to {@code pos}, flushing dirty buffered bytes only when moving away from EOF cursor.
     *
     * @param pos target byte offset
     * @throws IOException if seeking fails
     */
    public void seek(final long pos) throws IOException {
        if (pos == getFilePointer()) {
            return;
        }
        flush();
        this.raf.seek(pos);
        this.bufferStartOffset = pos;
    }

    /**
     * Returns the logical write offset matching {@link RandomAccessFile#getFilePointer()}.
     *
     * @return logical file offset in bytes
     */
    public long getFilePointer() {
        return this.bufferStartOffset + this.bufferPos;
    }

    /**
     * Writes {@code zeroCount} zero bytes.
     *
     * @param zeroCount number of zero bytes to append
     * @throws IOException if writing fails
     */
    public void writeZeros(final int zeroCount) throws IOException {
        int remaining = zeroCount;
        while (remaining > 0) {
            if (this.bufferPos == WRITE_BUFFER_BYTES) {
                flush();
            }
            final int run = Math.min(remaining, WRITE_BUFFER_BYTES - this.bufferPos);
            Arrays.fill(this.buffer, this.bufferPos, this.bufferPos + run, ZERO_BYTE);
            this.bufferPos += run;
            remaining -= run;
        }
    }

    /**
     * Writes a single byte XORed with {@code key}.
     */
    public void writeByte(final byte value, final int key) throws IOException {
        ensureSpace(1);
        this.buffer[this.bufferPos++] = (byte) (value ^ key);
    }

    /**
     * Writes a big-endian 16-bit value XORed with {@code key}.
     */
    public void writeShort(final short value, final int key) throws IOException {
        final int xored = value ^ key;
        ensureSpace(2);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 8) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) (xored & BYTE_MASK);
    }

    /**
     * Writes a big-endian 32-bit value XORed with {@code key}.
     */
    public void writeInt(final int value, final int key) throws IOException {
        final int xored = value ^ key;
        ensureSpace(4);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 24) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 16) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 8) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) (xored & BYTE_MASK);
    }

    /**
     * Writes a big-endian 64-bit value XORed with the exact Cubism sign-extended 64-bit key mask.
     */
    public void writeLong(final long value, final int key) throws IOException {
        // Mirror Cubism's (i2l << 32) | i2l sign-extended mask calculation verbatim.
        final long mask = (((long) key) << LONG_HIGH_SHIFT) | ((long) key);
        final long xored = value ^ mask;
        ensureSpace(8);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 56) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 48) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 40) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 32) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 24) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 16) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) ((xored >>> 8) & BYTE_MASK);
        this.buffer[this.bufferPos++] = (byte) (xored & BYTE_MASK);
    }

    /**
     * Writes a slice of {@code data}, XORing into the internal write buffer without mutating
     * {@code data} in place.
     */
    public void writeBytes(final byte[] data, final int off, final int len, final int key, final boolean ignoredRestore)
            throws IOException {
        Objects.requireNonNull(data, "data");
        if (off < 0 || len < 0 || off + len > data.length) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return;
        }
        if (key == 0) {
            writeUnmaskedBytes(data, off, len);
            return;
        }
        int src = off;
        int remaining = len;
        while (remaining > 0) {
            if (this.bufferPos == WRITE_BUFFER_BYTES) {
                flush();
            }
            final int space = WRITE_BUFFER_BYTES - this.bufferPos;
            final int count = Math.min(remaining, space);
            final int dstEnd = this.bufferPos + count;
            for (int dst = this.bufferPos; dst < dstEnd; dst++) {
                this.buffer[dst] = (byte) (data[src++] ^ key);
            }
            this.bufferPos = dstEnd;
            remaining -= count;
        }
    }

    private void writeUnmaskedBytes(final byte[] data, final int off, final int len) throws IOException {
        if (len <= WRITE_BUFFER_BYTES - this.bufferPos) {
            System.arraycopy(data, off, this.buffer, this.bufferPos, len);
            this.bufferPos += len;
            return;
        }
        if (len >= WRITE_BUFFER_BYTES) {
            flush();
            this.raf.write(data, off, len);
            this.bufferStartOffset += len;
            return;
        }
        int src = off;
        int remaining = len;
        while (remaining > 0) {
            if (this.bufferPos == WRITE_BUFFER_BYTES) {
                flush();
            }
            final int count = Math.min(remaining, WRITE_BUFFER_BYTES - this.bufferPos);
            System.arraycopy(data, src, this.buffer, this.bufferPos, count);
            this.bufferPos += count;
            src += count;
            remaining -= count;
        }
    }

    private void ensureSpace(final int required) throws IOException {
        if (WRITE_BUFFER_BYTES - this.bufferPos < required) {
            flush();
        }
    }

    private void flush() throws IOException {
        if (this.bufferPos > 0) {
            this.raf.write(this.buffer, 0, this.bufferPos);
            this.bufferStartOffset += this.bufferPos;
            this.bufferPos = 0;
        }
    }
}
