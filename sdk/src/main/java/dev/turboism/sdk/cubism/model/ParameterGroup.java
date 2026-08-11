package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** One folder in the Editor parameter hierarchy. */
@PreviewApi
public interface ParameterGroup {

    ParameterGroupId id();

    /** Returns this ParameterGroup's Cubism parameter-palette UI projection. */
    default ParameterGroupAppearance ui() { return ParameterGroupAppearance.unavailable(); }

    Optional<String> name();

    Optional<ParameterGroupId> parentId();

    List<ParameterGroupId> childGroupIds();

    List<ParameterId> parameterIds();

    /**
     * Renames this folder through the Editor undo path.
     *
     * @throws IllegalArgumentException when the name is blank
     */
    default void rename(final String name) {
        throw unavailable("ParameterGroup renaming");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
