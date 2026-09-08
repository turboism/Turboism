package dev.turboism.adapter.cubism.editor.history.decoder;


/** Rejects generic {@code PropertyUndo} entries until an exact domain descriptor matches. */
final class PropertyUndoDecoder implements NativeHistoryDecoder {

    @Override
    public NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth,
        final NativeHistoryDecoderRegistry registry
    ) {
        return NativeHistoryDecodeResult.unsupported("history.semantic-operation-unmapped");
    }
}
