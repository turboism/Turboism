package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** One Rotation Deformer exposed through Editor authoring semantics. */
@CubismEditor(from = "5.0.00")
public interface RotationDeformer extends Deformer {

    /** Returns the deformer pivot's base angle, in degrees. */
    float baseAngle();

    /**
     * Writes the deformer pivot's base angle, in degrees, through the Editor authoring path.
     *
     * @param angle the new base angle in degrees
     */
    void setBaseAngle(float angle);

    /** Returns the deformer's current pivot form (position and radii). */
    RotationDeformerForm form();

    /** Replaces the deformer's pivot form through the Editor authoring path. */
    void replaceForm(RotationDeformerForm form);
}
