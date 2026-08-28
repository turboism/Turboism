package dev.turboism.adapter.cubism.editor;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/** Explicitly enabled exact-host evidence trace; inert during ordinary runtime use. */
final class EditorObjectValidationTrace {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    static final long MAX_BYTES = 1_048_576L;
    private static final Object WRITE_LOCK = new Object();

    private EditorObjectValidationTrace() {
    }

    static long begin(
        final String kind,
        final String action,
        final String sourceId,
        final Object document,
        final Object modelSource
    ) {
        final long transaction = SEQUENCE.incrementAndGet();
        write(transaction, "begin", kind, action, sourceId, document, modelSource, "");
        return transaction;
    }

    static void event(
        final long transaction,
        final String phase,
        final String kind,
        final String action,
        final String sourceId,
        final Object document,
        final Object modelSource,
        final String detail
    ) {
        write(transaction, phase, kind, action, sourceId, document, modelSource, detail);
    }

    private static void write(
        final long transaction,
        final String phase,
        final String kind,
        final String action,
        final String sourceId,
        final Object document,
        final Object modelSource,
        final String detail
    ) {
        writeArtifact(
            "editor-object-runtime-trace.txt",
            "transaction=" + transaction
                + " phase=" + phase
                + " kind=" + safe(kind)
                + " action=" + safe(action)
                + " sourceId=" + safe(sourceId)
                + " documentIdentity=" + identity(document)
                + " modelSourceIdentity=" + identity(modelSource)
                + " thread=" + safe(Thread.currentThread().getName())
                + " edt=" + SwingUtilities.isEventDispatchThread()
                + (detail == null || detail.isBlank() ? "" : " " + detail)
                + System.lineSeparator(),
            true
        );
    }

    /** Writes a separate opt-in validation evidence artifact under the shared safe trace policy. */
    static void writeArtifact(final String fileName, final String evidence, final boolean append) {
        if (!Boolean.getBoolean("turboism.editorObjectValidation.trace")) return;
        final String home = System.getProperty("turboism.home");
        if (home == null || home.isBlank() || fileName == null || fileName.isBlank()
            || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            return;
        }
        try {
            final Path artifact = Path.of(home, "logs", fileName);
            final String content = evidence == null ? "" : evidence;
            synchronized (WRITE_LOCK) {
                Files.createDirectories(artifact.getParent());
                final long existingBytes = Files.exists(artifact) ? Files.size(artifact) : 0L;
                final long contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                if ((append ? existingBytes : 0L) + contentBytes > MAX_BYTES) return;
                Files.writeString(
                    artifact,
                    content,
                    StandardOpenOption.CREATE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING
                );
            }
        } catch (IOException | RuntimeException ignored) {
            // Validation tracing must never change host mutation behavior.
        }
    }

    private static String identity(final Object value) {
        return value == null ? "null" : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String safe(final String value) {
        if (value == null) return "null";
        return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }
}
