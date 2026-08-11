package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/**
 * Evaluated deformation configuration of one side of an auto-Yure binding.
 *
 * <p>Values mirror the Editor's {@code YureDeformConfig} projection:
 * scale percent X/Y, expand scale, and decay level.</p>
 */
@PreviewApi
public interface YureDeformConfig {

    float scalePercentX();

    float scalePercentY();

    float expandScale();

    double decayLevel();
}
