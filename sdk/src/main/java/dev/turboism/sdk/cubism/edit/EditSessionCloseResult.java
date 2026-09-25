package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;
import java.util.Optional;

/**
 * The typed outcome of {@link EditSession#close()} or {@link EditSession#cancel()}.
 *
 * <p>Returned instead of throwing when the session reached a terminal state: a committed close,
 * a cancellation (with the {@link CancelSource} that caused it), or a failed close carrying a
 * diagnostic id.
 *
 * @param outcome how the session terminated
 * @param cancelSource who cancelled the session; present iff {@code outcome} is
 *     {@link EditSessionCloseOutcome#CANCELLED}
 * @param diagnosticId stable diagnostic id; present iff {@code outcome} is
 *     {@link EditSessionCloseOutcome#FAILED}
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record EditSessionCloseResult(
        EditSessionCloseOutcome outcome,
        Optional<CancelSource> cancelSource,
        Optional<String> diagnosticId) {

    public EditSessionCloseResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(cancelSource, "cancelSource");
        Objects.requireNonNull(diagnosticId, "diagnosticId");
        switch (outcome) {
            case COMMITTED -> {
                if (cancelSource.isPresent() || diagnosticId.isPresent()) {
                    throw new IllegalArgumentException(
                        "committed close must not carry a cancel source or diagnostic id");
                }
            }
            case CANCELLED -> {
                if (cancelSource.isEmpty() || diagnosticId.isPresent()) {
                    throw new IllegalArgumentException(
                        "cancelled close requires a cancel source and no diagnostic id");
                }
            }
            case FAILED -> {
                if (diagnosticId.isEmpty() || diagnosticId.get().isBlank() || cancelSource.isPresent()) {
                    throw new IllegalArgumentException(
                        "failed close requires a non-blank diagnostic id and no cancel source");
                }
            }
        }
    }

    /** Returns a committed close result. */
    public static EditSessionCloseResult committed() {
        return new EditSessionCloseResult(
            EditSessionCloseOutcome.COMMITTED, Optional.empty(), Optional.empty());
    }

    /** Returns a cancelled close result recording who cancelled the session. */
    public static EditSessionCloseResult cancelled(final CancelSource source) {
        return new EditSessionCloseResult(
            EditSessionCloseOutcome.CANCELLED,
            Optional.of(Objects.requireNonNull(source, "source")),
            Optional.empty());
    }

    /** Returns a failed close result carrying a stable diagnostic id. */
    public static EditSessionCloseResult failed(final String diagnosticId) {
        return new EditSessionCloseResult(
            EditSessionCloseOutcome.FAILED,
            Optional.empty(),
            Optional.of(Objects.requireNonNull(diagnosticId, "diagnosticId")));
    }
}
