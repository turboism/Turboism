package dev.turboism.sdk.cubism.model;


/**
 * Evaluated auto-Yure configuration of one parameter binding on one Warp Deformer.
 *
 * <p>Mirrors the Editor's {@code AutoYureConfig} projection: left/right
 * deformation configs, sync flag, root direction, and flip flag.</p>
 */
public interface AutoYureConfig {

    /** Returns the left-side deformation configuration. */
    YureDeformConfig left();

    /** Returns the right-side deformation configuration. */
    YureDeformConfig right();

    /** Whether the left and right deformations are kept in sync. */
    boolean syncLeftRight();

    /** Returns the direction of the auto-Yure root. */
    YureRootDirection rootDirection();

    /** Whether the deformation output is flipped. */
    boolean isFlip();
}
