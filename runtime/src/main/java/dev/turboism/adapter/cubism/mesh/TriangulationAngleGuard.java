package dev.turboism.adapter.cubism.mesh;

/** Scalar-only conservative rejection for the leased native h.d branch. */
public final class TriangulationAngleGuard {
    private TriangulationAngleGuard() { }

    /** Inputs retain the native float products/order; false always runs the native angle call. */
    public static boolean reject(final float cross, final float dot) {
        if (!Float.isFinite(cross) || !Float.isFinite(dot)) return false;
        if (Math.abs(cross) < Float.MIN_NORMAL || Math.abs(dot) < Float.MIN_NORMAL) return false;
        if (dot < 0.0f) return true;
        return Math.abs((double) cross) > (double) dot * 2.0e-6;
    }
}
