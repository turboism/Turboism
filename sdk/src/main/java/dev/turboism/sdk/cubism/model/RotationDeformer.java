package dev.turboism.sdk.cubism.model;


/** One Rotation Deformer exposed through Editor authoring semantics. */
public interface RotationDeformer extends Deformer {

    float baseAngle();

    void setBaseAngle(float angle);

    RotationDeformerForm form();

    void replaceForm(RotationDeformerForm form);
    /** Returns independently stored ordinary forms and exact grid coordinates on verified hosts. */
    default RotationAuthoringSnapshot authoringSnapshot() {
        throw new UnsupportedOperationException("Stored Rotation authoring is unavailable.");
    }

    /** Compare-and-set existing non-default ordinary forms, preserving grid and source identities. */
    default void setOrdinaryKeyformForms(RotationAuthoringSnapshot expected, java.util.List<RotationKeyformForm> edits) {
        throw new UnsupportedOperationException("Stored Rotation form editing is unavailable.");
    }

}
