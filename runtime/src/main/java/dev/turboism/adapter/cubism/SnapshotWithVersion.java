package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;

/**
 * A runtime snapshot paired with the host revision it was taken at.
 *
 * <p>The version lets a consumer tell a re-read that observed no change from one that observed a
 * genuinely new host state: equal versions mean the snapshot was not recomputed. It is a host
 * revision counter, not a timestamp, and is only comparable against versions from the same source.
 *
 * @param snapshot the observed runtime state
 * @param version  the host revision the snapshot was read at; monotonically increasing
 */
public record SnapshotWithVersion(CubismRuntimeSnapshot snapshot, long version) {
}
