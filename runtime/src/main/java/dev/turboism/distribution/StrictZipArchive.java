package dev.turboism.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict, random-access ZIP structural validation followed by bounded streaming reads. */
final class StrictZipArchive implements AutoCloseable {
    private final ZipFile zip;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;
    private final Limits limits;

    static StrictZipArchive open(Path path, Limits limits) throws Exception {
        return new StrictZipArchive(path, limits);
    }

    private StrictZipArchive(Path path, Limits limits) throws Exception {
        this.limits = limits;
        List<Entry> parsed = StrictZipParser.parse(path, limits);
        ZipFile opened = null;
        try {
            opened = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
            zip = opened;
            entries = List.copyOf(parsed);
            Map<String, Entry> index = new HashMap<>();
            for (Entry entry : entries) index.put(entry.name(), entry);
            byName = Map.copyOf(index);
        } catch (Exception exception) {
            if (opened != null) opened.close();
            throw exception;
        }
    }

    List<Entry> entries() { return entries; }
    Entry entry(String name) { return byName.get(name); }

    InputStream stream(Entry entry) throws IOException {
        ZipEntry actual = zip.getEntry(entry.name());
        if (actual == null) throw new IOException("ZIP entry disappeared");
        return zip.getInputStream(actual);
    }

    Observation consume(Entry entry, java.io.OutputStream target) throws Exception {
        CRC32 crc = new CRC32();
        long size = stream(entry, target, crc);
        if (size != entry.expanded() || crc.getValue() != entry.crc()) {
            StrictZipSupport.invalid("ARCHIVE_CRC_SIZE_MISMATCH", entry.name());
        }
        return new Observation(size, crc.getValue());
    }

    private long stream(Entry entry, java.io.OutputStream target, CRC32 crc) throws Exception {
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = stream(entry)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                if (size > limits.entryMax() - read) {
                    StrictZipSupport.invalid("ARCHIVE_ENTRY_TOO_LARGE", entry.name());
                }
                size += read;
                crc.update(buffer, 0, read);
                if (target != null) target.write(buffer, 0, read);
            }
        } catch (DistributionValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            StrictZipSupport.invalid("ARCHIVE_STREAM_INVALID", entry.name());
        }
        return size;
    }

    @Override public void close() throws IOException { zip.close(); }

    record Entry(String name, boolean directory, int flags, int method, long crc,
                 long compressed, long expanded, long localOffset) {}
    record Observation(long size, long crc) {}
    record Limits(long rawMax, long entryMax, long totalMax, int countMax, double ratioMax) {}
}
