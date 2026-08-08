package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Immutable normalized MOC inspection result. */
@PreviewApi
public record MocInfo(MocVersion version, MocConsistency consistency) {

    public MocInfo {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(consistency, "consistency");
    }
}
