package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the parameters of the model currently open in the Editor.
 *
 * <p>Results are snapshots taken at call time and do not track later parameter changes.
 * Implementations bridge to the Cubism host, so calls may need to be made from the host thread and
 * fail with {@link CubismServiceException} when the host is unavailable.
 */
public interface ParameterQueryService {

    /**
     * @param id the parameter to look up
     * @return a snapshot of that parameter, or empty when the current model has no such parameter
     * @throws CubismServiceException if the host could not be queried
     */
    Optional<ParameterSummary> findById(ParameterId id) throws CubismServiceException;

    /**
     * @return a snapshot of every parameter of the current model; empty when no model is open
     * @throws CubismServiceException if the host could not be queried
     */
    List<ParameterSummary> listAll() throws CubismServiceException;

    /**
     * @param id the parameter to test for
     * @return {@code true} if the current model declares this parameter
     * @throws CubismServiceException if the host could not be queried
     */
    boolean exists(ParameterId id) throws CubismServiceException;
}
