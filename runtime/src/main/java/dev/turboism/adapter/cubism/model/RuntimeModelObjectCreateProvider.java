package dev.turboism.adapter.cubism.model;

import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectReference;

/** Runtime-only seam for exact, stable-ID model-object creation. */
public interface RuntimeModelObjectCreateProvider {

    /** Fails before any active-model or parent read when this request is unsupported. */
    void requireCreateSupported(ModelObjectCreateRequest request);

    /** Commits the request against the supplied generation-bound active model. */
    ModelObjectReference createModelObject(
        CubismModel model,
        ModelObjectCreateRequest request
    );
}
