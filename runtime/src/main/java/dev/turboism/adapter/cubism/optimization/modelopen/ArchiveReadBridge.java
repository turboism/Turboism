package dev.turboism.adapter.cubism.optimization.modelopen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Provides zero-copy RAW entry streams and exact-sized ZIP reads for Cubism archive deserialization.
 */
public final class ArchiveReadBridge {

    private static final int MAX_PREALLOCATED_ARRAY_BYTES = Integer.MAX_VALUE - 8;
    private static final int STREAM_COPY_BUFFER_BYTES = 8192;

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CDH_SIGNATURE = 0x02014b50;
    private static final int LFH_SIGNATURE = 0x04034b50;

    private static final int EOCD_MIN_SIZE = 22;
    private static final int CDH_MIN_SIZE = 46;
    private static final int LFH_MIN_SIZE = 30;
    private static final int MAX_ZIP_COMMENT_BYTES = 0xFFFF;

    private static final int EOCD_DISK_OFFSET = 4;
    private static final int EOCD_CD_START_DISK_OFFSET = 6;
    private static final int EOCD_DISK_ENTRIES_OFFSET = 8;
    private static final int EOCD_TOTAL_ENTRIES_OFFSET = 10;
    private static final int EOCD_CD_SIZE_OFFSET = 12;
    private static final int EOCD_CD_OFFSET_OFFSET = 16;
    private static final int EOCD_COMMENT_LEN_OFFSET = 20;

    private static final int CDH_FLAGS_OFFSET = 8;
    private static final int CDH_METHOD_OFFSET = 10;
    private static final int CDH_CRC_OFFSET = 16;
    private static final int CDH_COMP_SIZE_OFFSET = 20;
    private static final int CDH_UNCOMP_SIZE_OFFSET = 24;
    private static final int CDH_NAME_LEN_OFFSET = 28;
    private static final int CDH_EXTRA_LEN_OFFSET = 30;
    private static final int CDH_COMMENT_LEN_OFFSET = 32;
    private static final int CDH_LFH_OFFSET_OFFSET = 42;

    private static final int LFH_FLAGS_OFFSET = 6;
    private static final int LFH_METHOD_OFFSET = 8;
    private static final int LFH_NAME_LEN_OFFSET = 26;
    private static final int LFH_EXTRA_LEN_OFFSET = 28;

    private static final int ZIP_METHOD_STORED = 0;
    private static final int ZIP_METHOD_DEFLATED = 8;
    private static final int ZIP_FLAG_ENCRYPTED = 0x0001;
    private static final int SINGLE_ENTRY_COUNT = 1;
    private static final long ZIP64_UINT_MARKER = 0xFFFFFFFFL;

    private static final int BYTE_MASK = 0xFF;
    private static final long UINT_MASK = 0xFFFFFFFFL;
    private static final int SHIFT_ONE_BYTE = 8;
    private static final int SHIFT_TWO_BYTES = 16;
    private static final int SHIFT_THREE_BYTES = 24;
    private static final int ONE_BYTE_PROBE = 1;

    private static final ThreadLocal<Inflater> RAW_INFLATER = ThreadLocal.withInitial(() -> new Inflater(true));
    private static final Map<Object, ArchivePrefetchSession> PREFETCH_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ArchiveReadBridge() {}

    /**
     * Starts parallel background prefetch and inflation for non-excluded entries of {@code archiveReader}.
     *
     * @param archiveReader Cubism {@code ArchiveReader} instance, nullable
     * @param excludePath entry path to skip (typically the entry being read synchronously), nullable
     */
    public static void startArchivePrefetch(final Object archiveReader, final String excludePath) {
        if (archiveReader == null) {
            return;
        }
        synchronized (PREFETCH_SESSIONS) {
            if (PREFETCH_SESSIONS.containsKey(archiveReader)) {
                return;
            }
            PREFETCH_SESSIONS.put(archiveReader, ArchivePrefetchSession.tryStart(archiveReader, excludePath));
        }
    }

    /**
     * Claims a prefetched stream for {@code path}, auto-starting background prefetch on the first
     * eligible archive read so subsequent entries avoid serial I/O and inflation.
     *
     * @param archiveReader Cubism {@code ArchiveReader} instance, nullable
     * @param path archive entry path, nullable
     * @return zero-copy stream when prefetched, or {@code null} to execute the native read path
     */
    public static InputStream tryTakePrefetchedStream(final Object archiveReader, final String path) {
        if (archiveReader == null || path == null) {
            return null;
        }
        ArchivePrefetchSession session = PREFETCH_SESSIONS.get(archiveReader);
        if (session == null) {
            synchronized (PREFETCH_SESSIONS) {
                session = PREFETCH_SESSIONS.get(archiveReader);
                if (session == null) {
                    PREFETCH_SESSIONS.put(archiveReader, ArchivePrefetchSession.tryStart(archiveReader, path));
                    return null;
                }
            }
        }
        if (!session.isActive()) {
            return null;
        }
        return session.tryTakeStream(path);
    }

    /**
     * Cancels background prefetch work, releases unconsumed buffers, and closes the prefetch
     * channel associated with {@code archiveReader}.
     *
     * @param archiveReader Cubism {@code ArchiveReader} instance, nullable
     */
    public static void closeArchivePrefetch(final Object archiveReader) {
        if (archiveReader == null) {
            return;
        }
        final ArchivePrefetchSession previous;
        synchronized (PREFETCH_SESSIONS) {
            previous = PREFETCH_SESSIONS.put(archiveReader, ArchivePrefetchSession.DISABLED);
        }
        if (previous != null) {
            previous.close();
        }
    }

    static void registerPrefetchSessionForTest(final Object archiveReader, final ArchivePrefetchSession session) {
        synchronized (PREFETCH_SESSIONS) {
            PREFETCH_SESSIONS.put(archiveReader, session);
        }
    }

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
     * Extracts a single-entry in-memory ZIP directly into an exact-sized zero-copy stream,
     * falling back to {@link ZipInputStream} for multi-entry, ZIP64, encrypted, or malformed inputs.
     *
     * @param zipBytes complete in-memory ZIP archive bytes
     * @return input stream positioned at the entry payload
     * @throws IOException if fallback ZIP stream initialization fails
     */
    public static InputStream openZipEntryStream(final byte[] zipBytes) throws IOException {
        if (zipBytes != null) {
            final byte[] extracted = tryExtractSingleEntry(zipBytes);
            if (extracted != null) {
                return wrapRawBytes(extracted);
            }
        }
        return openFallbackZipStream(zipBytes);
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

    private static InputStream openFallbackZipStream(final byte[] zipBytes) throws IOException {
        final ZipInputStream zipStream = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        final ZipEntry entry = zipStream.getNextEntry();
        return wrapZipEntry(zipStream, entry);
    }

    private static byte[] tryExtractSingleEntry(final byte[] zipBytes) {
        final int eocdPos = findEocdOffset(zipBytes);
        if (eocdPos < 0) {
            return null;
        }
        if (readUShortLE(zipBytes, eocdPos + EOCD_DISK_OFFSET) != 0
                || readUShortLE(zipBytes, eocdPos + EOCD_CD_START_DISK_OFFSET) != 0
                || readUShortLE(zipBytes, eocdPos + EOCD_DISK_ENTRIES_OFFSET) != SINGLE_ENTRY_COUNT
                || readUShortLE(zipBytes, eocdPos + EOCD_TOTAL_ENTRIES_OFFSET) != SINGLE_ENTRY_COUNT) {
            return null;
        }
        final long cdSize = readUIntLE(zipBytes, eocdPos + EOCD_CD_SIZE_OFFSET);
        final long cdOffset = readUIntLE(zipBytes, eocdPos + EOCD_CD_OFFSET_OFFSET);
        if (cdSize == ZIP64_UINT_MARKER
                || cdOffset == ZIP64_UINT_MARKER
                || cdSize < CDH_MIN_SIZE
                || cdOffset + cdSize > eocdPos) {
            return null;
        }

        final int cdPos = (int) cdOffset;
        if (readIntLE(zipBytes, cdPos) != CDH_SIGNATURE) {
            return null;
        }
        final int cdFlags = readUShortLE(zipBytes, cdPos + CDH_FLAGS_OFFSET);
        if ((cdFlags & ZIP_FLAG_ENCRYPTED) != 0) {
            return null;
        }
        final int method = readUShortLE(zipBytes, cdPos + CDH_METHOD_OFFSET);
        if (method != ZIP_METHOD_STORED && method != ZIP_METHOD_DEFLATED) {
            return null;
        }
        final long expectedCrc = readUIntLE(zipBytes, cdPos + CDH_CRC_OFFSET);
        final long compressedSize = readUIntLE(zipBytes, cdPos + CDH_COMP_SIZE_OFFSET);
        final long uncompressedSize = readUIntLE(zipBytes, cdPos + CDH_UNCOMP_SIZE_OFFSET);
        final long lfhOffset = readUIntLE(zipBytes, cdPos + CDH_LFH_OFFSET_OFFSET);
        if (compressedSize == ZIP64_UINT_MARKER
                || uncompressedSize == ZIP64_UINT_MARKER
                || lfhOffset == ZIP64_UINT_MARKER
                || uncompressedSize > MAX_PREALLOCATED_ARRAY_BYTES
                || compressedSize > Integer.MAX_VALUE) {
            return null;
        }

        final long cdEntrySpan = (long) CDH_MIN_SIZE
                + readUShortLE(zipBytes, cdPos + CDH_NAME_LEN_OFFSET)
                + readUShortLE(zipBytes, cdPos + CDH_EXTRA_LEN_OFFSET)
                + readUShortLE(zipBytes, cdPos + CDH_COMMENT_LEN_OFFSET);
        if (cdEntrySpan > cdSize || lfhOffset + LFH_MIN_SIZE > cdOffset) {
            return null;
        }

        final int lfhPos = (int) lfhOffset;
        if (readIntLE(zipBytes, lfhPos) != LFH_SIGNATURE) {
            return null;
        }
        if ((readUShortLE(zipBytes, lfhPos + LFH_FLAGS_OFFSET) & ZIP_FLAG_ENCRYPTED) != 0
                || readUShortLE(zipBytes, lfhPos + LFH_METHOD_OFFSET) != method) {
            return null;
        }
        final long dataStart = lfhOffset
                + LFH_MIN_SIZE
                + readUShortLE(zipBytes, lfhPos + LFH_NAME_LEN_OFFSET)
                + readUShortLE(zipBytes, lfhPos + LFH_EXTRA_LEN_OFFSET);
        if (dataStart + compressedSize > cdOffset) {
            return null;
        }

        final int dataOff = (int) dataStart;
        final int compLen = (int) compressedSize;
        final int outLen = (int) uncompressedSize;
        if (method == ZIP_METHOD_STORED) {
            return extractStored(zipBytes, dataOff, compLen, outLen, expectedCrc);
        }
        return extractDeflated(zipBytes, dataOff, compLen, outLen, expectedCrc);
    }

    private static int findEocdOffset(final byte[] zipBytes) {
        if (zipBytes.length < EOCD_MIN_SIZE) {
            return -1;
        }
        final int maxStart = zipBytes.length - EOCD_MIN_SIZE;
        final int minStart = Math.max(0, maxStart - MAX_ZIP_COMMENT_BYTES);
        for (int pos = maxStart; pos >= minStart; pos--) {
            if (readIntLE(zipBytes, pos) == EOCD_SIGNATURE) {
                final int commentLen = readUShortLE(zipBytes, pos + EOCD_COMMENT_LEN_OFFSET);
                if (pos + EOCD_MIN_SIZE + commentLen == zipBytes.length) {
                    return pos;
                }
            }
        }
        return -1;
    }

    private static byte[] extractStored(
            final byte[] zipBytes, final int dataOff, final int compLen, final int outLen, final long expectedCrc) {
        if (compLen != outLen) {
            return null;
        }
        final byte[] out = new byte[outLen];
        System.arraycopy(zipBytes, dataOff, out, 0, outLen);
        return matchesCrc32(out, expectedCrc) ? out : null;
    }

    private static byte[] extractDeflated(
            final byte[] zipBytes, final int dataOff, final int compLen, final int outLen, final long expectedCrc) {
        final Inflater inflater = RAW_INFLATER.get();
        inflater.reset();
        try {
            inflater.setInput(zipBytes, dataOff, compLen);
            final byte[] out = new byte[outLen];
            int offset = 0;
            while (offset < outLen && !inflater.finished()) {
                final int produced = inflater.inflate(out, offset, outLen - offset);
                if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                offset += produced;
            }
            if (offset != outLen) {
                return null;
            }
            if (!inflater.finished()) {
                final byte[] probe = new byte[ONE_BYTE_PROBE];
                if (inflater.inflate(probe, 0, ONE_BYTE_PROBE) != 0 || !inflater.finished()) {
                    return null;
                }
            }
            return matchesCrc32(out, expectedCrc) ? out : null;
        } catch (final DataFormatException ignored) {
            return null;
        } finally {
            inflater.reset();
        }
    }

    private static boolean matchesCrc32(final byte[] payload, final long expectedCrc) {
        final CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        return crc.getValue() == expectedCrc;
    }

    private static int readUShortLE(final byte[] data, final int off) {
        return (data[off] & BYTE_MASK) | ((data[off + 1] & BYTE_MASK) << SHIFT_ONE_BYTE);
    }

    private static int readIntLE(final byte[] data, final int off) {
        return (data[off] & BYTE_MASK)
                | ((data[off + 1] & BYTE_MASK) << SHIFT_ONE_BYTE)
                | ((data[off + 2] & BYTE_MASK) << SHIFT_TWO_BYTES)
                | ((data[off + 3] & BYTE_MASK) << SHIFT_THREE_BYTES);
    }

    private static long readUIntLE(final byte[] data, final int off) {
        return readIntLE(data, off) & UINT_MASK;
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
