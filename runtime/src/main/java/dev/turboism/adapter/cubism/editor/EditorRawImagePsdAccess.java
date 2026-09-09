package dev.turboism.adapter.cubism.editor;

import dev.turboism.sdk.cubism.id.RawImageId;

import java.util.Objects;

/**
 * Package-private T003 seam for PSD raw-image targeting.
 *
 * <p>This slice owns only deterministic target identity selection. Native export/parse invocation,
 * model guards, and file ownership remain unconnected until the later runtime wiring task. The
 * candidate's native value is package-private and cannot escape to the SDK.</p>
 */
final class EditorRawImagePsdAccess {
    private EditorRawImagePsdAccess() {
    }

    /**
     * Selects exactly one raw image by its host-issued RawImageId.
     *
     * <p>Name, path, and list order are never used as identity. A duplicate ID is an explicit
     * ambiguity so a caller cannot accidentally export or replace the wrong native object.</p>
     */
    static <T> TargetSelection<T> selectByRawImageId(
        final Iterable<RawImageCandidate<T>> candidates,
        final RawImageId targetId
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(targetId, "targetId");

        RawImageCandidate<T> match = null;
        for (final RawImageCandidate<T> candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!targetId.equals(candidate.id())) {
                continue;
            }
            if (match != null) {
                return new TargetSelection<>(SelectionStatus.DUPLICATE, null);
            }
            match = candidate;
        }
        return match == null
            ? new TargetSelection<>(SelectionStatus.NOT_FOUND, null)
            : new TargetSelection<>(SelectionStatus.MATCHED, match);
    }

    /** Internal raw-image candidate; native host values never cross this package boundary. */
    record RawImageCandidate<T>(RawImageId id, String name, T nativeSource) {
        RawImageCandidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nativeSource, "nativeSource");
        }
    }

    /** Internal selection result with an explicit ambiguity state. */
    record TargetSelection<T>(SelectionStatus status, RawImageCandidate<T> candidate) {
        TargetSelection {
            Objects.requireNonNull(status, "status");
            if (status == SelectionStatus.MATCHED) {
                Objects.requireNonNull(candidate, "candidate");
            } else if (candidate != null) {
                throw new IllegalArgumentException("non-matched selection must not carry a candidate");
            }
        }
    }

    enum SelectionStatus {
        MATCHED,
        NOT_FOUND,
        DUPLICATE
    }
}
