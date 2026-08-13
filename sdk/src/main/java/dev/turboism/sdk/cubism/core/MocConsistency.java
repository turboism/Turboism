package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

/** Result of checking MOC byte consistency. */
@PreviewApi
public enum MocConsistency {
    UNKNOWN,
    CONSISTENT,
    INCONSISTENT
}
