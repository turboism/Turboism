package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;

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
        withholdEarlierWriters(resolver, nativeChildren, projectedCount, context);
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
        if (!truncated) {
            final Optional<HistoryEntryDetail> coalesced =
                PartMembershipRelations.coalesce(children, context.boundedLabel(label));
            if (coalesced.isPresent()) {
                return NativeHistoryDecodeResult.decoded(coalesced.orElseThrow());
            }
        }
        final boolean full = !truncated
            && !children.isEmpty()
            && children.stream().allMatch(child ->
                child.detailLevel() == HistoryAction.DetailLevel.FULL);
        final HistoryGroup group = new HistoryGroup(
            Optional.empty(),
            Math.max(count, nativeChildren.size()),
            children,
            truncated
        );
        // A group whose every child proves the same change on the same object is one operator
        // action: the subject is hoisted so the row can name it, while the bounded children stay
        // attached for audit.
        if (!truncated) {
            final Optional<HistoryEntryDetail> hoisted =
                hoistUniformSubject(children, context.boundedLabel(label), group);
            if (hoisted.isPresent()) {
                return NativeHistoryDecodeResult.decoded(hoisted.orElseThrow());
            }
        }
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            context.boundedLabel(label),
            full ? HistoryAction.DetailLevel.FULL : HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(group),
            full ? Optional.empty() : Optional.of(
                truncated ? "history.detail.group-truncated" : "history.detail.group-partial"
            )
        );
        return NativeHistoryDecodeResult.decoded(detail);
    }

    /**
     * Projects a provably single-subject group as that subject plus the one change every child
     * agrees on.
     *
     * <p>A canvas drag commits one snapshot child per keyform of the same object. Hoisting is only
     * allowed when every projected child names the same target with the same kind of change —
     * a group containing a label-only, multi-change, or different-subject child describes more
     * than one fact and stays a plain group row.</p>
     */
    private static Optional<HistoryEntryDetail> hoistUniformSubject(
        final List<HistoryEntryDetail> children,
        final String label,
        final HistoryGroup group
    ) {
        if (children.isEmpty()) return Optional.empty();
        HistoryTarget subject = null;
        HistoryChange.Operation operation = null;
        String property = null;
        HistoryEditContext context = null;
        Optional<String> before = null;
        Optional<String> after = null;
        boolean uniformContext = true;
        boolean uniformValues = true;
        boolean full = true;
        for (final HistoryEntryDetail child : children) {
            if (child.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY
                || child.targets().size() != 1 || child.changes().size() != 1) {
                return Optional.empty();
            }
            final HistoryTarget childTarget = child.targets().get(0);
            final HistoryChange childChange = child.changes().get(0);
            if (childChange.relation().isPresent()) return Optional.empty();
            if (subject == null) {
                subject = childTarget;
                operation = childChange.operation();
                property = childChange.property().orElse(null);
                context = childChange.context();
                before = childChange.before();
                after = childChange.after();
            } else {
                if (!subject.equals(childTarget)
                    || operation != childChange.operation()
                    || !java.util.Objects.equals(property, childChange.property().orElse(null))) {
                    return Optional.empty();
                }
                uniformContext = uniformContext && context.equals(childChange.context());
                uniformValues = uniformValues
                    && before.equals(childChange.before())
                    && after.equals(childChange.after());
            }
            full = full && child.detailLevel() == HistoryAction.DetailLevel.FULL;
        }
        final HistoryEditContext projectedContext = uniformContext
            ? context
            : new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of());
        // When children agree on subject, operation and property but not on values, the hoisted
        // row still names what was done to what — it is PARTIAL because the single projected
        // change cannot carry one honest before/after pair.
        final boolean complete = full && uniformValues;
        return Optional.of(new HistoryEntryDetail(
            label,
            complete ? HistoryAction.DetailLevel.FULL : HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(subject),
            List.of(new HistoryChange(
                operation,
                Optional.of(0),
                Optional.ofNullable(property),
                uniformValues ? before : Optional.empty(),
                uniformValues ? after : Optional.empty(),
                projectedContext
            )),
            Optional.of(group),
            complete ? Optional.empty() : Optional.of(
                uniformValues ? "history.detail.group-partial" : "history.detail.group-values-vary"
            )
        ));
    }

    /**
     * Withholds the live-target read from every child that is not the last writer of its object.
     *
     * <p>A group may write the same object more than once, and the live target then holds the
     * <em>last</em> writer's result. Reading it for an earlier child would report that child's post
     * state as a later one's, so only the last writer of each object may use it. Identity is the
     * live object itself, which is exactly what the read would use.</p>
     *
     * <p>Nothing is withheld when the Simple family is not authorised: its children decode as
     * unsupported either way, so there is nothing to protect.</p>
     */
    private static void withholdEarlierWriters(
        final VerifiedMemberResolver resolver,
        final List<?> nativeChildren,
        final int projectedCount,
        final NativeHistoryDecodeContext context
    ) {
        if (!NativeHistoryDecoderRegistry.authorized(
            resolver,
            EditorHistorySemanticSelectorContract.SIMPLE_REQUIRED_ALIASES
        )) {
            return;
        }
        final java.util.IdentityHashMap<Object, Integer> lastWriter = new java.util.IdentityHashMap<>();
        for (int index = 0; index < projectedCount; index++) {
            final Object child = nativeChildren.get(index);
            final Object target = simpleTarget(resolver, child);
            if (target != null) lastWriter.put(target, index);
        }
        for (int index = 0; index < projectedCount; index++) {
            final Object child = nativeChildren.get(index);
            final Object target = simpleTarget(resolver, child);
            if (target == null) continue;
            final Integer last = lastWriter.get(target);
            if (last == null || last != index) context.withholdLivePostState(child);
        }
    }

    /** Reads a SimpleUndo child's live target, or {@code null} when the child is not one. */
    private static Object simpleTarget(
        final VerifiedMemberResolver resolver,
        final Object child
    ) {
        try {
            if (!resolver.isExactInstance("cubism.editor-history.semantic.simple.class", child)) {
                return null;
            }
            return resolver.invoke("cubism.editor-history.semantic.simple.target", child);
        } catch (RuntimeException unavailable) {
            return null;
        }
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
