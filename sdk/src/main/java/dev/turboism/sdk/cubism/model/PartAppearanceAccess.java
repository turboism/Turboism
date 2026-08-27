package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;

/** Narrow SDK owner of the Cubism Part palette UI projection. */
public interface PartAppearanceAccess {

    /** Returns this Part's Cubism palette UI projection. */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default PartAppearance ui() { return PartAppearance.unavailable(); }
}
