package dev.turboism.plugin.uitheme.service;

import dev.turboism.plugin.uitheme.b1.domain.ThemePackageArchive;
import dev.turboism.plugin.uitheme.b1.domain.ThemePackageData;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.sdk.ui.UserFileWriteResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Imports and exports bounded theme archives through opaque user-file handles. */
public final class ThemePackageTransferService {

    private final UserFileAccessService files;

    public ThemePackageTransferService(final UserFileAccessService files) {
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * Encodes the package and writes it atomically to a file the user picks.
     *
     * <p>Blocks on the file request and the write. The handle is granted for one operation only and is
     * closed before returning, so no path or lasting file access is retained. The user cancelling is
     * {@code CANCELED}, a host that refuses the request is {@code UNAVAILABLE}, and a failed write is
     * {@code FAILED}; none of them throw.
     *
     * @param theme the package to write
     * @return the outcome and, when the host supplied one, its error code as a diagnostic id
     * @throws NullPointerException if {@code theme} is {@code null}
     */
    public ExportResult exportPackage(final ThemePackageData theme) {
        Objects.requireNonNull(theme, "theme");
        final UserFileRequestResult requested = files.request(new UserFileRequest(
            "ui-theme.package.export",
            "Export Theme Package",
            List.of("zip"),
            UserFileMode.WRITE,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().join();
        if (requested.status() == UserFileRequestStatus.CANCELED) {
            return new ExportResult(ExportOutcome.CANCELED, Optional.empty());
        }
        if (requested.status() != UserFileRequestStatus.GRANTED) {
            return new ExportResult(
                ExportOutcome.UNAVAILABLE,
                requested.error().map(error -> error.code().name())
            );
        }
        final UserFileHandle handle = requested.handle().orElseThrow();
        try (handle) {
            final UserFileWriteResult written = files.writeBytesAtomic(
                handle,
                ThemePackageArchive.encode(theme)
            ).toCompletableFuture().join();
            return written.written()
                ? new ExportResult(ExportOutcome.EXPORTED, Optional.empty())
                : new ExportResult(
                    ExportOutcome.FAILED,
                    written.error().map(error -> error.code().name())
                );
        }
    }

    /**
     * Reads and decodes a theme archive from a file the user picks.
     *
     * <p>Bounded read: an archive over the maximum size is reported as {@code FAILED} with the
     * {@code ARCHIVE_TOO_LARGE} diagnostic rather than partially decoded. A readable but malformed
     * archive is {@code INVALID}. Nothing is stored — the decoded package is returned to the caller to
     * persist. The one-operation handle is closed before returning.
     *
     * @return the outcome, the decoded package on {@code IMPORTED} only, and a diagnostic id on failure
     */
    public ImportResult importPackage() {
        final UserFileRequestResult requested = files.request(new UserFileRequest(
            "ui-theme.package.import",
            "Import Theme Package",
            List.of("zip"),
            UserFileMode.READ,
            UserFileLifetime.ONE_OPERATION
        )).toCompletableFuture().join();
        if (requested.status() == UserFileRequestStatus.CANCELED) {
            return new ImportResult(ImportOutcome.CANCELED, Optional.empty(), Optional.empty());
        }
        if (requested.status() != UserFileRequestStatus.GRANTED) {
            return new ImportResult(
                ImportOutcome.UNAVAILABLE,
                Optional.empty(),
                requested.error().map(error -> error.code().name())
            );
        }
        final UserFileHandle handle = requested.handle().orElseThrow();
        try (handle) {
            final var read = files.readBytes(handle, ThemePackageArchive.MAX_ARCHIVE_BYTES)
                .toCompletableFuture().join();
            if (read.error().isPresent() || read.truncated()) {
                return new ImportResult(
                    ImportOutcome.FAILED,
                    Optional.empty(),
                    read.error().map(error -> error.code().name())
                        .or(() -> Optional.of("ARCHIVE_TOO_LARGE"))
                );
            }
            final ThemePackageArchive.DecodeResult decoded = ThemePackageArchive.decode(
                read.value().orElseThrow()
            );
            return decoded.valid()
                ? new ImportResult(ImportOutcome.IMPORTED, decoded.theme(), Optional.empty())
                : new ImportResult(ImportOutcome.INVALID, Optional.empty(), decoded.issueCode());
        }
    }

    public enum ExportOutcome {
        EXPORTED,
        CANCELED,
        UNAVAILABLE,
        FAILED
    }

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
    public record ImportResult(
        ImportOutcome outcome,
        Optional<ThemePackageData> theme,
        Optional<String> diagnosticId
    ) {
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
