package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterGroupId;

import java.util.List;
import java.util.NoSuchElementException;

/** Parameter folders in one Editor model. */
@PreviewApi
public interface ParameterGroups {

    /** Returns all groups in stable pre-order, starting with the root group. */
    List<ParameterGroup> all();

    ParameterGroup root();

    /** @throws NoSuchElementException when the ID is absent */
    ParameterGroup find(ParameterGroupId id);
}
