package dev.turboism.core.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Strict, random-access ZIP structural validation followed by bounded streaming reads.
 *
 * <p>{@link StrictZipParser} proves the archive's structure against its central
 * directory (EOCD closure, CEN↔LOC consistency, unique normalized paths, declared
 * size/ratio bounds); {@link #consume} then re-verifies every extracted byte — counted
 * against the caller's per-entry bound and CRC-checked against the central record, so
 * neither a forged size nor corrupt payload survives. The central-directory view matches
 * what {@link ZipFile}/{@link java.net.URLClassLoader} see when the same bytes are
 * opened for loading.</p>
 *
 * <p>Two input shapes share the same parse and consume core: a {@code Path} (parsed over
 * a read-only channel, decompressed through {@link ZipFile} purely as a random-access
 * source) and an in-memory {@code byte[]} (parsed over a {@link ByteArrayChannel},
 * decompressed through a bounded data-window {@link InflaterInputStream} that can never
 * read outside the entry's declared compressed region).</p>
 */
public final class StrictZipArchive implements AutoCloseable {
    private final Source source;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;
    private final Limits limits;

    /** Strictly parses and opens a file-backed archive. */
    public static StrictZipArchive open(
        final Path path,
        final Limits limits,
        final ArchivePathPolicy policy
    ) throws Exception {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            final List<Entry> parsed =
                StrictZipParser.parse(channel, path.toString(), limits, policy);
            return new StrictZipArchive(new ZipFileSource(path), parsed, limits);
        }
    }

    /**
     * Strictly parses an in-memory archive image. The bytes are treated as the complete
     * archive: trailing data, a missing EOCD, or a central/local disagreement all reject
     * exactly as they do for the file backend.
     */
    public static StrictZipArchive open(
        final byte[] bytes,
        final Limits limits,
        final ArchivePathPolicy policy
    ) throws Exception {
        final List<Entry> parsed = StrictZipParser.parse(
            new ByteArrayChannel(bytes), "archive", limits, policy);
        return new StrictZipArchive(new ByteArraySource(bytes), parsed, limits);
    }

    private StrictZipArchive(
        final Source source,
        final List<Entry> parsed,
        final Limits limits
    ) throws Exception {
        this.limits = limits;
        try {
            source.open();
            this.source = source;
            entries = List.copyOf(parsed);
            final Map<String, Entry> index = new HashMap<>();
            for (final Entry entry : entries) {
                index.put(entry.name(), entry);
            }
            byName = Map.copyOf(index);
        } catch (final Exception failure) {
            try {
                source.close();
            } catch (final IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    /** @return every central-directory entry in archive order */
    public List<Entry> entries() {
        return entries;
    }

    /** @return the entry recorded under {@code name} in the central directory */
    public Entry entry(final String name) {
        return byName.get(name);
    }

    /**
     * Opens a raw decompression stream over the entry's data window. The stream itself
     * does not enforce bounds — integrity and limits are only proved by
     * {@link #consume}, which is the supported extraction path.
     */
    public InputStream stream(final Entry entry) throws IOException {
        return source.stream(entry);
    }

    /**
     * Streams the entry through {@code target} while counting expanded bytes and
     * recomputing the CRC; the read aborts at the per-entry bound and the entry is only
     * accepted when the counted size and CRC match the central record exactly.
     */
    public Observation consume(final Entry entry, final OutputStream target)
            throws Exception {
        final CRC32 crc = new CRC32();
        final long size = stream(entry, target, crc);
        if (size != entry.expanded() || crc.getValue() != entry.crc()) {
            StrictZipSupport.invalid("ARCHIVE_CRC_SIZE_MISMATCH", entry.name());
        }
        return new Observation(size, crc.getValue());
    }

    private long stream(final Entry entry, final OutputStream target, final CRC32 crc)
            throws Exception {
        long size = 0;
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream input = stream(entry)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                if (size > entry.expanded() - read) {
                    // The entry over-delivers beyond its central record — the same
                    // CRC/size verdict the post-read check would reach, earlier.
                    StrictZipSupport.invalid("ARCHIVE_CRC_SIZE_MISMATCH", entry.name());
                }
                if (size > limits.entryMax() - read) {
                    StrictZipSupport.invalid("ARCHIVE_ENTRY_TOO_LARGE", entry.name());
                }
                size += read;
                crc.update(buffer, 0, read);
                if (target != null) target.write(buffer, 0, read);
            }
        } catch (final ArchiveStructureException exception) {
            throw exception;
        } catch (final IOException exception) {
            StrictZipSupport.invalid("ARCHIVE_STREAM_INVALID", entry.name());
        }
        return size;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    /**
     * One central-directory record, already cross-checked against its local header.
     * {@code dataOffset} is where the entry's compressed data begins.
     */
    public record Entry(
        String name,
        boolean directory,
        int flags,
        int method,
        long crc,
        long compressed,
        long expanded,
        long localOffset,
        long dataOffset
    ) {}

    public record Observation(long size, long crc) {}

    public record Limits(
        long rawMax,
        long entryMax,
        long totalMax,
        int countMax,
        double ratioMax
    ) {}

    /** Decompression backend: opens raw streams over a parsed entry's data region. */
    private interface Source extends AutoCloseable {
        void open() throws IOException;
        InputStream stream(Entry entry) throws IOException;
        @Override void close() throws IOException;
    }

    /**
     * File-backed source: {@link ZipFile} is used only as a random-access decompression
     * window. It verifies nothing — structure came from the parser, integrity comes
     * from {@link #consume}'s own counting and CRC.
     */
    private static final class ZipFileSource implements Source {
        private final Path path;
        private ZipFile zip;

        ZipFileSource(final Path path) {
            this.path = path;
        }

        @Override
        public void open() throws IOException {
            zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
        }

        @Override
        public InputStream stream(final Entry entry) throws IOException {
            final ZipEntry actual = zip.getEntry(entry.name());
            if (actual == null) throw new IOException("ZIP entry disappeared");
            return zip.getInputStream(actual);
        }

        @Override
        public void close() throws IOException {
            if (zip != null) zip.close();
        }
    }

    /**
     * In-memory source: each entry is decompressed through a bounded window that can
     * only see the entry's declared compressed region of the container bytes.
     */
    private static final class ByteArraySource implements Source {
        private final byte[] bytes;

        ByteArraySource(final byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public void open() {}

        @Override
        public InputStream stream(final Entry entry) throws IOException {
            final ByteArrayInputStream window = new ByteArrayInputStream(
                bytes, Math.toIntExact(entry.dataOffset()),
                Math.toIntExact(entry.compressed()));
            if (entry.method() == 0) {
                return window;
            }
            return new BoundedInflaterStream(window);
        }

        @Override
        public void close() {}
    }

    /** {@link InflaterInputStream} that also releases its inflater on close. */
    private static final class BoundedInflaterStream extends InflaterInputStream {
        private BoundedInflaterStream(final InputStream window) {
            super(window, new Inflater(true));
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                inf.end();
            }
        }
    }
}
