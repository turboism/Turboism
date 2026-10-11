package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.CubismEditor;

/** Result of checking MOC byte consistency. */
@CubismEditor
public enum MocConsistency {
    UNKNOWN,
    CONSISTENT,
    INCONSISTENT
}
