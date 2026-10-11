package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** One Rotation Deformer exposed through Editor authoring semantics. */
@CubismEditor
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
    /** Returns independently stored ordinary forms and exact grid coordinates on verified hosts. */
    default RotationAuthoringSnapshot authoringSnapshot() {
        throw new UnsupportedOperationException("Stored Rotation authoring is unavailable.");
    }

    /** Compare-and-set existing non-default ordinary forms, preserving grid and source identities. */
    default void setOrdinaryKeyformForms(
            RotationAuthoringSnapshot expected, java.util.List<RotationKeyformForm> edits) {
        throw new UnsupportedOperationException("Stored Rotation form editing is unavailable.");
    }
}
