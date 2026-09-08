package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryOrigin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Bounded decoder for the exact verified {@code GroupUndo} class. */
final class GroupUndoDecoder implements NativeHistoryDecoder {

    private static final int MAX_GROUP_CHILDREN = 64;
    @Override
    public NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth,
        final NativeHistoryDecoderRegistry registry
    ) {
        final VerifiedMemberResolver resolver = context.resolver();
        final Object rawChildren = resolver.invoke(
            "cubism.editor-history.semantic.group.edits",
            entry
        );
        final Object rawCount = resolver.invoke(
            "cubism.editor-history.semantic.group.count",
            entry
        );
        if (!(rawChildren instanceof List<?> nativeChildren) || !(rawCount instanceof Integer count)) {
            return NativeHistoryDecodeResult.failed("history.detail.group-shape-invalid");
        }
        final ArrayList<HistoryEntryDetail> children = new ArrayList<>();
        boolean truncated = count != nativeChildren.size();
        final int projectedCount = Math.min(nativeChildren.size(), MAX_GROUP_CHILDREN);
        for (int index = 0; index < projectedCount; index++) {
            final Object child = nativeChildren.get(index);
            final String childLabel = childLabel(resolver, child, context);
            final NativeHistoryDecodeResult result = registry.decode(
                child,
                childLabel,
                context,
                depth + 1
            );
            final HistoryEntryDetail detail = result.detail().orElseGet(() ->
                HistoryEntryDetail.labelOnly(
                    childLabel,
                    HistoryOrigin.hostUnattributed(),
                    result.diagnosticId()
                )
            );
            children.add(detail);
            if (result.diagnosticId().contains("limit")
                || detail.group().map(HistoryGroup::truncated).orElse(false)) {
                truncated = true;
            }
        }
        if (nativeChildren.size() > projectedCount) truncated = true;
        final boolean full = !truncated
            && !children.isEmpty()
            && children.stream().allMatch(child ->
                child.detailLevel() == HistoryAction.DetailLevel.FULL);
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            context.boundedLabel(label),
            full ? HistoryAction.DetailLevel.FULL : HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(
                Optional.empty(),
                Math.max(count, nativeChildren.size()),
                children,
                truncated
            )),
            full ? Optional.empty() : Optional.of(
                truncated ? "history.detail.group-truncated" : "history.detail.group-partial"
            )
        );
        return NativeHistoryDecodeResult.decoded(detail);
    }

    private static String childLabel(
        final VerifiedMemberResolver resolver,
        final Object child,
        final NativeHistoryDecodeContext context
    ) {
        try {
            final Object value = resolver.invoke(
                "cubism.editor-history.entry.presentation-name",
                child
            );
            return value instanceof String text
                ? context.boundedLabel(text)
                : "History entry";
        } catch (RuntimeException failure) {
            return "History entry";
        }
    }
}
