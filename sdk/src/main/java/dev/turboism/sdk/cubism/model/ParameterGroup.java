package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** One folder in the Editor parameter hierarchy. */
@PreviewApi
public interface ParameterGroup {

    ParameterGroupId id();

    Optional<String> name();

    /** Returns the effective Editor label color. */
    default Color labelColor() {
        throw new UnsupportedOperationException(
            "Parameter-group label color access is unavailable for this backend."
        );
    }

    Optional<ParameterGroupId> parentId();

    List<ParameterGroupId> childGroupIds();

    List<ParameterId> parameterIds();
}
