package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Optional;

/** One folder in the Editor parameter hierarchy. */
public interface ParameterGroup {

    /** Returns this folder's stable identity. */
    ParameterGroupId id();

    /** Returns this ParameterGroup's Cubism parameter-palette UI projection. */
    default ParameterGroupAppearance ui() { return ParameterGroupAppearance.unavailable(); }

    /** Returns the folder's display name, or empty when it has none. */
    Optional<String> name();

    /** Returns the parent folder identity, or empty for the root folder. */
    Optional<ParameterGroupId> parentId();

    /** Returns the identities of this folder's direct child folders, in order. */
    List<ParameterGroupId> childGroupIds();

    /** Returns the identities of the parameters filed directly in this folder, in order. */
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
