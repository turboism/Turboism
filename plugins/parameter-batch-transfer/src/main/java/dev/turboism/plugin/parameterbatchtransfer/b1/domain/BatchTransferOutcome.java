package dev.turboism.plugin.parameterbatchtransfer.b1.domain;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Counted result of one batch-transfer apply pass. */
@PreviewApi
public record BatchTransferOutcome(
    int applied,
    int failed,
    BatchTransferStatus status
) {
    public BatchTransferOutcome {
        if (applied < 0 || failed < 0) {
            throw new IllegalArgumentException("applied and failed must not be negative");
        }
        status = Objects.requireNonNull(status, "status");
    }

    public static BatchTransferOutcome of(final int applied, final int failed) {
        final BatchTransferStatus status;
        if (applied == 0 && failed == 0) {
            status = BatchTransferStatus.NO_CHANGES;
        } else if (failed > 0) {
            status = BatchTransferStatus.PARTIAL;
        } else {
            status = BatchTransferStatus.APPLIED;
        }
        return new BatchTransferOutcome(applied, failed, status);
    }
}
