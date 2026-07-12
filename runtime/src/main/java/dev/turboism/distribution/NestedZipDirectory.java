package dev.turboism.distribution;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class NestedZipDirectory {
    private static final long EOCD = 0x06054b50L;
    private static final long CENTRAL = 0x02014b50L;
    private static final long LOCAL = 0x04034b50L;
    private static final long DESCRIPTOR = 0x08074b50L;
    private static final int UTF8 = 0x0800;
    private static final int DATA_DESCRIPTOR = 0x0008;
    private final byte[] bytes;
    private final String path;

    private NestedZipDirectory(byte[] bytes, String path) {
        this.bytes = bytes;
        this.path = path;
    }

    static List<String> parse(byte[] bytes, String path) throws DistributionValidationException {
        return new NestedZipDirectory(bytes, path).parse();
    }

    private List<String> parse() throws DistributionValidationException {
        int eocd = terminalEocd();
        int count = ushort(eocd + 10);
        long size = uint(eocd + 12);
        long offset = uint(eocd + 16);
        valid(ushort(eocd + 4) == 0 && ushort(eocd + 6) == 0);
        valid(ushort(eocd + 8) == count && count != 0xffff);
        valid(size != 0xffffffffL && offset != 0xffffffffL);
        valid(ushort(eocd + 20) == 0); // phase 1 rejects archive comments
        valid(offset <= Integer.MAX_VALUE && offset + size == eocd);
        List<Record> records = records((int) offset, size, count);
        validateLocals(records, (int) offset);
        return records.stream().map(Record::name).toList();
    }

    private List<Record> records(int cursor, long size, int count)
        throws DistributionValidationException {
        long end = cursor + size;
        List<Record> records = new ArrayList<>(count);
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < count; index++) {
            valid(cursor >= 0 && cursor + 46L <= end && uint(cursor) == CENTRAL);
            int flags = ushort(cursor + 8);
            int method = ushort(cursor + 10);
            long crc = uint(cursor + 16);
            long compressed = uint(cursor + 20);
            long expanded = uint(cursor + 24);
            int nameLength = ushort(cursor + 28);
            int extraLength = ushort(cursor + 30);
            int commentLength = ushort(cursor + 32);
            long localOffset = uint(cursor + 42);
            long next = cursor + 46L + nameLength + extraLength + commentLength;
            valid(flags(flags) && compressed != 0xffffffffL && expanded != 0xffffffffL
                && next <= end && localOffset <= Integer.MAX_VALUE);
            String name = decode(cursor + 46, nameLength);
            valid(unique.add(name));
            records.add(new Record(name, flags, method, crc, compressed, expanded, (int) localOffset));
            cursor = (int) next;
        }
        valid(cursor == end);
        return List.copyOf(records);
    }

    private void validateLocals(List<Record> centralOrder, int centralOffset)
        throws DistributionValidationException {
        List<Record> locals = new ArrayList<>(centralOrder);
        locals.sort(Comparator.comparingInt(Record::localOffset));
        int expected = 0;
        for (int index = 0; index < locals.size(); index++) {
            Record record = locals.get(index);
            valid(record.localOffset() == expected);
            int at = record.localOffset();
            valid(at + 30L <= centralOffset && uint(at) == LOCAL);
            int flags = ushort(at + 6);
            int method = ushort(at + 8);
            int nameLength = ushort(at + 26);
            int extraLength = ushort(at + 28);
            long dataStart = at + 30L + nameLength + extraLength;
            long dataEnd = dataStart + record.compressed();
            valid(flags == record.flags() && method == record.method() && flags(flags));
            valid(dataEnd <= centralOffset && record.name().equals(decode(at + 30, nameLength)));
            int next = index + 1 < locals.size() ? locals.get(index + 1).localOffset() : centralOffset;
            if ((flags & DATA_DESCRIPTOR) == 0) {
                valid(uint(at + 14) == record.crc() && uint(at + 18) == record.compressed()
                    && uint(at + 22) == record.expanded() && dataEnd == next);
            } else {
                validateDescriptor((int) dataEnd, next, record);
            }
            expected = next;
        }
        valid(expected == centralOffset);
    }

    private void validateDescriptor(int at, int next, Record record)
        throws DistributionValidationException {
        int length = next - at;
        int values = at;
        if (length == 16) {
            valid(uint(at) == DESCRIPTOR);
            values += 4;
        } else {
            valid(length == 12);
        }
        valid(uint(values) == record.crc() && uint(values + 4) == record.compressed()
            && uint(values + 8) == record.expanded());
    }

    private int terminalEocd() throws DistributionValidationException {
        int terminal = bytes.length - 22;
        valid(terminal >= 0 && uint(terminal) == EOCD && ushort(terminal + 20) == 0);
        return terminal;
    }

    private String decode(int at, int length) throws DistributionValidationException {
        valid(at >= 0 && at + (long) length <= bytes.length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, at, length)).toString();
        } catch (CharacterCodingException exception) {
            invalid();
            return "";
        }
    }

    private static boolean flags(int flags) {
        int forbidden = 0x0001 | 0x0020 | 0x0040 | 0x2000;
        return (flags & UTF8) != 0 && (flags & forbidden) == 0;
    }

    private int ushort(int at) {
        if (at < 0 || at + 2 > bytes.length) return -1;
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    private long uint(int at) {
        if (at < 0 || at + 4 > bytes.length) return -1;
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
            | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    private void valid(boolean condition) throws DistributionValidationException {
        if (!condition) invalid();
    }

    private void invalid() throws DistributionValidationException {
        throw ArchivePolicy.problem(DistributionErrors.JAR_INVALID,
            "Artifact is not a valid JAR", path);
    }

    private record Record(String name, int flags, int method, long crc, long compressed,
                          long expanded, int localOffset) {}
}
