package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.sdk.cubism.history.HistoryEntryDetail;

import java.util.Objects;
import java.util.Optional;

/** Internal result of one optional native history decoder attempt. */
public record NativeHistoryDecodeResult(
    Outcome outcome,
    Optional<HistoryEntryDetail> detail,
    String diagnosticId
) {

    public NativeHistoryDecodeResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        detail = Objects.requireNonNull(detail, "detail");
        diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId").strip();
        if (diagnosticId.isEmpty()) {
            throw new IllegalArgumentException("diagnosticId must not be blank");
        }
        if (outcome == Outcome.DECODED && detail.isEmpty()) {
            throw new IllegalArgumentException("decoded result requires detail");
        }
    }

    static NativeHistoryDecodeResult decoded(final HistoryEntryDetail detail) {
        return new NativeHistoryDecodeResult(
            Outcome.DECODED,
            Optional.of(detail),
            "history.detail.native-decoded"
        );
    }

    static NativeHistoryDecodeResult unsupported(final String diagnosticId) {
        return new NativeHistoryDecodeResult(Outcome.UNSUPPORTED, Optional.empty(), diagnosticId);
    }

    static NativeHistoryDecodeResult failed(final String diagnosticId) {
        return new NativeHistoryDecodeResult(Outcome.FAILED, Optional.empty(), diagnosticId);
    }

    public enum Outcome {
        DECODED,
        UNSUPPORTED,
        FAILED
    }
}
