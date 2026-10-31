package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted descriptor of an update staged and scheduled for application on the next controlled launch.
 *
 * <p>Security contract: this file is untrusted state. It carries NO signature authority, NO keys, and NO file
 * hashes. The helper must independently re-authenticate the staged manifest, verify all signatures against its
 * own compiled/bundled keys, reload and check the old installation receipt, and re-hash every staged payload byte
 * before replacing any installed file.
 */
final class FrameworkUpdatePendingTask {
    static final String FILE_NAME = "pending.json";
    static final String FORMAT = "turboism-framework-pending-v1";
    private static final int MAX_PENDING_BYTES = 64 * 1024;

    record Task(Path root, Path stage, String version, Instant created) {
        Task {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(created, "created");
        }
    }

    private FrameworkUpdatePendingTask() {}

    static void write(final Path workDir, final Path root, final Path stage, final String version)
            throws IOException {
        Objects.requireNonNull(workDir, "workDir");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(version, "version");
        final Path target = workDir.toAbsolutePath().normalize().resolve(FILE_NAME);
        final Path workParent = target.getParent();
        if (workParent != null) {
            FrameworkUpdateCache.installationAncestors(workParent);
            Files.createDirectories(workParent);
        }
        final ObjectNode node = UpdateFileSupport.JSON.createObjectNode();
        node.put("format", FORMAT);
        node.put("root", root.toAbsolutePath().normalize().toString());
        node.put("stage", stage.toAbsolutePath().normalize().toString());
        node.put("version", version);
        node.put("created", Instant.now().toString());
        final byte[] bytes = (UpdateFileSupport.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final Path temp = Files.createTempFile(workParent, ".pending-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static Optional<Task> read(final Path workDir) throws IOException {
        Objects.requireNonNull(workDir, "workDir");
        final Path path = workDir.toAbsolutePath().normalize().resolve(FILE_NAME);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        final var attrs = FrameworkUpdateCache.attributes(path);
        if (attrs == null || attrs.size() > MAX_PENDING_BYTES || attrs.isSymbolicLink()) {
            discard(workDir);
            return Optional.empty();
        }
        try {
            final JsonNode rootNode = UpdateFileSupport.JSON.readTree(Files.readAllBytes(path));
            if (!(rootNode instanceof ObjectNode obj) || !FORMAT.equals(obj.path("format").asText())) {
                discard(workDir);
                return Optional.empty();
            }
            final String rootStr = obj.path("root").asText("");
            final String stageStr = obj.path("stage").asText("");
            final String version = obj.path("version").asText("");
            final String createdStr = obj.path("created").asText("");
            if (rootStr.isBlank() || stageStr.isBlank() || version.isBlank()) {
                discard(workDir);
                return Optional.empty();
            }
            final Path root = Path.of(rootStr);
            final Path stage = Path.of(stageStr);
            Instant created;
            try {
                created = createdStr.isBlank() ? Instant.EPOCH : Instant.parse(createdStr);
            } catch (RuntimeException badTime) {
                created = Instant.EPOCH;
            }
            return Optional.of(new Task(root, stage, version, created));
        } catch (IOException parseError) {
            discard(workDir);
            return Optional.empty();
        }
    }

    static void discard(final Path workDir) {
        try {
            final Path path = workDir.toAbsolutePath().normalize().resolve(FILE_NAME);
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }
}
