package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;

/** Immutable, host-detached Rotation Deformer projection for event delivery. */
final class DetachedRotationDeformer extends DetachedDeformer implements RotationDeformer {
    private final float baseAngle;
    private final RotationDeformerForm form;

    private DetachedRotationDeformer(
        final RotationDeformer source,
        final float opacity,
        final float baseAngle,
        final RotationDeformerForm form
    ) {
        super(source, opacity);
        this.baseAngle = baseAngle;
        this.form = java.util.Objects.requireNonNull(form, "form");
    }

    static DetachedRotationDeformer capture(
        final RotationDeformer deformer,
        final float opacity,
        final float baseAngle,
        final RotationDeformerForm form
    ) {
        return new DetachedRotationDeformer(
            java.util.Objects.requireNonNull(deformer, "deformer"),
            opacity,
            baseAngle,
            form
        );
    }

    @Override public float baseAngle() { return baseAngle; }
    @Override public RotationDeformerForm form() { return form; }
    @Override public void setBaseAngle(final float angle) { throw detachedRotation(); }
    @Override public void replaceForm(final RotationDeformerForm form) {
        throw detachedRotation();
    }

    private static UnsupportedOperationException detachedRotation() {
        return new UnsupportedOperationException(
            "Event Rotation Deformer snapshots are read-only and host-detached."
        );
    }
}
