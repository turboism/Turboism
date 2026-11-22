package dev.turboism.update;

import dev.turboism.core.io.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reconstructs a target JAR byte-for-byte from a verified local JAR plus HTTP Range fetches.
 *
 * <p>Reuses unchanged ZIP entry spans directly from the authenticated local archive and fetches only
 * changed entry ranges plus the remote Central Directory. Returns empty whenever the archive format,
 * server range semantics, savings threshold, or final SHA-256 check fails so callers fall back to a
 * full download without compromising authenticity.</p>
 */
final class ZipRangeDeltaAssembler {
    static final long MIN_DELTA_OBJECT_BYTES = 256L * 1024L;
    static final int EOCD_MIN_BYTES = 22;
    static final int EOCD_MAX_TAIL_BYTES = EOCD_MIN_BYTES + 65535;
    static final int MAX_CENTRAL_DIRECTORY_BYTES = 8 * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 65535;
    static final long COALESCE_GAP_BYTES = 32L * 1024L;
    static final int MAX_RANGE_REQUESTS = 8;
    static final int MAX_DELTA_RATIO_NUMERATOR = 3;
    static final int MAX_DELTA_RATIO_DENOMINATOR = 4;

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CD_SIGNATURE = 0x02014b50;
    private static final int CD_FIXED_HEADER_BYTES = 46;
    private static final int ZIP64_SENTINEL_16 = 0xFFFF;
    private static final long ZIP64_SENTINEL_32 = 0xFFFFFFFFL;
    private static final long UNSIGNED_INT_MASK = 0xFFFFFFFFL;
    private static final int UNSIGNED_SHORT_MASK = 0xFFFF;
    private static final int COPY_BUFFER_BYTES = 32768;

    private ZipRangeDeltaAssembler() {}

    /** Fetches an exact inclusive HTTP byte range [start, endInclusive] into a byte array. */
    @FunctionalInterface
    interface RangeBytesFetcher {
        Optional<RangeChunk> fetch(long start, long endInclusive, String expectedEtag)
                throws IOException, InterruptedException;
    }

    /** Streams an exact inclusive HTTP byte range [start, endInclusive] directly into a sink. */
    @FunctionalInterface
    interface RangeStreamFetcher {
        boolean stream(long start, long endInclusive, String expectedEtag, ChunkSink sink)
                throws IOException, InterruptedException;
    }

    /** Receives streaming bytes from a validated remote range response. */
    @FunctionalInterface
    interface ChunkSink {
        void write(byte[] buffer, int offset, int length) throws IOException, InterruptedException;
    }

    /** Reports planned delta total bytes and live cumulative network bytes received. */
    interface ProgressListener {
        void onPlan(long totalNetworkBytes);

        void onNetworkProgress(long receivedNetworkBytes, long totalNetworkBytes);
    }

    /** Checks cancellation or session expiry before blocking work. */
    @FunctionalInterface
    interface ActivityCheck {
        void check() throws IOException, InterruptedException;
    }

    /** Validated bounded HTTP 206 byte chunk paired with its strong ETag. */
    record RangeChunk(byte[] bytes, String etag) {}

    /** Summary of a SHA-256 verified delta reconstruction. */
    record Result(long networkBytes) {}

    /** Parsed non-ZIP64 Central Directory metadata and sorted entry spans. */
    record ZipLayout(long cdOffset, long cdSize, List<EntrySpan> entries) {}

    /** Content and metadata identity of one ZIP entry span [localOffset, localOffset + spanLength). */
    record EntrySignature(
            String name,
            int flags,
            int method,
            int mtime,
            int mdate,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            long spanLength) {}

    /** Exact byte span of one ZIP entry inside an archive. */
    record EntrySpan(EntrySignature signature, long offset, long length) {}

    /** Planned copy/fetch sequence covering [0, cdOffset) of the target archive. */
    record DeltaPlan(List<Segment> segments, long remoteRangeBytes, int rangeRequestCount) {}

    /** One contiguous target byte region sourced either locally or over HTTP Range. */
    sealed interface Segment permits LocalCopy, RemoteRange {
        long targetOffset();

        long length();
    }

    record LocalCopy(long targetOffset, long localOffset, long length) implements Segment {}

    record RemoteRange(long targetOffset, long length) implements Segment {}

    /**
     * Attempts to assemble the target archive at {@code destination} using local entry reuse and
     * HTTP Range fetches.
     *
     * @return verified network transfer summary, or empty when callers must fall back to full download
     */
    static Optional<Result> assemble(
            final Path localArchive,
            final long remoteSize,
            final String expectedSha256,
            final Path destination,
            final RangeBytesFetcher rangeBytes,
            final RangeStreamFetcher rangeStream,
            final ProgressListener progress,
            final ActivityCheck activity)
            throws IOException, InterruptedException {
        Objects.requireNonNull(localArchive, "localArchive");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(rangeBytes, "rangeBytes");
        Objects.requireNonNull(rangeStream, "rangeStream");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(activity, "activity");

        if (remoteSize < MIN_DELTA_OBJECT_BYTES || remoteSize > UpdateManifest.MAX_FILE_BYTES) {
            return Optional.empty();
        }
        activity.check();
        final Optional<ZipLayout> localLayout = readLocalLayout(localArchive, activity);
        if (localLayout.isEmpty()) return Optional.empty();

        final long tailStart = Math.max(0L, remoteSize - EOCD_MAX_TAIL_BYTES);
        final Optional<RangeChunk> tailChunk = rangeBytes.fetch(tailStart, remoteSize - 1L, null);
        if (tailChunk.isEmpty()) return Optional.empty();
        final String etag = tailChunk.get().etag();
        final byte[] tailBytes = tailChunk.get().bytes();
        if (etag == null || tailBytes.length != remoteSize - tailStart) return Optional.empty();

        final Optional<Eocd> remoteEocd = parseEocd(tailBytes, remoteSize);
        if (remoteEocd.isEmpty()) return Optional.empty();
        final long cdOffset = remoteEocd.get().cdOffset();
        final long cdSize = remoteEocd.get().cdSize();
        final int suffixLength = Math.toIntExact(remoteSize - cdOffset);

        final byte[] suffixBytes;
        long fetchedNetworkBytes = tailBytes.length;
        if (cdOffset >= tailStart) {
            final int startInTail = Math.toIntExact(cdOffset - tailStart);
            suffixBytes = new byte[suffixLength];
            System.arraycopy(tailBytes, startInTail, suffixBytes, 0, suffixLength);
        } else {
            activity.check();
            final Optional<RangeChunk> cdHeadChunk = rangeBytes.fetch(cdOffset, tailStart - 1L, etag);
            if (cdHeadChunk.isEmpty()) return Optional.empty();
            final byte[] cdHead = cdHeadChunk.get().bytes();
            if (cdHead.length != tailStart - cdOffset) return Optional.empty();
            fetchedNetworkBytes += cdHead.length;
            suffixBytes = new byte[suffixLength];
            System.arraycopy(cdHead, 0, suffixBytes, 0, cdHead.length);
            System.arraycopy(tailBytes, 0, suffixBytes, cdHead.length, tailBytes.length);
        }

        final Optional<ZipLayout> remoteLayout = parseCentralDirectory(
                suffixBytes, cdOffset, cdSize, remoteEocd.get().entries());
        if (remoteLayout.isEmpty()) return Optional.empty();

        final Optional<DeltaPlan> maybePlan =
                planDelta(localLayout.get(), remoteLayout.get(), remoteSize, fetchedNetworkBytes);
        if (maybePlan.isEmpty()) return Optional.empty();
        final DeltaPlan plan = maybePlan.get();
        final long totalNetworkBytes = fetchedNetworkBytes + plan.remoteRangeBytes();
        progress.onPlan(totalNetworkBytes);
        progress.onNetworkProgress(fetchedNetworkBytes, totalNetworkBytes);

        final MessageDigest digest = Sha256.digest();
        final long[] networkReceived = {fetchedNetworkBytes};
        long written = 0L;
        try (FileChannel localChannel =
                        FileChannel.open(localArchive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                OutputStream output =
                        Files.newOutputStream(destination, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] copyBuffer = new byte[COPY_BUFFER_BYTES];
            for (final Segment segment : plan.segments()) {
                activity.check();
                if (segment.targetOffset() != written) return Optional.empty();
                if (segment instanceof LocalCopy copy) {
                    if (!copyLocalRange(
                            localChannel, copy.localOffset(), copy.length(), copyBuffer, output, digest, activity)) {
                        return Optional.empty();
                    }
                    written += copy.length();
                } else if (segment instanceof RemoteRange range) {
                    final long[] rangeWritten = {0L};
                    final boolean ok = rangeStream.stream(
                            range.targetOffset(), range.targetOffset() + range.length() - 1L, etag, (buf, off, len) -> {
                                activity.check();
                                output.write(buf, off, len);
                                digest.update(buf, off, len);
                                rangeWritten[0] += len;
                                networkReceived[0] += len;
                                progress.onNetworkProgress(
                                        Math.min(networkReceived[0], totalNetworkBytes), totalNetworkBytes);
                            });
                    if (!ok || rangeWritten[0] != range.length()) return Optional.empty();
                    written += range.length();
                }
            }
            activity.check();
            if (written != cdOffset) return Optional.empty();
            output.write(suffixBytes);
            digest.update(suffixBytes);
            written += suffixBytes.length;
        }
        if (written != remoteSize) return Optional.empty();
        if (!Sha256.hex(digest).equals(expectedSha256)) return Optional.empty();
        return Optional.of(new Result(totalNetworkBytes));
    }

    /** Parses the Central Directory of a verified local archive without reading entry payloads. */
    static Optional<ZipLayout> readLocalLayout(final Path localArchive, final ActivityCheck activity)
            throws IOException, InterruptedException {
        activity.check();
        final var attrs = FrameworkUpdateCache.attributes(localArchive);
        if (attrs == null
                || !attrs.isRegularFile()
                || attrs.isSymbolicLink()
                || attrs.isOther()
                || attrs.size() < EOCD_MIN_BYTES
                || attrs.size() > UpdateManifest.MAX_FILE_BYTES
                || !FrameworkUpdateCache.singleLink(localArchive)) {
            return Optional.empty();
        }
        final long localSize = attrs.size();
        final int tailLength = (int) Math.min(localSize, EOCD_MAX_TAIL_BYTES);
        try (FileChannel channel = FileChannel.open(localArchive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] tail = readFully(channel, localSize - tailLength, tailLength, activity);
            if (tail == null) return Optional.empty();
            final Optional<Eocd> eocd = parseEocd(tail, localSize);
            if (eocd.isEmpty()) return Optional.empty();
            final int cdLength = Math.toIntExact(eocd.get().cdSize());
            final byte[] cdBytes = readFully(channel, eocd.get().cdOffset(), cdLength, activity);
            if (cdBytes == null) return Optional.empty();
            return parseCentralDirectory(
                    cdBytes,
                    eocd.get().cdOffset(),
                    eocd.get().cdSize(),
                    eocd.get().entries());
        }
    }

    /** Computes a bounded sequence of local copies and coalesced remote HTTP Range fetches. */
    static Optional<DeltaPlan> planDelta(
            final ZipLayout localLayout,
            final ZipLayout remoteLayout,
            final long remoteSize,
            final long suffixNetworkBytes) {
        final Map<EntrySignature, Long> localOffsets =
                new HashMap<>(localLayout.entries().size() * 2);
        for (final EntrySpan span : localLayout.entries()) {
            localOffsets.putIfAbsent(span.signature(), span.offset());
        }

        final List<Segment> rawSegments = new ArrayList<>();
        long cursor = 0L;
        for (final EntrySpan entry : remoteLayout.entries()) {
            if (entry.offset() > cursor) {
                rawSegments.add(new RemoteRange(cursor, entry.offset() - cursor));
            }
            final Long localOffset = localOffsets.get(entry.signature());
            if (localOffset != null) {
                rawSegments.add(new LocalCopy(entry.offset(), localOffset, entry.length()));
            } else {
                rawSegments.add(new RemoteRange(entry.offset(), entry.length()));
            }
            cursor = entry.offset() + entry.length();
        }
        if (cursor < remoteLayout.cdOffset()) {
            rawSegments.add(new RemoteRange(cursor, remoteLayout.cdOffset() - cursor));
        }

        final List<Segment> merged = coalesceRemoteRanges(rawSegments, COALESCE_GAP_BYTES, MAX_RANGE_REQUESTS);
        long remoteRangeBytes = 0L;
        int rangeCount = 0;
        for (final Segment segment : merged) {
            if (segment instanceof RemoteRange range) {
                remoteRangeBytes += range.length();
                rangeCount++;
            }
        }
        final long totalNetworkBytes = suffixNetworkBytes + remoteRangeBytes;
        if (rangeCount > MAX_RANGE_REQUESTS
                || totalNetworkBytes * MAX_DELTA_RATIO_DENOMINATOR > remoteSize * MAX_DELTA_RATIO_NUMERATOR) {
            return Optional.empty();
        }
        return Optional.of(new DeltaPlan(List.copyOf(merged), remoteRangeBytes, rangeCount));
    }

    private static List<Segment> coalesceRemoteRanges(
            final List<Segment> segments, final long gapThreshold, final int maxRangeRequests) {
        final List<RemoteRange> ranges = new ArrayList<>();
        for (final Segment segment : segments) {
            if (segment instanceof RemoteRange range) {
                if (!ranges.isEmpty()) {
                    final RemoteRange previous = ranges.get(ranges.size() - 1);
                    final long gap = range.targetOffset() - (previous.targetOffset() + previous.length());
                    if (gap <= gapThreshold) {
                        ranges.set(
                                ranges.size() - 1,
                                new RemoteRange(
                                        previous.targetOffset(),
                                        range.targetOffset() + range.length() - previous.targetOffset()));
                        continue;
                    }
                }
                ranges.add(range);
            }
        }
        // Merge smallest gaps first when range count exceeds the per-file HTTP request ceiling.
        while (ranges.size() > maxRangeRequests) {
            int bestIndex = 0;
            long bestGap = Long.MAX_VALUE;
            for (int i = 0; i < ranges.size() - 1; i++) {
                final RemoteRange left = ranges.get(i);
                final RemoteRange right = ranges.get(i + 1);
                final long gap = right.targetOffset() - (left.targetOffset() + left.length());
                if (gap < bestGap) {
                    bestGap = gap;
                    bestIndex = i;
                }
            }
            final RemoteRange left = ranges.get(bestIndex);
            final RemoteRange right = ranges.remove(bestIndex + 1);
            ranges.set(
                    bestIndex,
                    new RemoteRange(left.targetOffset(), right.targetOffset() + right.length() - left.targetOffset()));
        }

        final List<Segment> result = new ArrayList<>();
        int rangeIndex = 0;
        for (final Segment segment : segments) {
            if (segment instanceof LocalCopy copy) {
                final long copyEnd = copy.targetOffset() + copy.length();
                while (rangeIndex < ranges.size()
                        && ranges.get(rangeIndex).targetOffset()
                                        + ranges.get(rangeIndex).length()
                                <= copy.targetOffset()) {
                    result.add(ranges.get(rangeIndex++));
                }
                if (rangeIndex < ranges.size()) {
                    final RemoteRange active = ranges.get(rangeIndex);
                    final long activeEnd = active.targetOffset() + active.length();
                    if (copy.targetOffset() >= active.targetOffset() && copyEnd <= activeEnd) {
                        continue;
                    }
                }
                result.add(copy);
            }
        }
        while (rangeIndex < ranges.size()) {
            result.add(ranges.get(rangeIndex++));
        }
        result.sort(Comparator.comparingLong(Segment::targetOffset));
        return result;
    }

    private record Eocd(int entries, long cdSize, long cdOffset) {}

    private static Optional<Eocd> parseEocd(final byte[] tail, final long fileSize) {
        if (tail.length < EOCD_MIN_BYTES) return Optional.empty();
        final ByteBuffer buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        for (int pos = tail.length - EOCD_MIN_BYTES; pos >= 0; pos--) {
            if (buf.getInt(pos) != EOCD_SIGNATURE) continue;
            final int commentLength = buf.getShort(pos + 20) & UNSIGNED_SHORT_MASK;
            if (pos + EOCD_MIN_BYTES + commentLength != tail.length) continue;
            final int diskNumber = buf.getShort(pos + 4) & UNSIGNED_SHORT_MASK;
            final int cdDisk = buf.getShort(pos + 6) & UNSIGNED_SHORT_MASK;
            final int diskEntries = buf.getShort(pos + 8) & UNSIGNED_SHORT_MASK;
            final int totalEntries = buf.getShort(pos + 10) & UNSIGNED_SHORT_MASK;
            final long cdSize = buf.getInt(pos + 12) & UNSIGNED_INT_MASK;
            final long cdOffset = buf.getInt(pos + 16) & UNSIGNED_INT_MASK;
            if (diskNumber != 0
                    || cdDisk != 0
                    || diskEntries != totalEntries
                    || totalEntries == 0
                    || totalEntries == ZIP64_SENTINEL_16
                    || totalEntries > MAX_ZIP_ENTRIES
                    || cdSize == 0L
                    || cdSize == ZIP64_SENTINEL_32
                    || cdSize > MAX_CENTRAL_DIRECTORY_BYTES
                    || cdOffset == ZIP64_SENTINEL_32
                    || cdOffset + cdSize > fileSize - EOCD_MIN_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new Eocd(totalEntries, cdSize, cdOffset));
        }
        return Optional.empty();
    }

    private static Optional<ZipLayout> parseCentralDirectory(
            final byte[] cdBytes, final long cdOffset, final long cdSize, final int expectedEntries) {
        if (cdSize > cdBytes.length) return Optional.empty();
        final ByteBuffer buf =
                ByteBuffer.wrap(cdBytes, 0, Math.toIntExact(cdSize)).order(ByteOrder.LITTLE_ENDIAN);
        final List<RawEntry> raw = new ArrayList<>(expectedEntries);
        int pos = 0;
        final int limit = Math.toIntExact(cdSize);
        while (pos < limit) {
            if (limit - pos < CD_FIXED_HEADER_BYTES || buf.getInt(pos) != CD_SIGNATURE) {
                return Optional.empty();
            }
            final int flags = buf.getShort(pos + 8) & UNSIGNED_SHORT_MASK;
            final int method = buf.getShort(pos + 10) & UNSIGNED_SHORT_MASK;
            final int mtime = buf.getShort(pos + 12) & UNSIGNED_SHORT_MASK;
            final int mdate = buf.getShort(pos + 14) & UNSIGNED_SHORT_MASK;
            final long crc32 = buf.getInt(pos + 16) & UNSIGNED_INT_MASK;
            final long compressedSize = buf.getInt(pos + 20) & UNSIGNED_INT_MASK;
            final long uncompressedSize = buf.getInt(pos + 24) & UNSIGNED_INT_MASK;
            final int nameLen = buf.getShort(pos + 28) & UNSIGNED_SHORT_MASK;
            final int extraLen = buf.getShort(pos + 30) & UNSIGNED_SHORT_MASK;
            final int commentLen = buf.getShort(pos + 32) & UNSIGNED_SHORT_MASK;
            final long localOffset = buf.getInt(pos + 42) & UNSIGNED_INT_MASK;
            if (compressedSize == ZIP64_SENTINEL_32
                    || uncompressedSize == ZIP64_SENTINEL_32
                    || localOffset == ZIP64_SENTINEL_32
                    || localOffset >= cdOffset) {
                return Optional.empty();
            }
            final int next = pos + CD_FIXED_HEADER_BYTES + nameLen + extraLen + commentLen;
            if (next > limit) return Optional.empty();
            final String name = new String(cdBytes, pos + CD_FIXED_HEADER_BYTES, nameLen, StandardCharsets.UTF_8);
            raw.add(new RawEntry(
                    name, flags, method, mtime, mdate, crc32, compressedSize, uncompressedSize, localOffset));
            pos = next;
        }
        if (raw.size() != expectedEntries) return Optional.empty();
        raw.sort(Comparator.comparingLong(RawEntry::localOffset));
        final List<EntrySpan> spans = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            final RawEntry current = raw.get(i);
            final long endOffset = (i + 1 < raw.size()) ? raw.get(i + 1).localOffset() : cdOffset;
            final long spanLength = endOffset - current.localOffset();
            if (spanLength <= 0L) return Optional.empty();
            final EntrySignature signature = new EntrySignature(
                    current.name(),
                    current.flags(),
                    current.method(),
                    current.mtime(),
                    current.mdate(),
                    current.crc32(),
                    current.compressedSize(),
                    current.uncompressedSize(),
                    spanLength);
            spans.add(new EntrySpan(signature, current.localOffset(), spanLength));
        }
        return Optional.of(new ZipLayout(cdOffset, cdSize, List.copyOf(spans)));
    }

    private record RawEntry(
            String name,
            int flags,
            int method,
            int mtime,
            int mdate,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            long localOffset) {}

    private static byte[] readFully(
            final FileChannel channel, final long offset, final int length, final ActivityCheck activity)
            throws IOException, InterruptedException {
        final byte[] bytes = new byte[length];
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long pos = offset;
        while (buffer.hasRemaining()) {
            activity.check();
            final int read = channel.read(buffer, pos);
            if (read < 0) return null;
            pos += read;
        }
        return bytes;
    }

    private static boolean copyLocalRange(
            final FileChannel channel,
            final long offset,
            final long length,
            final byte[] buffer,
            final OutputStream output,
            final MessageDigest digest,
            final ActivityCheck activity)
            throws IOException, InterruptedException {
        long remaining = length;
        long pos = offset;
        while (remaining > 0L) {
            activity.check();
            final int toRead = (int) Math.min(remaining, buffer.length);
            final ByteBuffer slice = ByteBuffer.wrap(buffer, 0, toRead);
            while (slice.hasRemaining()) {
                final int read = channel.read(slice, pos);
                if (read < 0) return false;
                pos += read;
            }
            output.write(buffer, 0, toRead);
            digest.update(buffer, 0, toRead);
            remaining -= toRead;
        }
        return true;
    }

    /** Utility used by callers to validate bounded streaming reads from an open HTTP response. */
    static boolean copyStreamExact(
            final InputStream input,
            final long expectedLength,
            final long requestDeadlineNanos,
            final ChunkSink sink,
            final ActivityCheck activity)
            throws IOException, InterruptedException {
        final byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long count = 0L;
        while (true) {
            activity.check();
            final int read = input.read(buffer);
            if (read == -1) break;
            activity.check();
            if (System.nanoTime() >= requestDeadlineNanos) {
                throw new IOException("Resource request deadline exceeded");
            }
            if (read > expectedLength - count) {
                throw new IOException("Resource exceeds expected length");
            }
            sink.write(buffer, 0, read);
            count += read;
        }
        return count == expectedLength;
    }
}
