package dev.turboism.distribution;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class StrictZipSupport {
    static final long EOCD = 0x06054b50L;
    static final long CENTRAL = 0x02014b50L;
    static final long LOCAL = 0x04034b50L;
    static final long DESCRIPTOR = 0x08074b50L;
    static final int UTF8 = 0x0800;
    static final int DATA_DESCRIPTOR = 0x0008;

    private StrictZipSupport() {}

    static byte[] read(FileChannel channel, long offset, int length) throws Exception {
        if (offset < 0 || length < 0 || offset > channel.size() - length) {
            invalid("ARCHIVE_TRUNCATED", "archive");
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) invalid("ARCHIVE_TRUNCATED", "archive");
        }
        return buffer.array();
    }

    static String decode(byte[] bytes, int offset, int length) throws Exception {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            invalid("ARCHIVE_PATH_UTF8_INVALID", "archive");
            return "";
        }
    }

    static int ushort(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    static long uint(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
            | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    static void valid(boolean condition, String code, String path) throws Exception {
        if (!condition) invalid(code, path);
    }

    static void invalid(String code, String path) throws DistributionValidationException {
        throw ArchivePolicy.problem(code, "Invalid strict ZIP archive", path);
    }
}
