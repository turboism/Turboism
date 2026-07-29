package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** One Rotation Deformer exposed through Editor authoring semantics. */
@PreviewApi
public interface RotationDeformer extends Deformer {

    float baseAngle();

    void setBaseAngle(float angle);

    RotationDeformerForm form();

    void replaceForm(RotationDeformerForm form);
}
