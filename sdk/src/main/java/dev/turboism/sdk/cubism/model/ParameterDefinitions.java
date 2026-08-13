package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;

/** Read-only scalar parameter definitions in stable model order. */
@PreviewApi
public interface ParameterDefinitions {

    List<ParameterDefinition> all();

    ParameterDefinition find(ParameterId id);
}
