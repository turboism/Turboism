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

    public record ExportResult(ExportOutcome outcome, Optional<String> diagnosticId) {
        public ExportResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId");
        }
    }

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
