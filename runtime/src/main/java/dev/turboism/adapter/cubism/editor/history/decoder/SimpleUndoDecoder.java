package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;

import java.util.List;

/** Decodes only exact {@code SimpleUndo} snapshots backed by a reviewed domain descriptor. */
final class SimpleUndoDecoder implements NativeHistoryDecoder {

    @Override
    public NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth,
        final NativeHistoryDecoderRegistry registry
    ) {
        final VerifiedMemberResolver resolver = context.resolver();
        if (!NativeHistoryDecoderRegistry.authorized(
            resolver,
            EditorHistorySemanticSelectorContract.ART_MESH_FORM_REQUIRED_ALIASES
        )) {
            return NativeHistoryDecodeResult.unsupported("history.semantic-operation-unmapped");
        }
        final Object target = resolver.invoke("cubism.editor-history.semantic.simple.target", entry);
        final Object undo = resolver.invoke("cubism.editor-history.semantic.simple.undo", entry);
        final Object redo = resolver.invoke("cubism.editor-history.semantic.simple.redo", entry);
        if (!isArtMeshForm(resolver, target) || !isArtMeshForm(resolver, undo)) {
            return NativeHistoryDecodeResult.unsupported("history.semantic-operation-unmapped");
        }
        // The host stores no post state when an entry is committed: SimpleUndo's constructor
        // assigns targetData and undoData only, and redoData is filled in lazily inside undo().
        // A freshly committed entry therefore has to be compared against the live target, and only
        // the decode context can say whether that value is still this entry's own result rather
        // than a later edit's.
        final Object post;
        if (redo != null) {
            post = redo;
        } else if (context.mayReadLiveTarget(entry) && isArtMeshForm(resolver, target)) {
            post = target;
        } else {
            return NativeHistoryDecodeResult.unsupported("history.detail.post-state-unavailable");
        }
        if (!isArtMeshForm(resolver, post)) {
            return NativeHistoryDecodeResult.unsupported("history.detail.value-unsupported");
        }
        return ListUndoDecoder.decodeArtMeshForms(
            resolver,
            context.boundedLabel(label),
            List.of(undo),
            List.of(post)
        );
    }

    private static boolean isArtMeshForm(
        final VerifiedMemberResolver resolver,
        final Object value
    ) {
        return value != null
            && resolver.isExactInstance("cubism.editor-history.semantic.art-mesh-form.class", value);
    }
}
