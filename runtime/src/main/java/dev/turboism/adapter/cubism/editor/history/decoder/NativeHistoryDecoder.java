package dev.turboism.adapter.cubism.editor.history.decoder;

interface NativeHistoryDecoder {
    NativeHistoryDecodeResult decode(
        Object entry,
        String label,
        NativeHistoryDecodeContext context,
        int depth,
        NativeHistoryDecoderRegistry registry
    );
}
