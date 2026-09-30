package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;

/** Read-only scalar parameter definitions in stable model order. */
@CubismEditor({"5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"})
public interface ParameterDefinitions {

    /** Returns every parameter definition in stable model order. */
    List<ParameterDefinition> all();

    /**
     * Returns the definition for {@code id}.
     *
     * @throws java.util.NoSuchElementException when the ID is absent
     */
    ParameterDefinition find(ParameterId id);
}
