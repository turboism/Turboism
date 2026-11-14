package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32C;

/** Reuses measured host fingerprints only while their canonical file identity is unchanged. */
public final class HostArtifactDigestCache {
    private static final HostArtifactDigestCache SHARED = new HostArtifactDigestCache();

    private final Map<Path, Entry> entries = new HashMap<>();
    private final DigestReader reader;

    /** Creates an independent cache that measures artifacts with {@link HostArtifactDigest#from}. */
    public HostArtifactDigestCache() {
        this(HostArtifactDigest::from);
    }

    /**
     * Creates an independent cache that delegates every measurement to {@code reader}.
     *
     * @param reader measurement strategy, such as a counting or delaying test double
     */
    public HostArtifactDigestCache(final DigestReader reader) {
        this.reader = reader;
    }

    /**
     * Returns the process-wide cache, so one launch measures each host artifact once.
     *
     * @return the cache shared by every hook installer and host profile lookup
     */
    public static HostArtifactDigestCache shared() {
        return SHARED;
    }

    /**
     * Returns the digest of a host artifact, and measures the file only when its identity changed.
     *
     * @param artifact file to digest
     * @return its byte length and lowercase hex SHA-256
     * @throws IOException if the file cannot be read, or if it changes during the measurement
     */
    public synchronized HostArtifactDigest digest(final Path artifact) throws IOException {
        final Path path = artifact.toRealPath();
        final FileStamp before = FileStamp.from(path);
        final Entry cached = entries.get(path);
        if (cached != null && cached.stamp().equals(before)) {
            return cached.digest();
        }
        entries.remove(path);
        final HostArtifactDigest digest = reader.read(path);
        if (digest.size() != before.size() || !before.equals(FileStamp.from(path))) {
            throw new IOException("host artifact changed during fingerprinting");
        }
        entries.put(path, new Entry(before, digest));
        return digest;
    }

    /** Measures one artifact; tests replace it to count or delay measurements. */
    @FunctionalInterface
    public interface DigestReader {
        /**
         * Measures the artifact at {@code artifact}.
         *
         * @param artifact file to digest
         * @return its byte length and lowercase hex SHA-256
         * @throws IOException if the file cannot be read
         */
        HostArtifactDigest read(Path artifact) throws IOException;
    }

    private record Entry(FileStamp stamp, HostArtifactDigest digest) {}

    private record FileStamp(
            long size, FileTime modified, FileTime created, Object fileKey, FileTime changed, Long contentSample) {
        private static final long FULL_CONTENT_LIMIT = 8192;
        private static final int EDGE_SAMPLE_BYTES = 4096;

        static FileStamp from(final Path path) throws IOException {
            final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            // Unix change time also detects in-place rewrites that restore the modification time.
            final FileTime changed =
                    path.getFileSystem().supportedFileAttributeViews().contains("unix")
                            ? (FileTime) Files.getAttribute(path, "unix:ctime")
                            : null;
            // Windows NTFS exposes neither an inode nor ctime, and File System Tunneling lets a
            // move-replaced file inherit the target's creation time, so identical size and
            // timestamps can hide a different artifact behind the same path. A cheap content
            // sample keeps the cache honest without consuming the full digest read.
            final Long contentSample =
                    attributes.fileKey() == null && changed == null ? sampleContent(path, attributes.size()) : null;
            return new FileStamp(
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.creationTime(),
                    attributes.fileKey(),
                    changed,
                    contentSample);
        }

        private static long sampleContent(final Path path, final long size) throws IOException {
            final CRC32C checksum = new CRC32C();
            if (size <= FULL_CONTENT_LIMIT) {
                checksum.update(Files.readAllBytes(path));
                return checksum.getValue();
            }
            final ByteBuffer sample = ByteBuffer.allocate(EDGE_SAMPLE_BYTES);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                readFully(channel, sample, 0);
                checksum.update(sample.flip());
                sample.clear();
                readFully(channel, sample, size - EDGE_SAMPLE_BYTES);
                checksum.update(sample.flip());
            }
            return checksum.getValue();
        }

        private static void readFully(final FileChannel channel, final ByteBuffer buffer, final long position)
                throws IOException {
            long offset = position;
            while (buffer.hasRemaining()) {
                final int read = channel.read(buffer, offset);
                if (read < 0) {
                    throw new IOException("host artifact changed during fingerprinting");
                }
                offset += read;
            }
        }
    }
}
