package dev.turboism.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict, random-access ZIP structural validation followed by bounded streaming reads. */
final class StrictZipArchive implements AutoCloseable {
    private static final long EOCD = 0x06054b50L, CENTRAL = 0x02014b50L;
    private static final long LOCAL = 0x04034b50L, DESCRIPTOR = 0x08074b50L;
    private static final int UTF8 = 0x0800, DATA_DESCRIPTOR = 0x0008;
    private final Path path;
    private final ZipFile zip;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;
    private final Limits limits;

    static StrictZipArchive open(Path path, Limits limits) throws Exception {
        return new StrictZipArchive(path, limits);
    }

    private StrictZipArchive(Path path, Limits limits) throws Exception {
        this.path = path;
        this.limits = limits;
        List<Entry> parsed = parse(path, limits);
        ZipFile opened = null;
        try {
            opened = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
            this.zip = opened;
            this.entries = List.copyOf(parsed);
            Map<String, Entry> index = new HashMap<>();
            for (Entry entry : entries) index.put(entry.name(), entry);
            this.byName = Map.copyOf(index);
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
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = stream(entry)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                if (size > limits.entryMax() - read) invalid("ARCHIVE_ENTRY_TOO_LARGE", entry.name());
                size += read;
                crc.update(buffer, 0, read);
                if (target != null) target.write(buffer, 0, read);
            }
        } catch (DistributionValidationException exception) { throw exception; }
        catch (IOException exception) { invalid("ARCHIVE_STREAM_INVALID", entry.name()); }
        if (size != entry.expanded() || crc.getValue() != entry.crc()) {
            invalid("ARCHIVE_CRC_SIZE_MISMATCH", entry.name());
        }
        return new Observation(size, crc.getValue());
    }

    @Override public void close() throws IOException { zip.close(); }

    private static List<Entry> parse(Path path, Limits limits) throws Exception {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long length = channel.size();
            if (length < 22 || length > limits.rawMax()) invalid("PACKAGE_TOO_LARGE", path.toString());
            byte[] eocd = read(channel, length - 22, 22);
            valid(uint(eocd, 0) == EOCD && ushort(eocd, 20) == 0, "ARCHIVE_EOCD_INVALID", path.toString());
            int count = ushort(eocd, 10);
            long centralSize = uint(eocd, 12), centralOffset = uint(eocd, 16);
            valid(ushort(eocd, 4) == 0 && ushort(eocd, 6) == 0 && ushort(eocd, 8) == count,
                "ARCHIVE_MULTI_DISK", path.toString());
            valid(count != 0xffff && centralSize != 0xffffffffL && centralOffset != 0xffffffffL,
                "ARCHIVE_ZIP64_UNSUPPORTED", path.toString());
            valid(count <= limits.countMax() && centralOffset + centralSize == length - 22,
                count > limits.countMax() ? "ARCHIVE_ENTRY_LIMIT" : "ARCHIVE_TRAILING_OR_GAP", path.toString());
            List<Entry> entries = central(channel, centralOffset, centralSize, count, limits);
            locals(channel, entries, centralOffset);
            return entries;
        } catch (DistributionValidationException exception) { throw exception; }
        catch (IOException exception) { throw ArchivePolicy.problem("ARCHIVE_INVALID", "Invalid ZIP archive", path.toString()); }
    }

    private static List<Entry> central(FileChannel channel, long offset, long size, int count,
                                       Limits limits) throws Exception {
        long cursor = offset, end = offset + size, total = 0, compressedTotal = 0;
        List<Entry> result = new ArrayList<>(count);
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < count; index++) {
            byte[] header = read(channel, cursor, 46);
            valid(uint(header, 0) == CENTRAL, "ARCHIVE_CENTRAL_INVALID", "archive");
            int madeBy = ushort(header, 4), flags = ushort(header, 8), method = ushort(header, 10);
            long crc = uint(header, 16), compressed = uint(header, 20), expanded = uint(header, 24);
            int nameLength = ushort(header, 28), extraLength = ushort(header, 30);
            int commentLength = ushort(header, 32), disk = ushort(header, 34);
            long external = uint(header, 38), localOffset = uint(header, 42);
            valid(compressed != 0xffffffffL && expanded != 0xffffffffL && localOffset != 0xffffffffL,
                "ARCHIVE_ZIP64_UNSUPPORTED", "archive");
            valid(disk == 0 && commentLength == 0, commentLength == 0 ? "ARCHIVE_MULTI_DISK" : "ARCHIVE_COMMENT_UNSUPPORTED", "archive");
            valid(supportedFlags(flags) && (method == 0 || method == 8), "ARCHIVE_ENTRY_UNSUPPORTED", "archive");
            byte[] variable = read(channel, cursor + 46, nameLength + extraLength + commentLength);
            rejectZip64Extra(variable, nameLength, extraLength);
            String name = decode(variable, 0, nameLength);
            boolean directory = name.endsWith("/");
            validateType(madeBy >>> 8, external, directory, name);
            PluginPathPolicy.validate(name, directory);
            valid(identities.add(ManifestPrimitives.pathIdentityKey(name)), "ARCHIVE_PATH_COLLISION", name);
            valid(expanded <= limits.entryMax(), "ARCHIVE_ENTRY_TOO_LARGE", name);
            valid(total <= limits.totalMax() - expanded, "ARCHIVE_TOTAL_TOO_LARGE", name);
            total += expanded;
            compressedTotal += compressed;
            ratio(expanded, compressed, limits.ratioMax(), name);
            result.add(new Entry(name, directory, flags, method, crc, compressed, expanded, localOffset));
            cursor += 46L + nameLength + extraLength + commentLength;
        }
        valid(cursor == end, "ARCHIVE_CENTRAL_INVALID", "archive");
        ratio(total, compressedTotal, limits.ratioMax(), "archive");
        PluginPathPolicy.validateCollisions(result.stream().map(Entry::name).toList());
        return result;
    }

    private static void locals(FileChannel channel, List<Entry> entries, long centralOffset) throws Exception {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(Entry::localOffset));
        long expected = 0;
        for (int index = 0; index < ordered.size(); index++) {
            Entry entry = ordered.get(index);
            valid(entry.localOffset() == expected, "ARCHIVE_GAP_OR_OVERLAP", entry.name());
            byte[] header = read(channel, expected, 30);
            valid(uint(header, 0) == LOCAL, "ARCHIVE_LOCAL_INVALID", entry.name());
            int flags = ushort(header, 6), method = ushort(header, 8);
            int nameLength = ushort(header, 26), extraLength = ushort(header, 28);
            byte[] variable = read(channel, expected + 30, nameLength + extraLength);
            rejectZip64Extra(variable, nameLength, extraLength);
            valid(flags == entry.flags() && method == entry.method()
                && entry.name().equals(decode(variable, 0, nameLength)), "ARCHIVE_LOCAL_CENTRAL_MISMATCH", entry.name());
            long dataEnd = expected + 30L + nameLength + extraLength + entry.compressed();
            long next = index + 1 < ordered.size() ? ordered.get(index + 1).localOffset() : centralOffset;
            if ((flags & DATA_DESCRIPTOR) == 0) {
                valid(uint(header, 14) == entry.crc() && uint(header, 18) == entry.compressed()
                    && uint(header, 22) == entry.expanded() && dataEnd == next,
                    "ARCHIVE_LOCAL_CENTRAL_MISMATCH", entry.name());
            } else validateDescriptor(channel, dataEnd, next, entry);
            expected = next;
        }
        valid(expected == centralOffset, "ARCHIVE_GAP_OR_OVERLAP", "archive");
    }

    private static void validateDescriptor(FileChannel channel, long at, long next, Entry entry) throws Exception {
        long length = next - at;
        valid(length == 12 || length == 16, "ARCHIVE_DESCRIPTOR_INVALID", entry.name());
        byte[] descriptor = read(channel, at, (int) length);
        int values = 0;
        if (length == 16) { valid(uint(descriptor, 0) == DESCRIPTOR, "ARCHIVE_DESCRIPTOR_INVALID", entry.name()); values = 4; }
        valid(uint(descriptor, values) == entry.crc() && uint(descriptor, values + 4) == entry.compressed()
            && uint(descriptor, values + 8) == entry.expanded(), "ARCHIVE_DESCRIPTOR_INVALID", entry.name());
    }

    private static void validateType(int platform, long external, boolean directory, String name) throws Exception {
        if (platform == 3) {
            int type = (int) ((external >>> 16) & 0170000);
            int expected = directory ? 0040000 : 0100000;
            valid(type == expected, "ARCHIVE_ENTRY_TYPE_UNSAFE", name);
        } else {
            valid(platform == 0 && (external & 0x10) == (directory ? 0x10 : 0), "ARCHIVE_ENTRY_TYPE_UNSAFE", name);
        }
    }

    private static void rejectZip64Extra(byte[] bytes, int nameLength, int extraLength) throws Exception {
        int cursor = nameLength, end = nameLength + extraLength;
        while (cursor < end) {
            valid(cursor + 4 <= end, "ARCHIVE_EXTRA_INVALID", "archive");
            int id = ushort(bytes, cursor), length = ushort(bytes, cursor + 2);
            valid(cursor + 4 + length <= end && id != 0x0001, id == 0x0001 ? "ARCHIVE_ZIP64_UNSUPPORTED" : "ARCHIVE_EXTRA_INVALID", "archive");
            cursor += 4 + length;
        }
    }

    private static boolean supportedFlags(int flags) {
        int allowed = UTF8 | DATA_DESCRIPTOR;
        return (flags & UTF8) != 0 && (flags & ~allowed) == 0;
    }

    private static void ratio(long expanded, long compressed, double maximum, String path) throws Exception {
        boolean bad = expanded > 0 && compressed == 0;
        bad |= compressed > 0 && (double) expanded / compressed > maximum;
        valid(!bad, "ARCHIVE_COMPRESSION_RATIO", path);
    }

    private static String decode(byte[] bytes, int offset, int length) throws Exception {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException exception) { invalid("ARCHIVE_PATH_UTF8_INVALID", "archive"); return ""; }
    }

    private static byte[] read(FileChannel channel, long offset, int length) throws Exception {
        if (offset < 0 || length < 0 || offset > channel.size() - length) invalid("ARCHIVE_TRUNCATED", "archive");
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) if (channel.read(buffer, offset + buffer.position()) < 0) invalid("ARCHIVE_TRUNCATED", "archive");
        return buffer.array();
    }

    private static int ushort(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    private static long uint(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
            | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    private static void valid(boolean condition, String code, String path) throws Exception {
        if (!condition) invalid(code, path);
    }

    private static void invalid(String code, String path) throws DistributionValidationException {
        throw ArchivePolicy.problem(code, "Invalid strict ZIP archive", path);
    }

    record Entry(String name, boolean directory, int flags, int method, long crc,
                 long compressed, long expanded, long localOffset) {}
    record Observation(long size, long crc) {}
    record Limits(long rawMax, long entryMax, long totalMax, int countMax, double ratioMax) {}
}
