package dev.turboism.adapter.cubism;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;

/** Runtime-private seam; exact host wiring is admitted separately from the model projection. */
public interface NativeLabelColorAuthoring {

    NativeLabelColorState readNativeLabelColor(NativeLabelColorTarget target);

    void setNativeLabelColor(NativeLabelColorTarget target, NativeLabelColor color);

    static NativeLabelColorAuthoring unavailable() {
        return new NativeLabelColorAuthoring() {
            @Override
            public NativeLabelColorState readNativeLabelColor(final NativeLabelColorTarget target) {
                throw unsupported();
            }

            @Override
            public void setNativeLabelColor(
                final NativeLabelColorTarget target,
                final NativeLabelColor color
            ) {
                throw unsupported();
            }
        };
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Native label-color authoring is unavailable");
    }
}
