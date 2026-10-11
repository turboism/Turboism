package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/**
 * Value shape carried by one animated attribute.
 *
 * <p>{@link #OTHER} covers host attribute types this SDK does not yet classify;
 * their keyframes still enumerate frame positions but may carry no value.</p>
 */
@CubismEditor
public enum AnimationAttributeKind {
    FLOAT,
    INTEGER,
    POINT,
    OTHER
}
