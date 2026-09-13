package dev.turboism.update;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Small bounded, symlink-rejecting atomic-file helper for update state. */
final class UpdateFileSupport {
    static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private UpdateFileSupport() {
    }

    static Path normalizeHome(final Path requestedHome) {
        return Objects.requireNonNull(requestedHome, "home").toAbsolutePath().normalize();
    }

    static Object lockFor(final Path path) {
        return LOCKS.computeIfAbsent(path, ignored -> new Object());
    }

    static ObjectNode readObject(final Path home, final Path path) throws IOException {
        rejectSymlinkChain(home, path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("update state file rejected");
        }
        final byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("update state file rejected");
        final JsonNode parsed = JSON.readTree(bytes);
        if (!(parsed instanceof ObjectNode object)) throw new IOException("update state is not an object");
        return object;
    }

    static void writeAtomic(
        final Path home,
        final Path path,
        final ObjectNode object
    ) throws IOException {
        Objects.requireNonNull(object, "object");
        final Path parent = path.getParent();
        if (parent == null || !path.startsWith(home)) throw new IOException("update path escaped home");
        rejectSymlinkChain(home, parent);
        Files.createDirectories(parent);
        rejectSymlinkChain(home, parent);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("update state file is symbolic link");
        }
        final byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(object);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("update state is too large");
        final Path temporary = parent.resolve("." + path.getFileName() + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            move(temporary, path);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                failure.addSuppressed(ignored);
            }
            throw failure;
        }
    }

    static void rejectSymlinkChain(final Path home, final Path requested) throws IOException {
        final Path normalizedHome = normalizeHome(home);
        final Path normalized = requested.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedHome)) throw new IOException("update path escaped home");
        Path current = normalized;
        while (current != null && current.startsWith(normalizedHome)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("symbolic link in update path");
            }
            if (current.equals(normalizedHome)) break;
            current = current.getParent();
        }
        if (current == null || !current.equals(normalizedHome)) {
            throw new IOException("update path escaped home");
        }
        if (Files.exists(normalizedHome, LinkOption.NOFOLLOW_LINKS)
            && Files.isSymbolicLink(normalizedHome)) {
            throw new IOException("update home is symbolic link");
        }
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void report(final Consumer<String> diagnostic, final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort and never change fail-closed state handling.
        }
    }
}
