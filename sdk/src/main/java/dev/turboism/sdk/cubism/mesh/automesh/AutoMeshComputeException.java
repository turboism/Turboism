package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;

/**
 * Raised by an {@link AutoMeshCompute} that cannot produce a result for the request it received.
 *
 * <p>It signals a fault — a planner that hit an internal error, not a refused request. A request
 * the algorithm declines on its own terms belongs in {@link AutoMeshResult.Rejected}, because
 * that outcome reaches the user as a message while a fault is reported as a failure.</p>
 */
@CubismEditor
public final class AutoMeshComputeException extends Exception {
    public AutoMeshComputeException(final String message) {
        super(message);
    }

    public AutoMeshComputeException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
