package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;

/** One Cubism parameter. */
@PreviewApi
public interface Parameter {

    ParameterId id();

    float getValue();

    float getMinimumValue();

    float getMaximumValue();

    float getDefaultValue();

    void setValue(float value);
}
