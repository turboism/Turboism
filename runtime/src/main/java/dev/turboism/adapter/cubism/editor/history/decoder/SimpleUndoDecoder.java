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
        // Live target state is not historical post-state, even at the current cursor:
        // a later child in the same group may already have changed the same form.
        if (redo == null) {
            return NativeHistoryDecodeResult.unsupported("history.detail.post-state-unavailable");
        }
        if (!isArtMeshForm(resolver, redo)) {
            return NativeHistoryDecodeResult.unsupported("history.detail.value-unsupported");
        }
        return ListUndoDecoder.decodeArtMeshForms(
            resolver,
            context.boundedLabel(label),
            List.of(undo),
            List.of(redo)
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
