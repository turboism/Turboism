package dev.turboism.ui.overlay;

import dev.turboism.sdk.plugin.Registration;

import java.util.List;

/** Host-sensitive materialization seam invoked from the verified bounding-box update hook. */
public interface BoundingBoxOverlayButtonHostOperations {

    /**
     * Installs the bounding-box overlay buttons described by {@code descriptors}.
     *
     * @param descriptors the buttons to materialize on the host overlay
     * @return a registration that removes the installed buttons when disposed
     */
    Registration install(List<BoundingBoxOverlayButtonDescriptor> descriptors);

    /**
     * Retains the current native registration and swaps the contribution snapshot so that
     * unchanged button identities survive contribution changes.
     */
    default Registration reconcile(
        final List<BoundingBoxOverlayButtonDescriptor> descriptors,
        final Registration existing
    ) {
        throw new UnsupportedOperationException(
            "bounding-box overlay host operations do not support reconcile"
        );
    }
}
