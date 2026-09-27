package dev.turboism.adapter.cubism;

import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;

/** Runtime-private seam; exact host wiring is admitted separately from the model projection. */
public interface NativeLabelColorAuthoring {

    /**
     * Reads the current label color of one palette object.
     *
     * @param target the palette object to inspect
     * @return the observed label-color state
     */
    NativeLabelColorState readNativeLabelColor(NativeLabelColorTarget target);

    /**
     * Writes the label color of one palette object through the verified host seam.
     *
     * @param target the palette object to recolor
     * @param color the label color to apply
     */
    void setNativeLabelColor(NativeLabelColorTarget target, NativeLabelColor color);

    /**
     * An authoring seam for when the verified host wiring is absent.
     *
     * @return an instance whose operations throw {@link UnsupportedOperationException}
     */
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
