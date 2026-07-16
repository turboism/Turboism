package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Strict atomic writer for the four authoritative preview report files. */
public final class PreviewReportWriter {

    private final Path stateDirectory;
    private final Consumer<Diagnostic> diagnosticSink;
    private final PreviewReportSanitizer sanitizer = new PreviewReportSanitizer();
    private final PreviewReportTruncator truncator = new PreviewReportTruncator();

    public PreviewReportWriter(
        final Path stateDirectory,
        final Consumer<Diagnostic> diagnosticSink
    ) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory")
            .toAbsolutePath()
            .normalize();
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    public Map<PreviewReportType, Boolean> writeAll(
        final Map<PreviewReportType, ObjectNode> documents
    ) {
        Objects.requireNonNull(documents, "documents");
        final EnumMap<PreviewReportType, Boolean> results =
            new EnumMap<>(PreviewReportType.class);
        for (PreviewReportType type : PreviewReportType.values()) {
            final ObjectNode document = documents.get(type);
            results.put(type, document != null && write(type, document));
        }
        return Map.copyOf(results);
    }

    public boolean write(
        final PreviewReportType expectedType,
        final ObjectNode document
    ) {
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(document, "document");
        Path temporary = null;
        try {
            final ObjectNode sanitized = document.deepCopy();
            sanitizer.sanitize(sanitized);
            final byte[] bytes = truncator.truncate(sanitized);
            final PreviewReportValidator.ValidatedReport validated =
                PreviewReportValidator.validate(bytes);
            if (validated.reportType() != expectedType) {
                throw new PreviewReportValidationException(
                    "REPORT_TYPE_MISMATCH",
                    "Preview report target and discriminator do not match."
                );
            }
            prepareStateDirectory();
            final Path target = stateDirectory.resolve(expectedType.fileName());
            temporary = stateDirectory.resolve(
                "." + expectedType.fileName() + ".turboism-" + UUID.randomUUID() + ".tmp"
            );
            writeForced(temporary, bytes);
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
            temporary = null;
            return true;
        } catch (PreviewReportValidationException exception) {
            emitRejected(expectedType, exception.code());
            return false;
        } catch (AtomicMoveNotSupportedException exception) {
            emitFailure(expectedType, "ATOMIC_REPLACE_UNAVAILABLE");
            return false;
        } catch (IOException | RuntimeException exception) {
            emitFailure(expectedType, "IO_FAILURE");
            return false;
        } finally {
            deleteTemporary(temporary);
        }
    }

    private void prepareStateDirectory() throws IOException {
        Files.createDirectories(stateDirectory);
        if (Files.isSymbolicLink(stateDirectory)
            || !Files.isDirectory(stateDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Preview report state directory is unsafe.");
        }
    }

    private static void writeForced(
        final Path temporary,
        final byte[] bytes
    ) throws IOException {
        try (FileChannel channel = FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void emitRejected(
        final PreviewReportType type,
        final String validationCode
    ) {
        diagnosticSink.accept(new Diagnostic(
            "PREVIEW_REPORT_WRITE_REJECTED",
            type,
            "Preview report was rejected by strict validation: " + safeCode(validationCode)
        ));
    }

    private void emitFailure(
        final PreviewReportType type,
        final String failureCode
    ) {
        diagnosticSink.accept(new Diagnostic(
            "PREVIEW_REPORT_WRITE_FAILED",
            type,
            "Preview report persistence failed safely: " + safeCode(failureCode)
        ));
    }

    private static String safeCode(final String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,128}")) {
            return "UNKNOWN";
        }
        return value;
    }

    private static void deleteTemporary(final Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }

    public record Diagnostic(
        String code,
        PreviewReportType reportType,
        String message
    ) {
        public Diagnostic {
            code = Objects.requireNonNull(code, "code");
            reportType = Objects.requireNonNull(reportType, "reportType");
            message = Objects.requireNonNull(message, "message");
        }
    }
}
