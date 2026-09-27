package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;

/** One evaluated auto-Yure binding: a Warp Deformer tracked for one Parameter. */
public interface AutoYureBinding {

    /** Returns the tracked Warp Deformer's identity. */
    DeformerId deformerId();

    /** Returns the Parameter this binding follows. */
    ParameterId parameterId();

    /** Returns the evaluated auto-Yure configuration of this binding. */
    AutoYureConfig config();
}
