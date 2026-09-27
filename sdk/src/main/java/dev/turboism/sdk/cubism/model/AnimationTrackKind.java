package dev.turboism.sdk.cubism.model;

/**
 * Classification of one animation timeline track.
 *
 * <p>{@link #OTHER} covers host track families this SDK does not yet classify;
 * those tracks still expose identity, flags, timing, and attributes.</p>
 */
public enum AnimationTrackKind {
    GROUP,
    LIVE2D_MODEL,
    SCENE,
    IMAGE,
    GUIDE_IMAGE,
    TEXT,
    MOC3,
    SOUND,
    OTHER
}
