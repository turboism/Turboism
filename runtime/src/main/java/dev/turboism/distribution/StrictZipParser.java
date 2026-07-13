package dev.turboism.distribution;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.turboism.distribution.StrictZipSupport.CENTRAL;
import static dev.turboism.distribution.StrictZipSupport.DATA_DESCRIPTOR;
import static dev.turboism.distribution.StrictZipSupport.DESCRIPTOR;
import static dev.turboism.distribution.StrictZipSupport.EOCD;
import static dev.turboism.distribution.StrictZipSupport.LOCAL;
import static dev.turboism.distribution.StrictZipSupport.UTF8;
import static dev.turboism.distribution.StrictZipSupport.decode;
import static dev.turboism.distribution.StrictZipSupport.read;
import static dev.turboism.distribution.StrictZipSupport.uint;
import static dev.turboism.distribution.StrictZipSupport.ushort;
import static dev.turboism.distribution.StrictZipSupport.valid;

final class StrictZipParser {
    private StrictZipParser() {}

    static List<StrictZipArchive.Entry> parse(Path path, StrictZipArchive.Limits limits) throws Exception {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            End end = end(channel, path, limits);
            List<StrictZipArchive.Entry> entries = central(channel, end, limits);
            locals(channel, entries, end.centralOffset());
            return entries;
        } catch (DistributionValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ArchivePolicy.problem("ARCHIVE_INVALID", "Invalid ZIP archive", path.toString());
        }
    }

    private static End end(FileChannel channel, Path path, StrictZipArchive.Limits limits) throws Exception {
        long length = channel.size();
        if (length < 22 || length > limits.rawMax()) {
            StrictZipSupport.invalid("PACKAGE_TOO_LARGE", path.toString());
        }
        byte[] eocd = read(channel, length - 22, 22);
        valid(uint(eocd, 0) == EOCD && ushort(eocd, 20) == 0,
            "ARCHIVE_EOCD_INVALID", path.toString());
        int count = ushort(eocd, 10);
        long centralSize = uint(eocd, 12);
        long centralOffset = uint(eocd, 16);
        valid(ushort(eocd, 4) == 0 && ushort(eocd, 6) == 0 && ushort(eocd, 8) == count,
            "ARCHIVE_MULTI_DISK", path.toString());
        valid(count != 0xffff && centralSize != 0xffffffffL && centralOffset != 0xffffffffL,
            "ARCHIVE_ZIP64_UNSUPPORTED", path.toString());
        String code = count > limits.countMax() ? "ARCHIVE_ENTRY_LIMIT" : "ARCHIVE_TRAILING_OR_GAP";
        valid(count <= limits.countMax() && centralOffset + centralSize == length - 22,
            code, path.toString());
        return new End(centralOffset, centralSize, count);
    }

    private static List<StrictZipArchive.Entry> central(FileChannel channel, End end,
            StrictZipArchive.Limits limits) throws Exception {
        long cursor = end.centralOffset();
        long total = 0;
        long compressedTotal = 0;
        List<StrictZipArchive.Entry> result = new ArrayList<>(end.count());
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < end.count(); index++) {
            Central value = centralEntry(channel, cursor);
            boolean directory = value.name().endsWith("/");
            validateType(value.platform(), value.external(), directory, value.name());
            PluginPathPolicy.validate(value.name(), directory);
            valid(identities.add(ManifestPrimitives.pathIdentityKey(value.name())),
                "ARCHIVE_PATH_COLLISION", value.name());
            valid(value.expanded() <= limits.entryMax(), "ARCHIVE_ENTRY_TOO_LARGE", value.name());
            valid(total <= limits.totalMax() - value.expanded(), "ARCHIVE_TOTAL_TOO_LARGE", value.name());
            total += value.expanded();
            compressedTotal += value.compressed();
            ratio(value.expanded(), value.compressed(), limits.ratioMax(), value.name());
            result.add(value.entry(directory));
            cursor += value.recordSize();
        }
        valid(cursor == end.centralOffset() + end.centralSize(), "ARCHIVE_CENTRAL_INVALID", "archive");
        ratio(total, compressedTotal, limits.ratioMax(), "archive");
        PluginPathPolicy.validateCollisions(result.stream().map(StrictZipArchive.Entry::name).toList());
        return result;
    }

    private static Central centralEntry(FileChannel channel, long cursor) throws Exception {
        byte[] header = read(channel, cursor, 46);
        valid(uint(header, 0) == CENTRAL, "ARCHIVE_CENTRAL_INVALID", "archive");
        int madeBy = ushort(header, 4);
        int flags = ushort(header, 8);
        int method = ushort(header, 10);
        long crc = uint(header, 16);
        long compressed = uint(header, 20);
        long expanded = uint(header, 24);
        int nameLength = ushort(header, 28);
        int extraLength = ushort(header, 30);
        int commentLength = ushort(header, 32);
        int disk = ushort(header, 34);
        long external = uint(header, 38);
        long localOffset = uint(header, 42);
        validateCentralValues(flags, method, compressed, expanded, localOffset, disk, commentLength);
        byte[] variable = read(channel, cursor + 46, nameLength + extraLength + commentLength);
        rejectZip64Extra(variable, nameLength, extraLength);
        return new Central(decode(variable, 0, nameLength), madeBy >>> 8, external, flags, method,
            crc, compressed, expanded, localOffset, 46L + nameLength + extraLength + commentLength);
    }

    private static void validateCentralValues(int flags, int method, long compressed, long expanded,
            long localOffset, int disk, int commentLength) throws Exception {
        valid(compressed != 0xffffffffL && expanded != 0xffffffffL && localOffset != 0xffffffffL,
            "ARCHIVE_ZIP64_UNSUPPORTED", "archive");
        valid(disk == 0 && commentLength == 0,
            commentLength == 0 ? "ARCHIVE_MULTI_DISK" : "ARCHIVE_COMMENT_UNSUPPORTED", "archive");
        valid(supportedFlags(flags) && (method == 0 || method == 8),
            "ARCHIVE_ENTRY_UNSUPPORTED", "archive");
    }

    private static void locals(FileChannel channel, List<StrictZipArchive.Entry> entries,
            long centralOffset) throws Exception {
        List<StrictZipArchive.Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(StrictZipArchive.Entry::localOffset));
        long expected = 0;
        for (int index = 0; index < ordered.size(); index++) {
            StrictZipArchive.Entry entry = ordered.get(index);
            long next = index + 1 < ordered.size() ? ordered.get(index + 1).localOffset() : centralOffset;
            validateLocal(channel, entry, expected, next);
            expected = next;
        }
        valid(expected == centralOffset, "ARCHIVE_GAP_OR_OVERLAP", "archive");
    }

    private static void validateLocal(FileChannel channel, StrictZipArchive.Entry entry,
            long expected, long next) throws Exception {
        valid(entry.localOffset() == expected, "ARCHIVE_GAP_OR_OVERLAP", entry.name());
        byte[] header = read(channel, expected, 30);
        valid(uint(header, 0) == LOCAL, "ARCHIVE_LOCAL_INVALID", entry.name());
        int flags = ushort(header, 6);
        int method = ushort(header, 8);
        int nameLength = ushort(header, 26);
        int extraLength = ushort(header, 28);
        byte[] variable = read(channel, expected + 30, nameLength + extraLength);
        rejectZip64Extra(variable, nameLength, extraLength);
        valid(flags == entry.flags() && method == entry.method()
            && entry.name().equals(decode(variable, 0, nameLength)),
            "ARCHIVE_LOCAL_CENTRAL_MISMATCH", entry.name());
        long dataEnd = expected + 30L + nameLength + extraLength + entry.compressed();
        if ((flags & DATA_DESCRIPTOR) == 0) validateFixedLocal(header, dataEnd, next, entry);
        else {
            validateDescriptorLocal(header, entry);
            validateDescriptor(channel, dataEnd, next, entry);
        }
    }

    private static void validateFixedLocal(byte[] header, long dataEnd, long next,
            StrictZipArchive.Entry entry) throws Exception {
        valid(uint(header, 14) == entry.crc() && uint(header, 18) == entry.compressed()
            && uint(header, 22) == entry.expanded() && dataEnd == next,
            "ARCHIVE_LOCAL_CENTRAL_MISMATCH", entry.name());
    }

    private static void validateDescriptorLocal(byte[] header, StrictZipArchive.Entry entry)
            throws Exception {
        long crc = uint(header, 14);
        long compressed = uint(header, 18);
        long expanded = uint(header, 22);
        boolean zero = crc == 0 && compressed == 0 && expanded == 0;
        boolean exact = crc == entry.crc() && compressed == entry.compressed()
            && expanded == entry.expanded();
        valid(zero || exact, "ARCHIVE_LOCAL_CENTRAL_MISMATCH", entry.name());
    }

    private static void validateDescriptor(FileChannel channel, long at, long next,
            StrictZipArchive.Entry entry) throws Exception {
        long length = next - at;
        valid(length == 12 || length == 16, "ARCHIVE_DESCRIPTOR_INVALID", entry.name());
        byte[] descriptor = read(channel, at, (int) length);
        int values = 0;
        if (length == 16) {
            valid(uint(descriptor, 0) == DESCRIPTOR, "ARCHIVE_DESCRIPTOR_INVALID", entry.name());
            values = 4;
        }
        valid(uint(descriptor, values) == entry.crc()
            && uint(descriptor, values + 4) == entry.compressed()
            && uint(descriptor, values + 8) == entry.expanded(),
            "ARCHIVE_DESCRIPTOR_INVALID", entry.name());
    }

    private static void validateType(int platform, long external, boolean directory,
            String name) throws Exception {
        if (platform == 3) {
            int type = (int) ((external >>> 16) & 0170000);
            valid(type == (directory ? 0040000 : 0100000), "ARCHIVE_ENTRY_TYPE_UNSAFE", name);
        } else {
            valid(platform == 0 && (external & 0x10) == (directory ? 0x10 : 0),
                "ARCHIVE_ENTRY_TYPE_UNSAFE", name);
        }
    }

    private static void rejectZip64Extra(byte[] bytes, int nameLength, int extraLength) throws Exception {
        int cursor = nameLength;
        int end = nameLength + extraLength;
        while (cursor < end) {
            valid(cursor + 4 <= end, "ARCHIVE_EXTRA_INVALID", "archive");
            int id = ushort(bytes, cursor);
            int length = ushort(bytes, cursor + 2);
            valid(cursor + 4 + length <= end && id != 0x0001,
                id == 0x0001 ? "ARCHIVE_ZIP64_UNSUPPORTED" : "ARCHIVE_EXTRA_INVALID", "archive");
            cursor += 4 + length;
        }
    }

    private static boolean supportedFlags(int flags) {
        int allowed = UTF8 | DATA_DESCRIPTOR;
        return (flags & UTF8) != 0 && (flags & ~allowed) == 0;
    }

    private static void ratio(long expanded, long compressed, double maximum,
            String path) throws Exception {
        boolean bad = expanded > 0 && compressed == 0;
        bad |= compressed > 0 && (double) expanded / compressed > maximum;
        valid(!bad, "ARCHIVE_COMPRESSION_RATIO", path);
    }

    private record End(long centralOffset, long centralSize, int count) {}

    private record Central(String name, int platform, long external, int flags, int method,
            long crc, long compressed, long expanded, long localOffset, long recordSize) {
        StrictZipArchive.Entry entry(boolean directory) {
            return new StrictZipArchive.Entry(name, directory, flags, method, crc,
                compressed, expanded, localOffset);
        }
    }
}
