package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;

/** Narrow SDK owner of the Cubism Part palette UI projection. */
@CubismEditor
public interface PartAppearanceAccess {

    /** Returns this Part's Cubism palette UI projection. */
    @CubismEditor(from = "5.0.00")
    default PartAppearance ui() {
        return PartAppearance.unavailable();
    }
}
