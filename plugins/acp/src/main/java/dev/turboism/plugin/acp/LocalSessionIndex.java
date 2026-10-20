package dev.turboism.plugin.acp;

import dev.turboism.sdk.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Locally owned session IDs, partitioned by harness and connection configuration. */
final class LocalSessionIndex {
    private final Path file;

    LocalSessionIndex(final Path state, final String harness, final AgentLaunchSpec launch) {
        final String identity = harness + "\n" + launch.protocol() + "\n" + Json.stringify(launch.command()) + "\n"
                + launch.workingDirectory();
        try {
            final String digest = java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            file = state.resolve("session-index").resolve(digest + ".json");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    synchronized List<AcpSessionSummary> sessions() throws IOException {
        if (!Files.exists(file)) return List.of();
        if (Files.size(file) > 4 * 1024 * 1024) throw new IOException("Local session index exceeds limit");
        final List<AcpSessionSummary> sessions = new ArrayList<>();
        try {
            final var root = NativeAgentTransport.object(Json.parseObject(Files.readAllBytes(file)));
            for (Object raw : NativeAgentTransport.list(root.get("sessions"))) {
                final var entry = NativeAgentTransport.object(raw);
                sessions.add(new AcpSessionSummary(
                        NativeAgentTransport.text(entry.get("id")),
                        NativeAgentTransport.text(entry.get("title")),
                        NativeAgentTransport.text(entry.get("updatedAt"))));
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid local session index", failure);
        }
        return List.copyOf(sessions);
    }

    synchronized void remember(final String id, final String title) throws IOException {
        final var values = new LinkedHashMap<String, AcpSessionSummary>();
        for (var entry : sessions()) values.put(entry.sessionId(), entry);
        final var old = values.remove(id);
        values.put(
                id,
                new AcpSessionSummary(
                        id,
                        title.isBlank() && old != null ? old.title() : title,
                        Instant.now().toString()));
        if (values.size() > 2000) throw new IOException("Local session index is full");
        final var entries = new ArrayList<Map<String, Object>>();
        for (var entry : values.values())
            entries.add(Map.of("id", entry.sessionId(), "title", entry.title(), "updatedAt", entry.updatedAt()));
        Files.createDirectories(file.getParent());
        final Path temporary = Files.createTempFile(file.getParent(), "index-", ".tmp");
        try {
            Files.writeString(temporary, Json.stringify(Map.of("version", 1L, "sessions", entries)));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
