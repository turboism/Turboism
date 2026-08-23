package dev.turboism.sdk.cubism.model;


/** One Rotation Deformer exposed through Editor authoring semantics. */
public interface RotationDeformer extends Deformer {

    float baseAngle();

    void setBaseAngle(float angle);

    RotationDeformerForm form();

    void replaceForm(RotationDeformerForm form);
}
