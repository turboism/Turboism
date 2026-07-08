package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.List;
import java.util.Optional;

public interface ParameterQueryService {

    Optional<ParameterSummary> findById(ParameterId id) throws CubismServiceException;

    List<ParameterSummary> listAll() throws CubismServiceException;

    boolean exists(ParameterId id) throws CubismServiceException;
}
