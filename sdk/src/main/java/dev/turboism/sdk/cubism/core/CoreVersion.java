package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

/** Normalized semantic Cubism Core version. */
@PreviewApi
public record CoreVersion(int major, int minor, int patch) {

    public CoreVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Core version numbers must be non-negative.");
        }
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
