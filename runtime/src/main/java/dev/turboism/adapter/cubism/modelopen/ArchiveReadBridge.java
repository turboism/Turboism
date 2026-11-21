package dev.turboism.adapter.cubism.modelopen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Provides zero-copy RAW entry streams and exact-sized ZIP reads for Cubism archive deserialization.
 */
public final class ArchiveReadBridge {

    private static final int MAX_PREALLOCATED_ARRAY_BYTES = Integer.MAX_VALUE - 8;
    private static final int STREAM_COPY_BUFFER_BYTES = 8192;

    private ArchiveReadBridge() {}

    /**
     * Wraps an owned RAW archive buffer so unread callers can claim the array without copying.
     *
     * @param rawBytes owned archive entry payload
     * @return input stream backed directly by {@code rawBytes}
     */
    public static InputStream wrapRawBytes(final byte[] rawBytes) {
        return new OwnedRawBytesInputStream(rawBytes);
    }

    /**
     * Wraps an open {@link ZipInputStream} entry with its declared uncompressed size when known.
     *
     * @param zipStream stream positioned after {@code getNextEntry()}
     * @param entry current ZIP entry header, nullable
     * @return sized wrapper stream when entry size fits in an array, otherwise {@code zipStream}
     */
    public static InputStream wrapZipEntry(final ZipInputStream zipStream, final ZipEntry entry) {
        if (zipStream == null || entry == null) {
            return zipStream;
        }
        final long size = entry.getSize();
        if (size < 0L || size > MAX_PREALLOCATED_ARRAY_BYTES) {
            return zipStream;
        }
        return new SizedZipEntryInputStream(zipStream, (int) size);
    }

    /**
     * Reads all remaining bytes from {@code inputStream} without buffer-doubling copies when possible.
     *
     * @param inputStream archive entry stream
     * @return byte-exact entry contents
     * @throws IOException if underlying stream reading fails
     */
    public static byte[] readAllBytes(final InputStream inputStream) throws IOException {
        if (inputStream instanceof OwnedRawBytesInputStream rawStream) {
            final byte[] direct = rawStream.claimUnreadBackingArray();
            if (direct != null) {
                return direct;
            }
        }
        if (inputStream instanceof SizedZipEntryInputStream sizedStream) {
            return sizedStream.readExactRemainingBytes();
        }
        return inputStream.readAllBytes();
    }

    private static byte[] readOverflow(final InputStream stream, final byte[] prefix, final int prefixLen)
            throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(prefixLen + STREAM_COPY_BUFFER_BYTES);
        out.write(prefix, 0, prefixLen);
        final byte[] scratch = new byte[STREAM_COPY_BUFFER_BYTES];
        int read;
        while ((read = stream.read(scratch, 0, scratch.length)) >= 0) {
            out.write(scratch, 0, read);
        }
        return out.toByteArray();
    }

    private static final class OwnedRawBytesInputStream extends ByteArrayInputStream {

        private final byte[] backing;

        private OwnedRawBytesInputStream(final byte[] bytes) {
            super(bytes);
            this.backing = bytes;
        }

        private byte[] claimUnreadBackingArray() {
            if (this.pos != 0 || this.count != this.backing.length) {
                return null;
            }
            this.pos = this.count;
            return this.backing;
        }
    }

    private static final class SizedZipEntryInputStream extends FilterInputStream {

        private int remainingExpected;

        private SizedZipEntryInputStream(final ZipInputStream zipStream, final int expectedSize) {
            super(zipStream);
            this.remainingExpected = expectedSize;
        }

        @Override
        public int read() throws IOException {
            final int b = super.read();
            if (b >= 0 && this.remainingExpected > 0) {
                this.remainingExpected--;
            }
            return b;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final int read = super.read(b, off, len);
            if (read > 0) {
                this.remainingExpected = Math.max(0, this.remainingExpected - read);
            }
            return read;
        }

        @Override
        public long skip(final long n) throws IOException {
            final long skipped = super.skip(n);
            if (skipped > 0L) {
                this.remainingExpected = (int) Math.max(0L, this.remainingExpected - skipped);
            }
            return skipped;
        }

        private byte[] readExactRemainingBytes() throws IOException {
            final int expected = this.remainingExpected;
            final byte[] result = new byte[expected];
            int offset = 0;
            while (offset < expected) {
                final int n = this.in.read(result, offset, expected - offset);
                if (n < 0) {
                    this.remainingExpected = 0;
                    final byte[] trimmed = new byte[offset];
                    System.arraycopy(result, 0, trimmed, 0, offset);
                    return trimmed;
                }
                offset += n;
            }
            this.remainingExpected = 0;
            final int extra = this.in.read();
            if (extra < 0) {
                return result;
            }
            final byte[] withExtra = new byte[offset + 1];
            System.arraycopy(result, 0, withExtra, 0, offset);
            withExtra[offset] = (byte) extra;
            return readOverflow(this.in, withExtra, offset + 1);
        }
    }
}
