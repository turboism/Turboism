package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;

/** Read-only scalar parameter definitions in stable model order. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
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
