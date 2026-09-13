package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.List;
import java.util.Optional;

/** Immutable operation-time projection; never retains the form or resolver. */
public record ArtMeshPropertyCapture(
    HistoryTarget target,
    HistoryEditContext context,
    String property,
    String value,
    String degradationCode
) {
    /** Must be called on the authoring thread, before/after the actual operation. */
    public static Optional<ArtMeshPropertyCapture> capture(
        final VerifiedMemberResolver resolver, final Object form, final String property
    ) {
        try {
            return Optional.of(ListUndoDecoder.captureProperty(resolver, form, property));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    /** Builds an incomplete before-only annotation, or freezes a matching actual-after projection. */
    public HistoryEntryDetail detail(
        final String label, final HistoryOrigin origin, final Optional<ArtMeshPropertyCapture> after
    ) {
        final boolean sameScope = after.filter(value -> target.equals(value.target())
            && context.equals(value.context()) && property.equals(value.property())).isPresent();
        final String code = !degradationCode.isEmpty() ? degradationCode
            : after.isEmpty() ? "history.capture.after-unavailable"
            : !sameScope ? "history.form-scope-unresolved"
            : after.orElseThrow().degradationCode();
        return new HistoryEntryDetail(label,
            code.isEmpty() ? HistoryAction.DetailLevel.FULL : HistoryAction.DetailLevel.PARTIAL,
            origin, List.of(target), List.of(new HistoryChange(HistoryChange.Operation.SET,
                Optional.of(0), Optional.of(property), Optional.of(value),
                sameScope ? Optional.of(after.orElseThrow().value()) : Optional.empty(), context)),
            Optional.empty(), code.isEmpty() ? Optional.empty() : Optional.of(code));
    }
}
