package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/**
 * Evaluated deformation configuration of one side of an auto-Yure binding.
 *
 * <p>Values mirror the Editor's {@code YureDeformConfig} projection:
 * scale percent X/Y, expand scale, and decay level.</p>
 */
@CubismEditor(from = "5.1.00")
public interface YureDeformConfig {

    /** Returns the horizontal scale percent applied to this side. */
    float scalePercentX();

    /** Returns the vertical scale percent applied to this side. */
    float scalePercentY();

    /** Returns the expand scale applied to this side. */
    float expandScale();

    /** Returns the decay level applied to this side. */
    double decayLevel();
}
