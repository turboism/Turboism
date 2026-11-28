package dev.turboism.adapter.cubism.optimization.save;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Streams bytes into a single-entry ZIP archive ({@code "contents"}) with bounded parallel chunk
 * deflation and exact single-allocation ZIP framing.
 */
final class ParallelZipEntryBuilder extends OutputStream {

    private static final String ZIP_ENTRY_NAME = "contents";
    private static final byte[] ZIP_ENTRY_NAME_BYTES = ZIP_ENTRY_NAME.getBytes(StandardCharsets.US_ASCII);
    private static final int ENTRY_NAME_LEN = ZIP_ENTRY_NAME_BYTES.length;

    private static final int INITIAL_CHUNK_BYTES = 64 * 1024;
    private static final int PARALLEL_CHUNK_BYTES = 512 * 1024;
    private static final int MIN_DEFLATE_GROW_BYTES = 4096;
    private static final int DEFLATE_OVERHEAD_SHIFT = 8;
    private static final int DEFLATE_OVERHEAD_PADDING = 128;

    private static final int MIN_POOL_THREADS = 2;
    private static final int MAX_POOL_THREADS = 6;
    private static final int MAX_QUEUE_CAPACITY = 4096;
    private static final String WORKER_THREAD_PREFIX = "save-deflate";

    private static final int LFH_SIGNATURE = 0x04034B50;
    private static final int CDH_SIGNATURE = 0x02014B50;
    private static final int EOCD_SIGNATURE = 0x06054B50;
    private static final int VERSION_EXTRACT = 20;
    private static final int VERSION_MADE_BY = 20;
    private static final int UTF8_GENERAL_FLAG = 0x0800;
    private static final int METHOD_DEFLATE = 8;
    private static final int DOS_DEFAULT_TIME = 0;
    private static final int DOS_DEFAULT_DATE = 0x0021;
    private static final int LFH_FIXED_BYTES = 30;
    private static final int CDH_FIXED_BYTES = 46;
    private static final int EOCD_FIXED_BYTES = 22;
    private static final long MAX_ZIP32_SIZE = 0xFFFFFFFFL;

    private static final int GF2_DIMENSION = 32;
    private static final long CRC32_POLYNOMIAL = 0xEDB88320L;
    private static final int BYTE_MASK = 0xFF;

    private static volatile ExecutorService deflatePool;

    private final int zipLevel;
    private final List<CompletableFuture<CompressedChunk>> asyncChunks = new ArrayList<>();
    private final byte[] singleByteScratch = new byte[1];

    private byte[] currentBuf = new byte[INITIAL_CHUNK_BYTES];
    private int currentLen;
    private byte[] pendingFullChunk;
    private boolean finished;

    ParallelZipEntryBuilder(final int zipLevel) {
        this.zipLevel = zipLevel;
    }

    @Override
    public void write(final int b) throws IOException {
        this.singleByteScratch[0] = (byte) b;
        write(this.singleByteScratch, 0, 1);
    }

    @Override
    public void write(final byte[] bytes, final int off, final int len) throws IOException {
        if (this.finished) {
            throw new IOException("ZIP builder already finished");
        }
        if (len == 0) {
            return;
        }
        int offset = off;
        int remaining = len;
        while (remaining > 0) {
            if (this.currentLen == PARALLEL_CHUNK_BYTES) {
                dispatchFullChunk(this.currentBuf);
                this.currentBuf = new byte[PARALLEL_CHUNK_BYTES];
                this.currentLen = 0;
            }
            if (this.currentBuf.length < PARALLEL_CHUNK_BYTES && this.currentLen + remaining > this.currentBuf.length) {
                final int grownCapacity = Math.min(
                        PARALLEL_CHUNK_BYTES, Math.max(this.currentBuf.length * 2, this.currentLen + remaining));
                this.currentBuf = Arrays.copyOf(this.currentBuf, grownCapacity);
            }
            final int space = this.currentBuf.length - this.currentLen;
            final int copyLen = Math.min(remaining, space);
            System.arraycopy(bytes, offset, this.currentBuf, this.currentLen, copyLen);
            this.currentLen += copyLen;
            offset += copyLen;
            remaining -= copyLen;
        }
    }

    byte[] toZipBytes() throws IOException {
        this.finished = true;
        final List<CompressedChunk> completed = collectAllChunks();
        long uncompressedTotal = 0L;
        long compressedTotal = 0L;
        int combinedCrc = 0;
        for (int i = 0; i < completed.size(); i++) {
            final CompressedChunk chunk = completed.get(i);
            uncompressedTotal += chunk.uncompressedLen;
            compressedTotal += chunk.compressedLen;
            combinedCrc = i == 0 ? chunk.crc32 : combineCrc32(combinedCrc, chunk.crc32, chunk.uncompressedLen);
        }
        final int lfhTotal = LFH_FIXED_BYTES + ENTRY_NAME_LEN;
        final int cdhTotal = CDH_FIXED_BYTES + ENTRY_NAME_LEN;
        final long totalArchiveBytes = lfhTotal + compressedTotal + cdhTotal + EOCD_FIXED_BYTES;
        if (uncompressedTotal > MAX_ZIP32_SIZE
                || compressedTotal > MAX_ZIP32_SIZE
                || totalArchiveBytes > Integer.MAX_VALUE) {
            throw new IOException("ZIP entry exceeds supported 32-bit archive size");
        }

        final byte[] out = new byte[(int) totalArchiveBytes];
        int pos = writeLocalFileHeader(out, 0, combinedCrc, (int) compressedTotal, (int) uncompressedTotal);
        for (final CompressedChunk chunk : completed) {
            System.arraycopy(chunk.compressedBytes, 0, out, pos, chunk.compressedLen);
            pos += chunk.compressedLen;
        }
        final int centralDirOffset = pos;
        pos = writeCentralDirectoryHeader(out, pos, combinedCrc, (int) compressedTotal, (int) uncompressedTotal);
        writeEndOfCentralDirectory(out, pos, cdhTotal, centralDirOffset);
        return out;
    }

    private void dispatchFullChunk(final byte[] fullChunk) {
        if (this.pendingFullChunk == null) {
            this.pendingFullChunk = fullChunk;
            return;
        }
        final byte[] readyChunk = this.pendingFullChunk;
        this.pendingFullChunk = fullChunk;
        submitIntermediateChunk(readyChunk);
    }

    private void submitIntermediateChunk(final byte[] readyChunk) {
        final ExecutorService pool = getOrCreatePool();
        final int level = this.zipLevel;
        try {
            final CompletableFuture<CompressedChunk> future = CompletableFuture.supplyAsync(
                    () -> compressChunk(readyChunk, readyChunk.length, level, false), pool);
            this.asyncChunks.add(future);
        } catch (final RejectedExecutionException saturated) {
            this.asyncChunks.add(
                    CompletableFuture.completedFuture(compressChunk(readyChunk, readyChunk.length, level, false)));
        }
    }

    private List<CompressedChunk> collectAllChunks() throws IOException {
        final CompressedChunk finalChunk;
        if (this.pendingFullChunk != null) {
            if (this.currentLen > 0) {
                submitIntermediateChunk(this.pendingFullChunk);
                this.pendingFullChunk = null;
                finalChunk = compressChunk(this.currentBuf, this.currentLen, this.zipLevel, true);
            } else {
                final byte[] lastFull = this.pendingFullChunk;
                this.pendingFullChunk = null;
                finalChunk = compressChunk(lastFull, lastFull.length, this.zipLevel, true);
            }
        } else {
            finalChunk = compressChunk(this.currentBuf, this.currentLen, this.zipLevel, true);
        }
        this.currentBuf = null;

        final List<CompressedChunk> results = new ArrayList<>(this.asyncChunks.size() + 1);
        Throwable firstFailure = null;
        for (final CompletableFuture<CompressedChunk> future : this.asyncChunks) {
            try {
                results.add(future.get());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (firstFailure == null) {
                    firstFailure = interrupted;
                }
            } catch (final ExecutionException failed) {
                if (firstFailure == null) {
                    firstFailure = failed.getCause() != null ? failed.getCause() : failed;
                }
            }
        }
        if (firstFailure != null) {
            FatalErrors.rethrowIfFatal(firstFailure);
            throw new IOException("Parallel XML ZIP deflation failed", firstFailure);
        }
        results.add(finalChunk);
        return results;
    }

    private static CompressedChunk compressChunk(
            final byte[] input, final int len, final int zipLevel, final boolean isFinalChunk) {
        final CRC32 crc = new CRC32();
        crc.update(input, 0, len);
        final int chunkCrc = (int) crc.getValue();

        final Deflater deflater = new Deflater(zipLevel, true);
        try {
            final int initialOutLen =
                    Math.max(MIN_DEFLATE_GROW_BYTES, len + (len >>> DEFLATE_OVERHEAD_SHIFT) + DEFLATE_OVERHEAD_PADDING);
            byte[] out = new byte[initialOutLen];
            int pos = 0;
            deflater.setInput(input, 0, len);
            if (isFinalChunk) {
                deflater.finish();
                while (!deflater.finished()) {
                    if (pos == out.length) {
                        out = Arrays.copyOf(out, out.length + Math.max(MIN_DEFLATE_GROW_BYTES, out.length >>> 1));
                    }
                    pos += deflater.deflate(out, pos, out.length - pos);
                }
            } else {
                while (true) {
                    if (pos == out.length) {
                        out = Arrays.copyOf(out, out.length + Math.max(MIN_DEFLATE_GROW_BYTES, out.length >>> 1));
                    }
                    pos += deflater.deflate(out, pos, out.length - pos, Deflater.SYNC_FLUSH);
                    if (deflater.needsInput() && pos < out.length) {
                        break;
                    }
                }
            }
            return new CompressedChunk(out, pos, len, chunkCrc);
        } finally {
            deflater.end();
        }
    }

    static int combineCrc32(final int crc1, final int crc2, final long len2) {
        if (len2 <= 0L) {
            return crc1;
        }
        final long[] even = new long[GF2_DIMENSION];
        final long[] odd = new long[GF2_DIMENSION];

        odd[0] = CRC32_POLYNOMIAL;
        long row = 1L;
        for (int n = 1; n < GF2_DIMENSION; n++) {
            odd[n] = row;
            row <<= 1;
        }
        gf2MatrixSquare(even, odd);
        gf2MatrixSquare(odd, even);

        long combined = Integer.toUnsignedLong(crc1);
        long remaining = len2;
        do {
            gf2MatrixSquare(even, odd);
            if ((remaining & 1L) != 0L) {
                combined = gf2MatrixTimes(even, combined);
            }
            remaining >>>= 1;
            if (remaining == 0L) {
                break;
            }
            gf2MatrixSquare(odd, even);
            if ((remaining & 1L) != 0L) {
                combined = gf2MatrixTimes(odd, combined);
            }
            remaining >>>= 1;
        } while (remaining != 0L);

        return (int) (combined ^ Integer.toUnsignedLong(crc2));
    }

    private static long gf2MatrixTimes(final long[] mat, final long vec) {
        long sum = 0L;
        long bits = vec;
        int idx = 0;
        while (bits != 0L) {
            if ((bits & 1L) != 0L) {
                sum ^= mat[idx];
            }
            bits >>>= 1;
            idx++;
        }
        return sum;
    }

    private static void gf2MatrixSquare(final long[] square, final long[] mat) {
        for (int n = 0; n < GF2_DIMENSION; n++) {
            square[n] = gf2MatrixTimes(mat, mat[n]);
        }
    }

    private static int writeLocalFileHeader(
            final byte[] out,
            final int startPos,
            final int crc32,
            final int compressedSize,
            final int uncompressedSize) {
        int pos = writeLeInt(out, startPos, LFH_SIGNATURE);
        pos = writeLeShort(out, pos, VERSION_EXTRACT);
        pos = writeLeShort(out, pos, UTF8_GENERAL_FLAG);
        pos = writeLeShort(out, pos, METHOD_DEFLATE);
        pos = writeLeShort(out, pos, DOS_DEFAULT_TIME);
        pos = writeLeShort(out, pos, DOS_DEFAULT_DATE);
        pos = writeLeInt(out, pos, crc32);
        pos = writeLeInt(out, pos, compressedSize);
        pos = writeLeInt(out, pos, uncompressedSize);
        pos = writeLeShort(out, pos, ENTRY_NAME_LEN);
        pos = writeLeShort(out, pos, 0);
        System.arraycopy(ZIP_ENTRY_NAME_BYTES, 0, out, pos, ENTRY_NAME_LEN);
        return pos + ENTRY_NAME_LEN;
    }

    private static int writeCentralDirectoryHeader(
            final byte[] out,
            final int startPos,
            final int crc32,
            final int compressedSize,
            final int uncompressedSize) {
        int pos = writeLeInt(out, startPos, CDH_SIGNATURE);
        pos = writeLeShort(out, pos, VERSION_MADE_BY);
        pos = writeLeShort(out, pos, VERSION_EXTRACT);
        pos = writeLeShort(out, pos, UTF8_GENERAL_FLAG);
        pos = writeLeShort(out, pos, METHOD_DEFLATE);
        pos = writeLeShort(out, pos, DOS_DEFAULT_TIME);
        pos = writeLeShort(out, pos, DOS_DEFAULT_DATE);
        pos = writeLeInt(out, pos, crc32);
        pos = writeLeInt(out, pos, compressedSize);
        pos = writeLeInt(out, pos, uncompressedSize);
        pos = writeLeShort(out, pos, ENTRY_NAME_LEN);
        pos = writeLeShort(out, pos, 0);
        pos = writeLeShort(out, pos, 0);
        pos = writeLeShort(out, pos, 0);
        pos = writeLeShort(out, pos, 0);
        pos = writeLeInt(out, pos, 0);
        pos = writeLeInt(out, pos, 0);
        System.arraycopy(ZIP_ENTRY_NAME_BYTES, 0, out, pos, ENTRY_NAME_LEN);
        return pos + ENTRY_NAME_LEN;
    }

    private static int writeEndOfCentralDirectory(
            final byte[] out, final int startPos, final int centralDirSize, final int centralDirOffset) {
        int pos = writeLeInt(out, startPos, EOCD_SIGNATURE);
        pos = writeLeShort(out, pos, 0);
        pos = writeLeShort(out, pos, 0);
        pos = writeLeShort(out, pos, 1);
        pos = writeLeShort(out, pos, 1);
        pos = writeLeInt(out, pos, centralDirSize);
        pos = writeLeInt(out, pos, centralDirOffset);
        return writeLeShort(out, pos, 0);
    }

    private static int writeLeShort(final byte[] out, final int pos, final int value) {
        out[pos] = (byte) (value & BYTE_MASK);
        out[pos + 1] = (byte) ((value >>> 8) & BYTE_MASK);
        return pos + 2;
    }

    private static int writeLeInt(final byte[] out, final int pos, final int value) {
        out[pos] = (byte) (value & BYTE_MASK);
        out[pos + 1] = (byte) ((value >>> 8) & BYTE_MASK);
        out[pos + 2] = (byte) ((value >>> 16) & BYTE_MASK);
        out[pos + 3] = (byte) ((value >>> 24) & BYTE_MASK);
        return pos + 4;
    }

    private static ExecutorService getOrCreatePool() {
        final ExecutorService existing = deflatePool;
        if (existing != null) {
            return existing;
        }
        synchronized (ParallelZipEntryBuilder.class) {
            if (deflatePool == null) {
                final int cpus = Runtime.getRuntime().availableProcessors();
                final int workers = Math.min(MAX_POOL_THREADS, Math.max(MIN_POOL_THREADS, cpus - 1));
                deflatePool = RuntimeThreads.fixedLane(
                        workers,
                        MAX_QUEUE_CAPACITY,
                        RuntimeThreads.daemonThreads(
                                WORKER_THREAD_PREFIX,
                                RuntimeThreads.contextClassLoader(ParallelZipEntryBuilder.class)));
            }
            return deflatePool;
        }
    }

    private record CompressedChunk(byte[] compressedBytes, int compressedLen, int uncompressedLen, int crc32) {}
}
