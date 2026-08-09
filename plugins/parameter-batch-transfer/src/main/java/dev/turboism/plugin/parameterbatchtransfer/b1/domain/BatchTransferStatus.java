package dev.turboism.plugin.parameterbatchtransfer.b1.domain;

import dev.turboism.sdk.PreviewApi;

/** User-facing outcome of one batch-transfer apply pass. */
@PreviewApi
public enum BatchTransferStatus {
    /** Every non-trivial row transferred. */
    APPLIED,
    /** At least one row failed while others transferred. */
    PARTIAL,
    /** No row transferred (all rows kept their source parameter). */
    NO_CHANGES
}
