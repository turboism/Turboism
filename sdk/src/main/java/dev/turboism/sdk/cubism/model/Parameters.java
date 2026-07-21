package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.List;
import java.util.NoSuchElementException;

/** Parameters in one Cubism model. */
@PreviewApi
public interface Parameters {

    /** Returns all parameters in stable model order. */
    List<Parameter> all();

    /**
     * Finds one parameter by ID.
     *
     * @throws NoSuchElementException when the ID is absent
     */
    Parameter find(ParameterId id);
}
