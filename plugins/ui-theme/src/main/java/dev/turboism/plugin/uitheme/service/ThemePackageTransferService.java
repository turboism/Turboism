package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.domain.ThemePackageArchive;
import dev.turboism.plugin.uitheme.domain.ThemePackageData;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileReadResult;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.sdk.ui.UserFileWriteResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Imports and exports bounded theme archives through opaque user-file handles.
 *
 * <p>No operation waits for the host: file requests and their reads and writes settle on the owning
 * plugin's worker, so callers compose instead of blocking that worker.</p>
 */
public final class ThemePackageTransferService {

    private final UserFileAccessService files;

    public ThemePackageTransferService(final UserFileAccessService files) {
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * Encodes the package and writes it atomically to a file the user picks.
     *
     * <p>The handle is granted for one operation only and is closed on every completion path — written,
     * refused, failed, or thrown synchronously — so no lasting file access is retained. The user
     * cancelling is {@code CANCELED}, a host that refuses the request is {@code UNAVAILABLE}, and a
     * failed write is {@code FAILED}; none of those outcomes throw.
     *
     * @param theme the package to write
     * @return a stage of the outcome and, when the host supplied one, its error code as a diagnostic id
     * @throws NullPointerException if {@code theme} is {@code null}
     */
    public CompletionStage<ExportResult> exportPackage(final ThemePackageData theme) {
        Objects.requireNonNull(theme, "theme");
        final byte[] archive = ThemePackageArchive.encode(theme);
        return files.request(new UserFileRequest(
                        "ui-theme.package.export",
                        "Export Theme Package",
                        List.of("zip"),
                        UserFileMode.WRITE,
                        UserFileLifetime.ONE_OPERATION))
                .thenCompose(requested -> {
                    if (requested.status() == UserFileRequestStatus.CANCELED) {
                        return CompletableFuture.completedFuture(
                                new ExportResult(ExportOutcome.CANCELED, Optional.empty()));
                    }
                    if (requested.status() != UserFileRequestStatus.GRANTED) {
                        return CompletableFuture.completedFuture(new ExportResult(
                                ExportOutcome.UNAVAILABLE,
                                requested.error().map(error -> error.code().name())));
                    }
                    final UserFileHandle handle = requested.handle().orElseThrow();
                    try {
                        return files.writeBytesAtomic(handle, archive)
                                .handle((written, failure) -> failure == null
                                        ? exported(written)
                                        : new ExportResult(ExportOutcome.FAILED, Optional.empty()))
                                .whenComplete((result, failure) -> handle.close());
                    } catch (RuntimeException synchronous) {
                        closeSuppressing(handle, synchronous);
                        throw synchronous;
                    }
                });
    }

    /**
     * Reads and decodes a theme archive from a file the user picks.
     *
     * <p>Bounded read: an archive over the maximum size is reported as {@code FAILED} with the
     * {@code ARCHIVE_TOO_LARGE} diagnostic rather than partially decoded. A readable but malformed
     * archive is {@code INVALID}. Nothing is stored — the decoded package is returned to the caller to
     * persist. The one-operation handle is closed on every completion path — decoded, refused, failed,
     * or thrown synchronously.
     *
     * @return a stage of the outcome, the decoded package on {@code IMPORTED} only, and a diagnostic id
     *     on failure
     */
    public CompletionStage<ImportResult> importPackage() {
        return files.request(new UserFileRequest(
                        "ui-theme.package.import",
                        "Import Theme Package",
                        List.of("zip"),
                        UserFileMode.READ,
                        UserFileLifetime.ONE_OPERATION))
                .thenCompose(requested -> {
                    if (requested.status() == UserFileRequestStatus.CANCELED) {
                        return CompletableFuture.completedFuture(
                                new ImportResult(ImportOutcome.CANCELED, Optional.empty(), Optional.empty()));
                    }
                    if (requested.status() != UserFileRequestStatus.GRANTED) {
                        return CompletableFuture.completedFuture(new ImportResult(
                                ImportOutcome.UNAVAILABLE,
                                Optional.empty(),
                                requested.error().map(error -> error.code().name())));
                    }
                    final UserFileHandle handle = requested.handle().orElseThrow();
                    try {
                        return files.readBytes(handle, ThemePackageArchive.MAX_ARCHIVE_BYTES)
                                .handle((read, failure) -> failure == null
                                        ? decodeRead(read)
                                        : new ImportResult(ImportOutcome.FAILED, Optional.empty(), Optional.empty()))
                                .whenComplete((result, failure) -> handle.close());
                    } catch (RuntimeException synchronous) {
                        closeSuppressing(handle, synchronous);
                        throw synchronous;
                    }
                });
    }

    /** Closes a one-operation handle and keeps the original failure authoritative, as try-with-resources did. */
    private static void closeSuppressing(final UserFileHandle handle, final RuntimeException primary) {
        try {
            handle.close();
        } catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private static ExportResult exported(final UserFileWriteResult written) {
        return written.written()
                ? new ExportResult(ExportOutcome.EXPORTED, Optional.empty())
                : new ExportResult(
                        ExportOutcome.FAILED,
                        written.error().map(error -> error.code().name()));
    }

    private static ImportResult decodeRead(final UserFileReadResult<byte[]> read) {
        if (read.error().isPresent() || read.truncated()) {
            return new ImportResult(
                    ImportOutcome.FAILED,
                    Optional.empty(),
                    read.error().map(error -> error.code().name()).or(() -> Optional.of("ARCHIVE_TOO_LARGE")));
        }
        final ThemePackageArchive.DecodeResult decoded =
                ThemePackageArchive.decode(read.value().orElseThrow());
        return decoded.valid()
                ? new ImportResult(ImportOutcome.IMPORTED, decoded.theme(), Optional.empty())
                : new ImportResult(ImportOutcome.INVALID, Optional.empty(), decoded.issueCode());
    }

    /**
     * Outcome of an {@link ThemePackageTransferService#exportPackage} attempt: written,
     * cancelled by the user, refused by the host, or failed to write.
     */
    public enum ExportOutcome {
        EXPORTED,
        CANCELED,
        UNAVAILABLE,
        FAILED
    }

    /**
     * Outcome of an {@link ThemePackageTransferService#importPackage} attempt: decoded and
     * returned, cancelled by the user, refused by the host, malformed ({@code INVALID}), or
     * unreadable.
     */
    public enum ImportOutcome {
        IMPORTED,
        CANCELED,
        UNAVAILABLE,
        INVALID,
        FAILED
    }

    /**
     * Outcome of an export attempt.
     *
     * @param outcome whether the archive was written, cancelled by the user, refused by the host, or
     *     failed to write
     * @param diagnosticId the host's error code when it supplied one, otherwise empty
     */
    public record ExportResult(ExportOutcome outcome, Optional<String> diagnosticId) {
        public ExportResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }

    /**
     * Outcome of an import attempt.
     *
     * <p>The compact constructor enforces that a package is present exactly when the outcome is
     * {@code IMPORTED}; any other pairing throws {@link IllegalArgumentException}.
     *
     * @param outcome whether the archive was imported, cancelled, refused, malformed, or unreadable
     * @param theme the decoded package, present only on {@code IMPORTED}
     * @param diagnosticId the host error code or archive issue code on failure, otherwise empty
     */
    public record ImportResult(ImportOutcome outcome, Optional<ThemePackageData> theme, Optional<String> diagnosticId) {
        public ImportResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            theme = Objects.requireNonNull(theme, "theme");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
            if ((outcome == ImportOutcome.IMPORTED) != theme.isPresent()) {
                throw new IllegalArgumentException("only imported results contain a theme");
            }
        }
    }
}
