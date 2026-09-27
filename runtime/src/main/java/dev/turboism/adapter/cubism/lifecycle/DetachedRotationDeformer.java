package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;

/** Immutable, host-detached Rotation Deformer projection for event delivery. */
final class DetachedRotationDeformer extends DetachedDeformer implements RotationDeformer {
    private final DetachedValue<Float> baseAngle;
    private final DetachedValue<RotationDeformerForm> form;

    private DetachedRotationDeformer(
        final RotationDeformer source,
        final float opacity,
        final DetachedValue<Float> baseAngle,
        final DetachedValue<RotationDeformerForm> form
    ) {
        super(source, opacity);
        this.baseAngle = baseAngle;
        this.form = java.util.Objects.requireNonNull(form, "form");
    }

    static DetachedRotationDeformer capture(final RotationDeformer deformer, final float opacity) {
        return new DetachedRotationDeformer(
            deformer, opacity,
            DetachedValue.capture("baseAngle", deformer::baseAngle),
            DetachedValue.capture("form", deformer::form)
        );
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
            DetachedValue.capture("baseAngle", () -> baseAngle),
            DetachedValue.capture("form", () -> java.util.Objects.requireNonNull(form, "form"))
        );
    }

    @Override public float baseAngle() { return baseAngle.get(); }
    @Override public RotationDeformerForm form() { return form.get(); }
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
