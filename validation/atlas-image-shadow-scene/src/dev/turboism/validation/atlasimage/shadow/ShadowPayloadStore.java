package dev.turboism.validation.atlasimage.shadow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Fixed T040 payload and one-shot canonical result publication. */
final class ShadowPayloadStore {
    static final String PAYLOAD_NAME = "payload.properties";
    static final String RESULT_NAME = "result.txt";

    record Persisted(Path path, String sha256) {}

    @FunctionalInterface
    interface ChannelWriter {
        int write(FileChannel channel, ByteBuffer buffer) throws IOException;
    }

    @FunctionalInterface
    private interface PrePublishVerifier {
        void verify(Path temporary) throws Exception;
    }

    private static final int MAX_ZERO_PROGRESS = 8;

    private ShadowPayloadStore() {}

    static Persisted persist(final Path run, final String runId, final String scene,
                             final String profile, final String fixtureName,
                             final String fixtureSha256,
                             final Map<String, String> freeze) throws Exception {
        final Map<String, String> expected = new TreeMap<>();
        expected.put("payloadSchemaVersion", "1");
        expected.put("scene", scene);
        expected.put("runId", runId);
        expected.put("profile", profile);
        expected.put("fixtureName", fixtureName);
        expected.put("fixtureSha256", fixtureSha256);
        for (Map.Entry<String, String> entry : freeze.entrySet()) {
            expected.put("freeze." + entry.getKey(), entry.getValue());
        }
        final byte[] bytes = encode(expected);
        final Path target = run.resolve(PAYLOAD_NAME).normalize();
        if (!target.startsWith(run) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("payload already exists or escaped run directory");
        }
        atomicCreate(target, bytes);
        final String hash = sha256(bytes);
        verifyReopen(target, expected, hash);
        return new Persisted(target, hash);
    }

    static void publishComplete(final Path result, final String runId, final String scene,
                                final String payloadSha256) throws Exception {
        final byte[] bytes = completeBytes(runId, scene, payloadSha256);
        atomicCreate(result, bytes, temporary -> verifyCompleteCanonical(temporary, bytes),
            (channel, buffer) -> channel.write(buffer));
    }

    static void publishCompleteForSelfCheck(final Path result, final String runId,
                                            final String scene, final String payloadSha256,
                                            final ChannelWriter writer) throws Exception {
        final byte[] bytes = completeBytes(runId, scene, payloadSha256);
        atomicCreate(result, bytes, temporary -> verifyCompleteCanonical(temporary, bytes), writer);
    }

    static void verifyCompleteForSelfCheck(final Path temporary, final byte[] expected) throws Exception {
        verifyCompleteCanonical(temporary, expected);
    }

    static byte[] completeBytesForSelfCheck(final String runId, final String scene,
                                            final String payloadSha256) {
        return completeBytes(runId, scene, payloadSha256);
    }

    private static byte[] completeBytes(final String runId, final String scene,
                                        final String payloadSha256) {
        final String content = "collectionStatus=COMPLETE\n"
            + "scene=" + scene + "\n"
            + "runId=" + runId + "\n"
            + "payloadSha256=" + payloadSha256 + "\n";
        return content.getBytes(StandardCharsets.UTF_8);
    }

    static void publishFailed(final Path result, final String runId, final String scene,
                              final String reason) throws Exception {
        final String content = "collectionStatus=FAILED\n"
            + "scene=" + scene + "\n"
            + "runId=" + runId + "\n"
            + "reason=" + safeReason(reason) + "\n";
        atomicCreate(result, content.getBytes(StandardCharsets.UTF_8));
    }

    static Properties loadProperties(final Path path) throws Exception {
        final Properties values = new Properties();
        try (var input = Files.newInputStream(path, StandardOpenOption.READ)) {
            values.load(input);
        }
        return values;
    }

    private static Properties loadProperties(final byte[] bytes) throws IOException {
        final Properties values = new Properties();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            values.load(input);
        }
        return values;
    }

    private static void verifyReopen(final Path target, final Map<String, String> expected,
                                     final String expectedHash) throws Exception {
        final byte[] bytes = Files.readAllBytes(target);
        if (!expectedHash.equals(sha256(bytes))) throw new IllegalStateException("payload hash changed on reopen");
        final Properties values = loadProperties(target);
        if (values.size() != expected.size()) throw new IllegalStateException("payload key count changed on reopen");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(values.getProperty(entry.getKey()))) {
                throw new IllegalStateException("payload value changed on reopen: " + entry.getKey());
            }
        }
    }

    private static void verifyExactBytes(final Path path, final byte[] expected) throws Exception {
        if (!Arrays.equals(Files.readAllBytes(path), expected)) {
            throw new IllegalStateException("pre-publish bytes are incomplete or changed");
        }
    }

    private static void verifyCompleteCanonical(final Path path, final byte[] expected) throws Exception {
        verifyExactBytes(path, expected);
        final Properties actual = loadProperties(path);
        final Properties expectedValues = loadProperties(expected);
        if (actual.size() != 4 || expectedValues.size() != 4
                || !"COMPLETE".equals(actual.getProperty("collectionStatus"))) {
            throw new IllegalStateException("pre-publish canonical COMPLETE is incomplete");
        }
        for (String key : expectedValues.stringPropertyNames()) {
            if (!expectedValues.getProperty(key).equals(actual.getProperty(key))) {
                throw new IllegalStateException("pre-publish canonical value mismatch: " + key);
            }
        }
    }

    private static byte[] encode(final Map<String, String> values) {
        final StringBuilder text = new StringBuilder(256 + values.size() * 48);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text.append(escape(entry.getKey(), true)).append('=')
                .append(escape(entry.getValue(), false)).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(final String value, final boolean key) {
        final StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case '=' -> escaped.append(key ? "\\=" : "\\=");
                case ':' -> escaped.append(key ? "\\:" : ":");
                case ' ' -> {
                    if (index == 0 || key) escaped.append('\\');
                    escaped.append(' ');
                }
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static void atomicCreate(final Path target, final byte[] bytes) throws Exception {
        atomicCreate(target, bytes, temporary -> verifyExactBytes(temporary, bytes),
            (channel, buffer) -> channel.write(buffer));
    }

    private static void atomicCreate(final Path target, final byte[] bytes,
                                     final PrePublishVerifier verifier,
                                     final ChannelWriter writer) throws Exception {
        final Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("target has no parent");
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("refusing to overwrite existing result or payload");
        }
        final Path temporary = Files.createTempFile(parent, ".t040-publish-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(bytes), writer);
                channel.force(true);
            }
            verifier.verify(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IllegalStateException("atomic publication is unavailable", unsupported);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static void writeFully(final FileChannel channel, final ByteBuffer buffer,
                                   final ChannelWriter writer) throws IOException {
        int zeroProgress = 0;
        while (buffer.hasRemaining()) {
            final int before = buffer.position();
            final int remaining = buffer.remaining();
            final int written = writer.write(channel, buffer);
            if (written < 0 || written > remaining || buffer.position() - before != written) {
                throw new IOException("invalid FileChannel write progress");
            }
            if (written == 0) {
                if (++zeroProgress >= MAX_ZERO_PROGRESS) {
                    throw new IOException("FileChannel write made no progress");
                }
                Thread.yield();
            } else {
                zeroProgress = 0;
            }
        }
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(bytes));
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String safeReason(final String reason) {
        final String value = reason == null ? "UNSPECIFIED" : reason;
        final StringBuilder safe = new StringBuilder(64);
        for (int index = 0; index < value.length() && safe.length() < 64; index++) {
            final char character = value.charAt(index);
            safe.append(Character.isLetterOrDigit(character) || character == '_' || character == '-'
                || character == '.' ? character : '_');
        }
        return safe.length() == 0 ? "UNSPECIFIED" : safe.toString();
    }
}
