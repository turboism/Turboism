package dev.turboism.ui.overlay;

import dev.turboism.sdk.plugin.Registration;

import java.util.List;

/** Host-sensitive materialization seam invoked from the verified bounding-box update hook. */
public interface BoundingBoxOverlayButtonHostOperations {

    Registration install(List<BoundingBoxOverlayButtonDescriptor> descriptors);
}
