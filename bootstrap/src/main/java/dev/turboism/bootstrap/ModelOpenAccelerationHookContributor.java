package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;
import java.lang.instrument.ClassFileTransformer;

/** Installs model-open acceleration before Cubism loads document and image classes. */
final class ModelOpenAccelerationHookContributor extends AbstractAccelerationHookContributor {

    static final String HOOK_POLICY_ID = "cubism.model.open-acceleration";
    private static final String ID = "TURBOISM_MODEL_OPEN_ACCELERATION";
    private static final String TRANSFORMER_CLASS =
            "dev.turboism.adapter.cubism.modelopen.ModelOpenAccelerationTransformer";

    ModelOpenAccelerationHookContributor() {
        super(ID, HOOK_POLICY_ID, OptimizationCatalog.MODEL_OPEN_ACCELERATION);
    }

    @Override
    protected ClassFileTransformer createTransformer() {
        return AccelerationTransformerFactory.instantiateOrNoOp(TRANSFORMER_CLASS);
    }
}
