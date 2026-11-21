package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;
import java.lang.instrument.ClassFileTransformer;

/** Installs runtime export acceleration before Cubism loads exporter and archive classes. */
final class RuntimeExportAccelerationHookContributor extends AbstractAccelerationHookContributor {

    static final String HOOK_POLICY_ID = "cubism.model.runtime-export-acceleration";
    private static final String ID = "TURBOISM_RUNTIME_EXPORT_ACCELERATION";
    private static final String TRANSFORMER_CLASS =
            "dev.turboism.adapter.cubism.exportperf.RuntimeExportAccelerationTransformer";

    RuntimeExportAccelerationHookContributor() {
        super(ID, HOOK_POLICY_ID, OptimizationCatalog.RUNTIME_EXPORT_ACCELERATION);
    }

    @Override
    protected ClassFileTransformer createTransformer() {
        return AccelerationTransformerFactory.instantiateOrNoOp(TRANSFORMER_CLASS);
    }
}
