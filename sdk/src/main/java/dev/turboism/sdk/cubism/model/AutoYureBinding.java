package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;

/** One evaluated auto-Yure binding: a Warp Deformer tracked for one Parameter. */
public interface AutoYureBinding {

    DeformerId deformerId();

    ParameterId parameterId();

    AutoYureConfig config();
}
