package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import java.util.List;
import java.util.Objects;

/** Detached geometry and opacity blend shapes on a Warp with one ordinary base form and no ordinary axes. */
@CubismEditor(from = "5.0.00")
public record WarpMorphSnapshot(DeformerId deformerId, String sourceGuid, Form base, List<Key> keys) {
    public WarpMorphSnapshot {
        Objects.requireNonNull(deformerId, "deformerId");
        identity(sourceGuid);
        Objects.requireNonNull(base, "base");
        keys = List.copyOf(keys);
    }
    /** One stored absolute Warp form. */
    public record Form(String formGuid, List<Point2> controlPoints, float opacity) {
        public Form {
            identity(formGuid);
            controlPoints = List.copyOf(controlPoints);
            validateOpacity(opacity);
        }
    }
    /** One non-basic key, addressed by native parameter GUID. */
    public record Key(String parameterGuid, float keyValue, Form form) {
        public Key {
            identity(parameterGuid);
            if (!Float.isFinite(keyValue) || keyValue == 0f)
                throw new IllegalArgumentException("non-basic morph key required");
            Objects.requireNonNull(form, "form");
        }
    }

    private static void identity(String value) {
        if (Objects.requireNonNull(value, "identity").isBlank()) throw new IllegalArgumentException("blank identity");
    }

    private static void validateOpacity(float value) {
        if (!Float.isFinite(value) || value < 0f || value > 1f)
            throw new IllegalArgumentException("opacity outside 0..1");
    }
}
