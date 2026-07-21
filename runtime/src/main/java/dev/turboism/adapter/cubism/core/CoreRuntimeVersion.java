package dev.turboism.adapter.cubism.core;

/** Immutable, adapter-owned value returned by Cubism Core's public version API. */
public record CoreRuntimeVersion(int major, int minor, int patch) {

    public CoreRuntimeVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Core runtime version components must not be negative");
        }
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
