package dev.turboism.validation.modelupdate;

import java.util.List;

/** Exact-bit camera restoration regression; no native Editor or GPU is required. */
public final class ExactCameraRestoreTest {
    public static void main(String[] args) throws Exception {
        java.awt.EventQueue.invokeAndWait(() -> {
            try {
                verify();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }
    private static void verify() throws Exception {
        Transform transform = new Transform();
        Vector look = new Vector();
        float epsilon = Float.intBitsToFloat(0x34800000);
        transform.position.set(epsilon, 0, 1000);
        look.set(epsilon, -0.0000076293945f, 0);
        var snapshot = new NativeInteractionHost.CameraTransform(List.of(
            new float[] {0, 0, 1000}, new float[] {-0.0f, 0, 0}, new float[] {1, 1, 1}));
        var camera = new NativeInteractionHost.Camera(0.9981901f, 0, 0, 0);
        for (int i = 0; i < 4; i++) {
            NativeInteractionHost.restoreCameraTransform(transform, snapshot, look, camera);
            check(Float.floatToRawIntBits(transform.position.x) == 0, "position drift must be removed exactly");
            check(Float.floatToRawIntBits(transform.angles.x) == 0x80000000, "negative zero must be preserved");
            check(Float.floatToRawIntBits(look.x) == 0 && Float.floatToRawIntBits(look.y) == 0,
                "wrapper drift from native camera recomputation must be removed");
            check(transform.position.z == 1000 && transform.scale.x == 1, "other transform components preserved");
        }
        System.out.println("ExactCameraRestoreTest PASS (native rounding drift, signed zero, repeated restoration)");
    }
    public static final class Vector {
        float x, y, z;
        public void set(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }
    public static final class Transform {
        final Vector position = new Vector(), angles = new Vector(), scale = new Vector();
        public Vector getPosition() { return position; }
        public Vector getEulerAngles() { return angles; }
        public Vector getScale() { return scale; }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
