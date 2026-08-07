package dev.turboism.ui.overlay;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Static ingress called only by the exact verified bounding-box update transformer. */
public final class NativeBoundingBoxOverlayButtonBridge {

    private static final AtomicReference<VerifiedBoundingBoxOverlayButtonHostOperations> HOST =
        new AtomicReference<>();

    private NativeBoundingBoxOverlayButtonBridge() {
    }

    static Registration install(final VerifiedBoundingBoxOverlayButtonHostOperations host) {
        final VerifiedBoundingBoxOverlayButtonHostOperations requested = Objects.requireNonNull(
            host,
            "host"
        );
        if (!HOST.compareAndSet(null, requested)) {
            throw new IllegalStateException("bounding-box overlay bridge is already installed");
        }
        return () -> HOST.compareAndSet(requested, null);
    }

    /** Called from transformed host bytecode after the native update completes normally. */
    public static void afterUpdate(final Object boundingBox, final Object actionPack, final Object sceneGraph) {
        final VerifiedBoundingBoxOverlayButtonHostOperations host = HOST.get();
        if (host != null) {
            host.afterUpdate(boundingBox, actionPack, sceneGraph);
        }
    }

    static void setHostForTest(final VerifiedBoundingBoxOverlayButtonHostOperations host) {
        HOST.set(host);
    }
}
