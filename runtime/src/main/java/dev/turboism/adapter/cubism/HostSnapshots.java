package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.ProjectSnapshot;
import java.util.Objects;

/** Runtime-internal projection of host-typed snapshot fragments into SDK snapshots. */
public final class HostSnapshots {

    private static final ImmutableSnapshotFactory FACTORY = new ImmutableSnapshotFactory();

    private HostSnapshots() {}

    /** Projects one raw host project into its immutable SDK snapshot. */
    public static ProjectSnapshot project(final HostSnapshotSource.HostProject project) {
        return FACTORY.project(Objects.requireNonNull(project, "project"));
    }
}
