package dev.turboism.kernel.report;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.DurableWriteException;
import dev.turboism.core.io.DurableWriteException.Failure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Strict atomic writer for the four authoritative preview report files. */
public final class KernelReportWriter {

    private final Path stateDirectory;
    private final Consumer<Diagnostic> diagnosticSink;
    private final KernelReportSanitizer sanitizer = new KernelReportSanitizer();
    private final KernelReportTruncator truncator = new KernelReportTruncator();

    public KernelReportWriter(final Path stateDirectory, final Consumer<Diagnostic> diagnosticSink) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory")
                .toAbsolutePath()
                .normalize();
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    /**
     * Attempts to write every report type, so one bad document does not suppress the others.
     *
     * <p>A type absent from the map is recorded as {@code false} rather than skipped: the result
     * always has an entry for all four types.
     *
     * @param documents the documents to persist, keyed by type; may be partial
     * @return an unmodifiable map from every report type to whether its file was written
     * @throws NullPointerException if {@code documents} is {@code null}
     */
    public Map<KernelReportType, Boolean> writeAll(final Map<KernelReportType, ObjectNode> documents) {
        Objects.requireNonNull(documents, "documents");
        final EnumMap<KernelReportType, Boolean> results = new EnumMap<>(KernelReportType.class);
        for (KernelReportType type : KernelReportType.values()) {
            final ObjectNode document = documents.get(type);
            results.put(type, document != null && write(type, document));
        }
        return Map.copyOf(results);
    }

    /**
     * Sanitizes, truncates, validates, and atomically publishes one report file.
     *
     * <p>The caller's node is never mutated — it is deep-copied first. The bytes are validated
     * against the full v1 contract and their declared type is checked against
     * {@code expectedType} before anything touches the filesystem, so an invalid document never
     * replaces a previously good file. Publication goes through {@link DurableFile}: a uniquely
     * named sibling temporary file that is force-synced and then atomically moved over the target,
     * deleted on every failure path. Atomicity is required — the writer has no non-atomic fallback.
     * The state directory is created if needed and rejected if it is a symbolic link.
     *
     * <p>This method does not throw for expected failures: rejection and I/O problems are reported
     * to the diagnostic sink as a {@link Diagnostic} and reduced to a {@code false} return, so a
     * reporting problem cannot take down the run.
     *
     * @param expectedType the report type this file must contain, and whose fixed file name is the
     *     write target
     * @param document the report document; copied, not retained
     * @return whether the file was published
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean write(final KernelReportType expectedType, final ObjectNode document) {
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(document, "document");
        try {
            final ObjectNode sanitized = document.deepCopy();
            sanitizer.sanitize(sanitized);
            final byte[] bytes = truncator.truncate(sanitized);
            final KernelReportValidator.ValidatedReport validated = KernelReportValidator.validate(bytes);
            if (validated.reportType() != expectedType) {
                throw new KernelReportValidationException(
                        "REPORT_TYPE_MISMATCH", "Preview report target and discriminator do not match.");
            }
            prepareStateDirectory();
            DurableFile.at(stateDirectory.resolve(expectedType.fileName())).replace(bytes);
            return true;
        } catch (KernelReportValidationException exception) {
            emitRejected(expectedType, exception.code());
            return false;
        } catch (DurableWriteException exception) {
            // Only an unavailable atomic replace keeps its own code; every other publication
            // failure stays the generic I/O code this writer has always reported.
            emitFailure(
                    expectedType,
                    exception.failure() == Failure.ATOMIC_REPLACE_UNAVAILABLE
                            ? "ATOMIC_REPLACE_UNAVAILABLE"
                            : "IO_FAILURE");
            return false;
        } catch (IOException | RuntimeException exception) {
            emitFailure(expectedType, "IO_FAILURE");
            return false;
        }
    }

    private void prepareStateDirectory() throws IOException {
        Files.createDirectories(stateDirectory);
        if (Files.isSymbolicLink(stateDirectory) || !Files.isDirectory(stateDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Preview report state directory is unsafe.");
        }
    }

    private void emitRejected(final KernelReportType type, final String validationCode) {
        diagnosticSink.accept(new Diagnostic(
                "PREVIEW_REPORT_WRITE_REJECTED",
                type,
                "Preview report was rejected by strict validation: " + safeCode(validationCode)));
    }

    private void emitFailure(final KernelReportType type, final String failureCode) {
        diagnosticSink.accept(new Diagnostic(
                "PREVIEW_REPORT_WRITE_FAILED",
                type,
                "Preview report persistence failed safely: " + safeCode(failureCode)));
    }

    private static String safeCode(final String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,128}")) {
            return "UNKNOWN";
        }
        return value;
    }

    /**
     * A report-write problem handed to the writer's diagnostic sink instead of being thrown.
     *
     * <p>Emitted with code {@code PREVIEW_REPORT_WRITE_REJECTED} when strict validation refused
     * the document, or {@code PREVIEW_REPORT_WRITE_FAILED} when persistence failed. The message
     * embeds only a sanitized upper-case code, so a raw exception detail never reaches the sink.
     *
     * @param code which of the two write-problem classes this is
     * @param reportType the report whose write was abandoned
     * @param message human-readable detail ending in the sanitized cause code
     */
    public record Diagnostic(String code, KernelReportType reportType, String message) {
        public Diagnostic {
            code = Objects.requireNonNull(code, "code");
            reportType = Objects.requireNonNull(reportType, "reportType");
            message = Objects.requireNonNull(message, "message");
        }
    }
}
