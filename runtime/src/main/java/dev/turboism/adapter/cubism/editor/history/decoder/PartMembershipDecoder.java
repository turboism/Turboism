package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.Optional;

/**
 * Decoder for the exact native Part-membership entry the Editor itself creates.
 *
 * <p>A native Parts-tree drag is performed by the host, not by Turboism, so its history row would
 * otherwise carry only the host label. The admitted entry is exact: it names the Part the child
 * joins or leaves and the child itself. The opposite endpoint is genuinely not part of this entry,
 * so it stays {@link HistoryRelationChange.State#UNKNOWN} rather than being guessed, and the row is
 * reported as {@code PARTIAL}. {@link GroupUndoDecoder} combines the sibling remove/join entries of
 * one host edit into the full previous-and-next relation when both are present.</p>
 */
final class PartMembershipDecoder implements NativeHistoryDecoder {

    private static final String CLASS_ALIAS = "cubism.editor-history.semantic.part-membership.class";

    boolean supports(final VerifiedMemberResolver resolver, final Object entry) {
        return NativeHistoryDecoderRegistry.authorized(
            resolver,
            EditorHistorySemanticSelectorContract.PART_MEMBERSHIP_REQUIRED_ALIASES
        ) && resolver.isExactInstance(CLASS_ALIAS, entry);
    }

    @Override
    public NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth,
        final NativeHistoryDecoderRegistry registry
    ) {
        final VerifiedMemberResolver resolver = context.resolver();
        final Object rawPart = resolver.invoke("cubism.editor-history.semantic.part-membership.part", entry);
        final Object rawChild = resolver.invoke("cubism.editor-history.semantic.part-membership.child", entry);
        final Object rawIsAdd = resolver.invoke(
            "cubism.editor-history.semantic.part-membership.is-add",
            entry
        );
        if (!(rawIsAdd instanceof Boolean isAdd)) {
            return NativeHistoryDecodeResult.failed("history.detail.part-membership-shape-invalid");
        }
        if (rawPart == null || rawChild == null) {
            return NativeHistoryDecodeResult.failed("history.detail.part-membership-target-unavailable");
        }
        final Optional<HistoryTarget> child = PartMembershipRelations.target(resolver, rawChild);
        final Optional<HistoryTarget> part = PartMembershipRelations.partTarget(resolver, rawPart);
        if (child.isEmpty() || part.isEmpty()) {
            return NativeHistoryDecodeResult.failed("history.detail.part-membership-target-unavailable");
        }
        final HistoryTarget childTarget = child.orElseThrow();
        final HistoryTarget partTarget = part.orElseThrow();
        if (PartMembershipRelations.sameIdentity(childTarget, partTarget)) {
            return NativeHistoryDecodeResult.failed("history.detail.part-membership-self-target");
        }
        final HistoryRelationChange.Endpoint endpoint = new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.TARGET,
            Optional.of(partTarget)
        );
        final HistoryRelationChange relation = isAdd
            ? new HistoryRelationChange(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                PartMembershipRelations.unknownEndpoint(),
                endpoint
            )
            : new HistoryRelationChange(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                endpoint,
                PartMembershipRelations.unknownEndpoint()
            );
        return NativeHistoryDecodeResult.decoded(PartMembershipRelations.detail(
            context.boundedLabel(label),
            childTarget,
            relation,
            HistoryAction.DetailLevel.PARTIAL,
            Optional.of(isAdd ? "history.relation.before-unknown" : "history.relation.after-unknown")
        ));
    }
}
