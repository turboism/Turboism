package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.CancellationToken;

/**
 * Computes one ArtMesh placement for a registered algorithm.
 *
 * <p>The runtime calls this off the Editor UI thread and applies the returned geometry inside one
 * host transaction, so an implementation may take as long as its own budget allows but must stop
 * when the cancellation token reports a request. The request carries everything the algorithm may
 * read; reaching the Editor through any other SDK service from this call is a contract violation
 * because the target model may change before the result is applied.</p>
 *
 * <p>The runtime never applies a result that fails validation against the request — vertex count,
 * uv pairing and triangle indices must all match the snapshot the request carried.</p>
 */
@FunctionalInterface
@CubismEditor
public interface AutoMeshCompute {

    /**
     * Computes a placement for {@code request}.
     *
     * @param request the target snapshot, texture input and resolved parameter values
     * @param cancellation cooperative cancellation signal for this run
     * @return the computed geometry, a refusal, or a cancellation notice
     * @throws AutoMeshComputeException when the algorithm fails; the runtime reports it as a fault
     */
    AutoMeshResult compute(AutoMeshRequest request, CancellationToken cancellation) throws AutoMeshComputeException;
}
