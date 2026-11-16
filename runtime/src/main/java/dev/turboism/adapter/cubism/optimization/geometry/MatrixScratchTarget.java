package dev.turboism.adapter.cubism.optimization.geometry;

/**
 * Reviewed host class the invocation-local matrix scratch weave depends on.
 *
 * <p>The rewritten traversal replaces the reviewed product helper's per-call matrix allocation with
 * one invocation-local scratch instance, so the helper must be the reviewed one. The name lives here
 * instead of in the installer, which keeps the composition layer free of host-package literals.</p>
 */
public final class MatrixScratchTarget {

    /** Matrix product helper the rewritten traversal calls instead of allocating. */
    public static final String PRODUCT_OWNER = "com/live2d/graphics3d/type/a";

    /** Product helper method name. */
    public static final String PRODUCT_METHOD = "a";

    private MatrixScratchTarget() {}
}
