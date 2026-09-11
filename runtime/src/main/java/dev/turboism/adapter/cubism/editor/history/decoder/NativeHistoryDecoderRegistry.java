package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;

import java.util.Objects;
import java.util.Set;

/** Exact-class, allowlist-only native history decoder registry. */
public final class NativeHistoryDecoderRegistry {

    private final GroupUndoDecoder group = new GroupUndoDecoder();
    private final PartMembershipDecoder partMembership = new PartMembershipDecoder();
    private final AddOrRemoveDecoder addOrRemove = new AddOrRemoveDecoder();
    private final PropertyUndoDecoder property = new PropertyUndoDecoder();
    private final SimpleUndoDecoder simple = new SimpleUndoDecoder();
    private final ListUndoDecoder list = new ListUndoDecoder();

    /** Decodes one exact native history entry through independently authorized families. */
    public NativeHistoryDecodeResult decode(
        final VerifiedMemberResolver resolver,
        final Object entry,
        final String label
    ) {
        return decode(resolver, entry, label, false);
    }

    /**
     * Decodes one exact native history entry, optionally admitting a live-target post-state read.
     *
     * @param livePostStateAllowed whether the caller has proved that this entry is still the undo
     *     manager's current tip, so no later edit has overwritten its result. It stays false for
     *     the history projection, which decodes entries at any cursor position.
     */
    public NativeHistoryDecodeResult decode(
        final VerifiedMemberResolver resolver,
        final Object entry,
        final String label,
        final boolean livePostStateAllowed
    ) {
        return decode(
            Objects.requireNonNull(entry, "entry"),
            Objects.requireNonNull(label, "label"),
            new NativeHistoryDecodeContext(
                Objects.requireNonNull(resolver, "resolver"),
                livePostStateAllowed
            ),
            0
        );
    }

    NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth
    ) {
        if (!context.enter(entry, depth)) {
            return NativeHistoryDecodeResult.unsupported("history.detail.node-or-depth-limit");
        }
        final VerifiedMemberResolver resolver = context.resolver();
        try {
            if (authorized(resolver, EditorHistorySemanticSelectorContract.GROUP_REQUIRED_ALIASES)
                && resolver.isExactInstance("cubism.editor-history.semantic.group.class", entry)) {
                return group.decode(entry, label, context, depth, this);
            }
            if (partMembership.supports(resolver, entry)) {
                return partMembership.decode(entry, label, context, depth, this);
            }
            if (addOrRemove.supports(resolver, entry)) {
                return addOrRemove.decode(entry, label, context, depth, this);
            }
            if (authorized(resolver, EditorHistorySemanticSelectorContract.PROPERTY_REQUIRED_ALIASES)
                && resolver.isExactInstance("cubism.editor-history.semantic.property.class", entry)) {
                return property.decode(entry, label, context, depth, this);
            }
            if (authorized(resolver, EditorHistorySemanticSelectorContract.SIMPLE_REQUIRED_ALIASES)
                && resolver.isExactInstance("cubism.editor-history.semantic.simple.class", entry)) {
                return simple.decode(entry, label, context, depth, this);
            }
            if (authorized(resolver, EditorHistorySemanticSelectorContract.LIST_REQUIRED_ALIASES)
                && resolver.isExactInstance("cubism.editor-history.semantic.list.class", entry)) {
                return list.decode(entry, label, context, depth, this);
            }
            return NativeHistoryDecodeResult.unsupported("history.detail.class-unsupported");
        } catch (RuntimeException failure) {
            return NativeHistoryDecodeResult.failed("history.detail.decoder-failed");
        }
    }

    static boolean authorized(final VerifiedMemberResolver resolver, final Set<String> aliases) {
        try {
            return resolver.authorizesFeature(
                EditorHistorySemanticSelectorContract.ADAPTER_SLICE_ID,
                EditorHistorySemanticSelectorContract.CAPABILITY_ID,
                aliases
            );
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
