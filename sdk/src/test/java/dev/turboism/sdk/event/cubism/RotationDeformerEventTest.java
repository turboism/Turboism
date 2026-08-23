package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RotationDeformerEventTest {
    @Test
    void baseAngleBeforeSealsMutationAfterCallback() {
        final RotationDeformerBaseAngleEvent.Before retained;
        try (RotationDeformerBaseAngleEvent.Before.Callback callback =
            RotationDeformerBaseAngleEvent.Before.openCallback(
                deformer(), 10.0F, 5.0F
            )) {
            retained = callback.event();
            retained.setAngle(15.0F);
            assertEquals(15.0F, retained.angle());
        }

        assertThrows(IllegalStateException.class, () -> retained.setAngle(20.0F));
    }

    @Test
    void formBeforeSealsMutationAfterCallback() {
        final RotationDeformerForm requested = form(1.0F);
        final RotationDeformerForm replacement = form(2.0F);
        final RotationDeformerFormEvent.Before retained;
        try (RotationDeformerFormEvent.Before.Callback callback =
            RotationDeformerFormEvent.Before.openCallback(
                deformer(), requested, requested
            )) {
            retained = callback.event();
            retained.setForm(replacement);
            assertEquals(replacement, retained.form());
        }

        assertThrows(IllegalStateException.class, () -> retained.setForm(form(3.0F)));
    }

    private static RotationDeformer deformer() {
        return new RotationDeformer() {
            @Override public DeformerId id() { return new DeformerId("RotationA"); }
            @Override public int parentDeformerIndex() { return -1; }
            @Override public IntSequence parameters() { return ints(); }
            @Override public float baseAngle() { return 0.0F; }
            @Override public void setBaseAngle(final float angle) { }
            @Override public RotationDeformerForm form() {
                return RotationDeformerEventTest.form(0.0F);
            }
            @Override public void replaceForm(final RotationDeformerForm form) { }
        };
    }

    private static RotationDeformerForm form(final float angle) {
        return new RotationDeformerForm(angle, 0, 0, 1, false, false);
    }

    private static IntSequence ints() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
