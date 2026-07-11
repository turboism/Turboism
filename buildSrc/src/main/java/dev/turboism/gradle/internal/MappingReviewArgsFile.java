package dev.turboism.gradle.internal;

import org.gradle.api.GradleException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Internal build-logic helper for securely consuming the mapping-review argv file. */
public final class MappingReviewArgsFile {
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_LINES = 256;
    private static final int MAX_ENCODED_LINE_BYTES = 128 * 1024;
    private static final int MAX_DECODED_ARGUMENT_BYTES = 64 * 1024;

    private MappingReviewArgsFile() {
    }

    public static List<String> readAndDelete(Path path) {
        Object ownedKey = null;
        Long ownedSize = null;
        Throwable primaryFailure = null;
        try {
            BasicFileAttributes before;
            try {
                before = attributes(path);
            } catch (Exception ignored) {
                throw new GradleException("Mapping review argument file is unavailable or not inspectable.");
            }
            if (!before.isRegularFile()) {
                throw new GradleException("Mapping review argument file must be a NOFOLLOW regular file.");
            }
            Object beforeKey = before.fileKey();
            if (beforeKey == null) {
                throw new GradleException("Mapping review argument file identity is unavailable; refusing unsafe read and cleanup.");
            }
            if (before.size() > MAX_FILE_BYTES) {
                throw new GradleException("Mapping review argument file exceeds the byte limit.");
            }

            byte[] bytes;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long openedSize = channel.size();
                if (openedSize != before.size()) {
                    throw new GradleException("Mapping review argument file identity changed while opening.");
                }
                BasicFileAttributes openedPath = attributes(path);
                if (!openedPath.isRegularFile() || !beforeKey.equals(openedPath.fileKey()) || openedPath.size() != openedSize) {
                    throw new GradleException("Mapping review argument file identity changed while opening.");
                }
                ownedKey = beforeKey;
                ownedSize = openedSize;

                ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(openedSize, MAX_FILE_BYTES));
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                long total = 0;
                while (true) {
                    buffer.clear();
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    total += read;
                    if (total > MAX_FILE_BYTES) {
                        throw new GradleException("Mapping review argument file exceeds the byte limit while reading.");
                    }
                    output.write(buffer.array(), 0, read);
                }
                BasicFileAttributes after = attributes(path);
                if (!after.isRegularFile() || !beforeKey.equals(after.fileKey()) || after.size() != openedSize
                    || channel.size() != openedSize || total != openedSize) {
                    throw new GradleException("Mapping review argument file changed during bounded read.");
                }
                bytes = output.toByteArray();
            } catch (GradleException failure) {
                throw failure;
            } catch (Exception ignored) {
                throw new GradleException("Mapping review argument file identity changed while opening.");
            }
            return decode(bytes);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                if (ownedKey != null && ownedSize != null) {
                    BasicFileAttributes current = safeAttributes(path);
                    if (sameOwnedFile(current, ownedKey, ownedSize)) {
                        BasicFileAttributes confirmed = safeAttributes(path);
                        if (sameOwnedFile(confirmed, ownedKey, ownedSize)) {
                            Files.delete(path);
                        }
                    }
                }
            } catch (Exception ignored) {
                GradleException cleanupFailure = new GradleException(
                    "Mapping review argument file cleanup failed without path disclosure."
                );
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static List<String> decode(byte[] bytes) {
        for (byte value : bytes) {
            if (value == '\r') {
                throw new GradleException("Mapping review argument file must use LF line endings.");
            }
        }
        String content = new String(bytes, StandardCharsets.US_ASCII);
        String[] split = content.split("\\n", -1);
        int lineCount = split.length;
        if (lineCount > 0 && split[lineCount - 1].isEmpty()) {
            lineCount--;
        }
        if (lineCount > MAX_LINES) {
            throw new GradleException("Mapping review argument file exceeds the line limit.");
        }

        List<String> decodedArgs = new ArrayList<>(lineCount);
        for (int index = 0; index < lineCount; index++) {
            String encoded = split[index];
            if (encoded.getBytes(StandardCharsets.US_ASCII).length > MAX_ENCODED_LINE_BYTES) {
                throw new GradleException("Mapping review argument at line " + (index + 1) + " exceeds the encoded line limit.");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new GradleException("Invalid Base64 mapping review argument at line " + (index + 1), exception);
            }
            if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
                throw new GradleException("Non-canonical Base64 mapping review argument at line " + (index + 1));
            }
            if (decoded.length > MAX_DECODED_ARGUMENT_BYTES) {
                throw new GradleException("Mapping review argument at line " + (index + 1) + " exceeds the decoded byte limit.");
            }
            try {
                decodedArgs.add(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString());
            } catch (CharacterCodingException exception) {
                throw new GradleException("Invalid UTF-8 mapping review argument at line " + (index + 1), exception);
            }
        }
        return decodedArgs;
    }

    private static BasicFileAttributes attributes(Path path) throws Exception {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static BasicFileAttributes safeAttributes(Path path) {
        try {
            return attributes(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean sameOwnedFile(BasicFileAttributes attributes, Object key, long size) {
        return attributes != null && attributes.isRegularFile() && key.equals(attributes.fileKey()) && attributes.size() == size;
    }
}
