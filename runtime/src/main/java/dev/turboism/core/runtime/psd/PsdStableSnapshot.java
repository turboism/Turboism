package dev.turboism.core.runtime.psd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bounded worker-side staging for a runtime-allocated PSD, not a public path grant.
 *
 * <p>One streamed copy is followed by an independent digest read of the live file. Metadata and
 * digests must agree. This detects ordinary concurrent saves, but is not a filesystem lock or a
 * proof against hostile same-user path races. The coordinator must still debounce, authorize,
 * parse, and revalidate the stage before native admission. Failed and superseded stages remain
 * in the OS temporary directory; this class never deletes them.</p>
 */
final class PsdStableSnapshot {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;

    private PsdStableSnapshot() { }

    static Snapshot capture(final PsdTemporaryFile allocation) throws IOException {
        return capture(allocation, MAX_BYTES);
    }

    /** Smaller bounds support tests; callers cannot increase the approved workflow limit. */
    static Snapshot capture(final PsdTemporaryFile allocation, final long limit) throws IOException {
        Objects.requireNonNull(allocation, "allocation");
        if (limit <= 0 || limit > MAX_BYTES) throw new IllegalArgumentException("invalid PSD snapshot limit");
        final Path live = allocation.validatedPath();
        final BasicFileAttributes before = inspect(live, limit);
        final Path stage = Files.createTempFile(live.getParent(), "revision-", ".psd");
        // Revalidate the allocation after stage allocation, before opening either data stream.
        allocation.validatedPath();
        final Digest copied;
        try (FileChannel output = FileChannel.open(stage, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            copied = read(live, output, limit);
            output.force(true);
        }
        final BasicFileAttributes between = inspect(allocation.validatedPath(), limit);
        requireSame(before, between);
        final Digest checked = read(live, null, limit);
        final BasicFileAttributes after = inspect(allocation.validatedPath(), limit);
        requireSame(before, after);
        if (!copied.equals(checked) || copied.size() != before.size()) {
            throw new IOException("PSD changed while staging; retry before any native mutation");
        }
        final BasicFileAttributes staged = inspect(stage, limit);
        if (staged.size() != copied.size() || !stage.toRealPath().equals(stage)) {
            throw new IOException("PSD stage identity or size changed");
        }
        return new Snapshot(stage, copied.sha256(), copied.size());
    }

    private static Digest read(final Path path, final FileChannel output, final long limit) throws IOException {
        final MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
        final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
        long total = 0;
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                total += count;
                if (total > limit) throw new IOException("PSD exceeds snapshot limit");
                buffer.flip();
                hash.update(buffer.asReadOnlyBuffer());
                if (output != null) {
                    while (buffer.hasRemaining()) output.write(buffer);
                }
                buffer.clear();
            }
        }
        return new Digest(HexFormat.of().formatHex(hash.digest()), total);
    }

    private static BasicFileAttributes inspect(final Path path, final long limit) throws IOException {
        final BasicFileAttributes value = Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!value.isRegularFile() || value.size() <= 0 || value.size() > limit) {
            throw new IOException("PSD snapshot input must be a non-empty bounded regular file");
        }
        return value;
    }

    private static void requireSame(final BasicFileAttributes first, final BasicFileAttributes last)
        throws IOException {
        if (!Objects.equals(first.fileKey(), last.fileKey()) || first.size() != last.size()
            || !first.lastModifiedTime().equals(last.lastModifiedTime())) {
            throw new IOException("PSD changed while staging; retry before any native mutation");
        }
    }

    /** Runtime-only artifact; no PSD validity, authorization or applied outcome is implied. */
    record Snapshot(Path path, String sha256, long size) { }

    private record Digest(String sha256, long size) { }
}
