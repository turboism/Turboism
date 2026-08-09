package dev.turboism.plugin.parameterbatchtransfer.b1.domain;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.Objects;

/**
 * One dialog row: a bound source snapshot, the chosen target parameter,
 * and whether the transferred binding is inverted.
 */
@PreviewApi
public record BatchTransferRow(
    BoundParameterSnapshot snapshot,
    ParameterId target,
    boolean invert
) {
    public BatchTransferRow {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        target = Objects.requireNonNull(target, "target");
    }

    /** Default row: target stays the source parameter (a no-op until changed). */
    public static BatchTransferRow keep(final BoundParameterSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new BatchTransferRow(snapshot, snapshot.parameterId(), false);
    }
}
