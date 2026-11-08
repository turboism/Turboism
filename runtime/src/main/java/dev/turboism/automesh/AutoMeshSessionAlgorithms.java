package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.Optional;

/**
 * Session selection surface shared by the native selector, the selection state and the dispatcher.
 *
 * <p>{@link AutoMeshAlgorithmCatalog} is the only production implementation, because it already
 * owns the Editor's sentinel entry and the selection that survives plugin reloads. The interface
 * exists for observability: the native confirmation path must prove that it resolves no algorithm
 * at all, and that proof needs a substitutable view the final catalog cannot offer.</p>
 */
public interface AutoMeshSessionAlgorithms {

    /**
     * Reports the algorithm the native entry would run now.
     *
     * @return the selected algorithm id, or empty when the Editor's own generator runs
     */
    Optional<String> selectedAlgorithmId();

    /**
     * Resolves one algorithm by id, including the Editor's own generator.
     *
     * @param algorithmId the algorithm id to resolve
     * @return the live algorithm, or empty when the id is unregistered or its generation ended
     */
    Optional<AutoMeshAlgorithm> find(String algorithmId);

    /**
     * Requests the algorithm the native entry runs.
     *
     * @param algorithmId the algorithm to select; a native value clears the selection
     */
    void select(String algorithmId);
}
