package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/** Atomic model-level parameter-binding inversion and GUID-transfer operations. */
@PreviewApi
public interface ParameterBindingBatchOperations {

    void invert(List<ParameterBindingTarget> targets);

    void transfer(ParameterBindingTransferPlan plan);
}
