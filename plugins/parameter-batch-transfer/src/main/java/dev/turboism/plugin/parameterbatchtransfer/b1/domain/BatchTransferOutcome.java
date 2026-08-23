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

    /**
     * Derives the outcome status from the counts, so status and counts can never disagree.
     *
     * <p>Nothing attempted is {@code NO_CHANGES}; any failure at all is {@code PARTIAL}, even when
     * nothing succeeded; otherwise {@code APPLIED}.
     *
     * @param applied number of transfers that took effect; must not be negative
     * @param failed number of transfers that did not; must not be negative
     * @return the outcome carrying both counts and the derived status
     * @throws IllegalArgumentException if either count is negative
     */
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
