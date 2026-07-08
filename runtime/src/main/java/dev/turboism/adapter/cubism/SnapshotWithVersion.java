package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;

public record SnapshotWithVersion(CubismRuntimeSnapshot snapshot, long version) {
}
