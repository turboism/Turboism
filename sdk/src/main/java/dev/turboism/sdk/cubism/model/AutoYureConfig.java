package dev.turboism.sdk.cubism.model;


/**
 * Evaluated auto-Yure configuration of one parameter binding on one Warp Deformer.
 *
 * <p>Mirrors the Editor's {@code AutoYureConfig} projection: left/right
 * deformation configs, sync flag, root direction, and flip flag.</p>
 */
public interface AutoYureConfig {

    YureDeformConfig left();

    YureDeformConfig right();

    boolean syncLeftRight();

    YureRootDirection rootDirection();

    boolean isFlip();
}
