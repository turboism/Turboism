package dev.turboism.plugin.frameanimation.domain;

/**
 * Display transition strategy between consecutive frames.
 */
public enum DisplayStrategy {
    /**
     * Discrete flipbook step without transparency blend.
     * The active frame remains fully opaque across its slot.
     */
    STEP_FLIPBOOK,

    /**
     * Smooth crossfade blend between consecutive frames.
     */
    CROSSFADE
}
