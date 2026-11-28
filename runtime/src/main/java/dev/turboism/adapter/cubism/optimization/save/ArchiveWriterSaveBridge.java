package dev.turboism.adapter.cubism.optimization.save;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Provides zero-copy and chunked entry streams for {@code ArchiveWriter.addFileEntry_exe}.
 */
public final class ArchiveWriterSaveBridge {

    private static final String RAW_OPTION_NAME = "RAW";
    private static final int INITIAL_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_BYTES = 512 * 1024;

    private ArchiveWriterSaveBridge() {}

    /**
     * Creates an entry buffer tailored to {@code compressOption}, capturing single-write raw payloads
     * without copying and pre-sizing compressed streams to avoid 32-byte growth cascades.
     *
     * @param compressOption Cubism {@code CompressOption} instance
     * @return {@link ByteArrayOutputStream} subclass compatible with {@code ArchiveWriter}
     */
    public static ByteArrayOutputStream newEntryBufferStream(final Object compressOption) {
        final boolean rawMode = compressOption != null && RAW_OPTION_NAME.equals(compressOption.toString());
        return new ZeroCopyEntryStream(rawMode);
    }

    /**
     * {@link ByteArrayOutputStream} subclass that preserves exact single-array writes under
     * {@code CompressOption.RAW} and uses chunked allocation for multi-write payloads.
     */
    static final class ZeroCopyEntryStream extends ByteArrayOutputStream {

        private final boolean rawMode;
        private final Deque<byte[]> fullChunks = new ArrayDeque<>();
        private byte[] capturedSingleArray;
        private byte[] currentChunk;
        private int currentPos;
        private long totalWritten;
        private boolean fallbackActive;

        ZeroCopyEntryStream(final boolean rawMode) {
            super(0);
            this.rawMode = rawMode;
        }

        @Override
        public synchronized void write(final int b) {
            if (this.capturedSingleArray != null) {
                materializeSingleArray();
            }
            ensureCurrentChunk(1);
            this.currentChunk[this.currentPos++] = (byte) b;
            this.totalWritten++;
        }

        @Override
        public synchronized void write(final byte[] b, final int off, final int len) {
            if (len < 0 || off < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return;
            }
            if (this.rawMode && !this.fallbackActive && this.totalWritten == 0L && off == 0 && len == b.length) {
                this.capturedSingleArray = b;
                this.totalWritten = len;
                return;
            }
            if (this.capturedSingleArray != null) {
                materializeSingleArray();
            }
            this.fallbackActive = true;
            writeToChunks(b, off, len);
        }

        @Override
        public synchronized int size() {
            return Math.toIntExact(this.totalWritten);
        }

        @Override
        public synchronized byte[] toByteArray() {
            if (this.capturedSingleArray != null) {
                return this.capturedSingleArray;
            }
            final int total = Math.toIntExact(this.totalWritten);
            if (total == 0) {
                return new byte[0];
            }
            if (this.fullChunks.isEmpty()) {
                return this.currentPos == this.currentChunk.length
                        ? this.currentChunk
                        : Arrays.copyOf(this.currentChunk, this.currentPos);
            }
            final byte[] result = new byte[total];
            int pos = 0;
            for (final byte[] chunk : this.fullChunks) {
                System.arraycopy(chunk, 0, result, pos, chunk.length);
                pos += chunk.length;
            }
            if (this.currentPos > 0) {
                System.arraycopy(this.currentChunk, 0, result, pos, this.currentPos);
            }
            return result;
        }

        @Override
        public void flush() throws IOException {}

        @Override
        public void close() throws IOException {}

        private void materializeSingleArray() {
            final byte[] prev = this.capturedSingleArray;
            this.capturedSingleArray = null;
            this.fallbackActive = true;
            this.totalWritten = 0L;
            writeToChunks(prev, 0, prev.length);
        }

        private void writeToChunks(final byte[] src, final int off, final int len) {
            int offset = off;
            int remaining = len;
            while (remaining > 0) {
                ensureCurrentChunk(remaining);
                final int space = this.currentChunk.length - this.currentPos;
                final int copy = Math.min(remaining, space);
                System.arraycopy(src, offset, this.currentChunk, this.currentPos, copy);
                this.currentPos += copy;
                this.totalWritten += copy;
                offset += copy;
                remaining -= copy;
            }
        }

        private void ensureCurrentChunk(final int needed) {
            if (this.currentChunk == null) {
                this.currentChunk = new byte[chooseChunkSize(needed)];
                this.currentPos = 0;
                return;
            }
            if (this.currentPos == this.currentChunk.length) {
                this.fullChunks.addLast(this.currentChunk);
                this.currentChunk = new byte[chooseChunkSize(needed)];
                this.currentPos = 0;
            }
        }

        private static int chooseChunkSize(final int hint) {
            return Math.max(INITIAL_CHUNK_BYTES, Math.min(hint, MAX_CHUNK_BYTES));
        }
    }
}
