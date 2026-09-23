package dev.turboism.core.archive;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.turboism.core.archive.StrictZipSupport.CENTRAL;
import static dev.turboism.core.archive.StrictZipSupport.DATA_DESCRIPTOR;
import static dev.turboism.core.archive.StrictZipSupport.DESCRIPTOR;
import static dev.turboism.core.archive.StrictZipSupport.EOCD;
import static dev.turboism.core.archive.StrictZipSupport.LOCAL;
import static dev.turboism.core.archive.StrictZipSupport.UTF8;
import static dev.turboism.core.archive.StrictZipSupport.decode;
import static dev.turboism.core.archive.StrictZipSupport.read;
import static dev.turboism.core.archive.StrictZipSupport.uint;
import static dev.turboism.core.archive.StrictZipSupport.ushort;
import static dev.turboism.core.archive.StrictZipSupport.valid;

/**
 * Single structural-validation core for strict ZIP archives: EOCD closure, single-disk
 * non-ZIP64 central directory, central↔local header consistency, path identity
 * uniqueness, declared size/ratio bounds, and gap/overlap rejection. Content policy is
 * injected through {@link ArchivePathPolicy}; extraction is left to
 * {@link StrictZipArchive}, which pairs this parse with counted, CRC-verified reads.
 */
final class StrictZipParser {
    private StrictZipParser() {}

    static List<StrictZipArchive.Entry> parse(
        SeekableByteChannel channel,
        String label,
        StrictZipArchive.Limits limits,
        ArchivePathPolicy policy
    ) throws Exception {
        try {
            End end = end(channel, label, limits);
            List<Central> central = central(channel, end, limits, policy, label);
            return locals(channel, central, end.centralOffset());
        } catch (ArchiveStructureException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ArchiveStructureException(
                "ARCHIVE_INVALID", "Invalid ZIP archive", label);
        }
    }

    private static End end(
        SeekableByteChannel channel,
        String label,
        StrictZipArchive.Limits limits
    ) throws Exception {
        long length = channel.size();
        if (length < 22) {
            // Too short to even carry an EOCD record — malformed, not oversized.
            StrictZipSupport.invalid("ARCHIVE_TRUNCATED", label);
        }
        if (length > limits.rawMax()) {
            StrictZipSupport.invalid("PACKAGE_TOO_LARGE", label);
        }
        byte[] eocd = read(channel, length - 22, 22);
        valid(uint(eocd, 0) == EOCD && ushort(eocd, 20) == 0,
            "ARCHIVE_EOCD_INVALID", label);
        int count = ushort(eocd, 10);
        long centralSize = uint(eocd, 12);
        long centralOffset = uint(eocd, 16);
        valid(ushort(eocd, 4) == 0 && ushort(eocd, 6) == 0 && ushort(eocd, 8) == count,
            "ARCHIVE_MULTI_DISK", label);
        valid(count != 0xffff && centralSize != 0xffffffffL && centralOffset != 0xffffffffL,
            "ARCHIVE_ZIP64_UNSUPPORTED", label);
        String code = count > limits.countMax() ? "ARCHIVE_ENTRY_LIMIT" : "ARCHIVE_TRAILING_OR_GAP";
        valid(count <= limits.countMax() && centralOffset + centralSize == length - 22,
            code, label);
        return new End(centralOffset, centralSize, count);
    }

    private static List<Central> central(
        SeekableByteChannel channel,
        End end,
        StrictZipArchive.Limits limits,
        ArchivePathPolicy policy,
        String label
    ) throws Exception {
        long cursor = end.centralOffset();
        long total = 0;
        long compressedTotal = 0;
        List<Central> result = new ArrayList<>(end.count());
        List<String> names = new ArrayList<>(end.count());
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < end.count(); index++) {
            Central value = centralEntry(channel, cursor, index);
            boolean directory = value.name().endsWith("/");
            validateType(value.platform(), value.external(), directory, value.name(),
                policy.permitsDefaultDirectoryMetadata());
            // A policy that admits directories still requires them to deliver zero
            // expanded bytes — a directory carrying a payload is a data channel,
            // not structure. (Standard JDK writers emit a 2-byte empty deflate
            // stream for a directory, so the bound is on the expanded size.)
            valid(!directory || !policy.permitsDefaultDirectoryMetadata()
                    || value.expanded() == 0,
                "ARCHIVE_ENTRY_TYPE_UNSAFE", value.name());
            policy.validateEntry(value.name(), directory);
            valid(identities.add(ArchivePaths.pathIdentityKey(value.name())),
                "ARCHIVE_PATH_COLLISION", value.name());
            valid(value.expanded() <= limits.entryMax(), "ARCHIVE_ENTRY_TOO_LARGE", value.name());
            valid(total <= limits.totalMax() - value.expanded(), "ARCHIVE_TOTAL_TOO_LARGE", value.name());
            total += value.expanded();
            compressedTotal += value.compressed();
            ratio(value.expanded(), value.compressed(), limits.ratioMax(), value.name());
            result.add(value);
            names.add(value.name());
            cursor += value.recordSize();
        }
        valid(cursor == end.centralOffset() + end.centralSize(), "ARCHIVE_CENTRAL_INVALID", "archive");
        ratio(total, compressedTotal, limits.ratioMax(), "archive");
        policy.validateCollisions(names);
        return result;
    }

    private static Central centralEntry(
        SeekableByteChannel channel,
        long cursor,
        int index
    ) throws Exception {
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
        validateNameFlags(flags, variable, nameLength);
        return new Central(decode(variable, 0, nameLength), madeBy >>> 8, external, flags, method,
            crc, compressed, expanded, localOffset, 46L + nameLength + extraLength + commentLength,
            index);
    }

    private static void validateCentralValues(int flags, int method, long compressed, long expanded,
            long localOffset, int disk, int commentLength) throws Exception {
        valid(compressed != 0xffffffffL && expanded != 0xffffffffL && localOffset != 0xffffffffL,
            "ARCHIVE_ZIP64_UNSUPPORTED", "archive");
        valid(disk == 0 && commentLength == 0,
            commentLength == 0 ? "ARCHIVE_MULTI_DISK" : "ARCHIVE_COMMENT_UNSUPPORTED", "archive");
        valid((flags & ~(UTF8 | DATA_DESCRIPTOR)) == 0 && (method == 0 || method == 8),
            "ARCHIVE_ENTRY_UNSUPPORTED", "archive");
    }

    /**
     * Validates each local header against its central record (name, flags, method, and
     * — absent a data descriptor — the declared crc/sizes), proves the local records tile
     * the archive without gaps or overlaps, and stamps each entry with the offset where
     * its compressed data begins so byte[] backends can open a bounded data window.
     */
    private static List<StrictZipArchive.Entry> locals(
        SeekableByteChannel channel,
        List<Central> central,
        long centralOffset
    ) throws Exception {
        List<Central> ordered = new ArrayList<>(central);
        ordered.sort(Comparator.comparingLong(Central::localOffset));
        StrictZipArchive.Entry[] entries = new StrictZipArchive.Entry[central.size()];
        long expected = 0;
        for (int index = 0; index < ordered.size(); index++) {
            Central record = ordered.get(index);
            long next = index + 1 < ordered.size()
                ? ordered.get(index + 1).localOffset() : centralOffset;
            entries[record.index()] = validateLocal(channel, record, expected, next);
            expected = next;
        }
        valid(expected == centralOffset, "ARCHIVE_GAP_OR_OVERLAP", "archive");
        return List.of(entries);
    }

    private static StrictZipArchive.Entry validateLocal(
        SeekableByteChannel channel,
        Central record,
        long expected,
        long next
    ) throws Exception {
        valid(record.localOffset() == expected, "ARCHIVE_GAP_OR_OVERLAP", record.name());
        byte[] header = read(channel, expected, 30);
        valid(uint(header, 0) == LOCAL, "ARCHIVE_LOCAL_INVALID", record.name());
        int flags = ushort(header, 6);
        int method = ushort(header, 8);
        int nameLength = ushort(header, 26);
        int extraLength = ushort(header, 28);
        byte[] variable = read(channel, expected + 30, nameLength + extraLength);
        rejectZip64Extra(variable, nameLength, extraLength);
        valid(flags == record.flags() && method == record.method()
            && record.name().equals(decode(variable, 0, nameLength)),
            "ARCHIVE_LOCAL_CENTRAL_MISMATCH", record.name());
        long dataStart = expected + 30L + nameLength + extraLength;
        long dataEnd = dataStart + record.compressed();
        if ((flags & DATA_DESCRIPTOR) == 0) {
            validateFixedLocal(header, dataEnd, next, record);
        } else {
            validateDescriptorLocal(header, record);
            validateDescriptor(channel, dataEnd, next, record);
        }
        return record.entry(record.name().endsWith("/"), dataStart);
    }

    private static void validateFixedLocal(byte[] header, long dataEnd, long next,
            Central record) throws Exception {
        valid(uint(header, 14) == record.crc() && uint(header, 18) == record.compressed()
            && uint(header, 22) == record.expanded() && dataEnd == next,
            "ARCHIVE_LOCAL_CENTRAL_MISMATCH", record.name());
    }

    private static void validateDescriptorLocal(byte[] header, Central record)
            throws Exception {
        long crc = uint(header, 14);
        long compressed = uint(header, 18);
        long expanded = uint(header, 22);
        boolean zero = crc == 0 && compressed == 0 && expanded == 0;
        boolean exact = crc == record.crc() && compressed == record.compressed()
            && expanded == record.expanded();
        valid(zero || exact, "ARCHIVE_LOCAL_CENTRAL_MISMATCH", record.name());
    }

    private static void validateDescriptor(SeekableByteChannel channel, long at, long next,
            Central record) throws Exception {
        long length = next - at;
        valid(length == 12 || length == 16, "ARCHIVE_DESCRIPTOR_INVALID", record.name());
        byte[] descriptor = read(channel, at, (int) length);
        int values = 0;
        if (length == 16) {
            valid(uint(descriptor, 0) == DESCRIPTOR, "ARCHIVE_DESCRIPTOR_INVALID",
                record.name());
            values = 4;
        }
        valid(uint(descriptor, values) == record.crc()
            && uint(descriptor, values + 4) == record.compressed()
            && uint(descriptor, values + 8) == record.expanded(),
            "ARCHIVE_DESCRIPTOR_INVALID", record.name());
    }

    private static void validateType(int platform, long external, boolean directory,
            String name, boolean defaultDirectoryMetadata) throws Exception {
        if (platform == 3) {
            int type = (int) ((external >>> 16) & 0170000);
            valid(type == (directory ? 0040000 : 0100000), "ARCHIVE_ENTRY_TYPE_UNSAFE", name);
        } else {
            boolean ok = platform == 0
                && (external & 0x10) == (directory ? 0x10 : 0);
            ok |= defaultDirectoryMetadata && directory
                && platform == 0 && external == 0;
            valid(ok, "ARCHIVE_ENTRY_TYPE_UNSAFE", name);
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

    private static void validateNameFlags(int flags, byte[] variable, int nameLength) throws Exception {
        if ((flags & UTF8) != 0) return;
        for (int index = 0; index < nameLength; index++) {
            valid((variable[index] & 0x80) == 0, "ARCHIVE_ENTRY_UNSUPPORTED", "archive");
        }
    }

    private static void ratio(long expanded, long compressed, double maximum,
            String path) throws Exception {
        boolean bad = expanded > 0 && compressed == 0;
        bad |= compressed > 0 && (double) expanded / compressed > maximum;
        valid(!bad, "ARCHIVE_COMPRESSION_RATIO", path);
    }

    private record End(long centralOffset, long centralSize, int count) {}

    private record Central(String name, int platform, long external, int flags, int method,
            long crc, long compressed, long expanded, long localOffset, long recordSize,
            int index) {
        StrictZipArchive.Entry entry(boolean directory, long dataOffset) {
            return new StrictZipArchive.Entry(name, directory, flags, method, crc,
                compressed, expanded, localOffset, dataOffset);
        }
    }
}
