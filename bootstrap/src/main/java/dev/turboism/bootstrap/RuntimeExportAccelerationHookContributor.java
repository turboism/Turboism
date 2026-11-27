package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.export.RuntimeExportAccelerationTransformer;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.lang.instrument.ClassFileTransformer;

/** Installs runtime export acceleration before Cubism loads exporter and archive classes. */
final class RuntimeExportAccelerationHookContributor extends AbstractAccelerationHookContributor {

    static final String HOOK_POLICY_ID = "cubism.model.runtime-export-acceleration";
    private static final String ID = "TURBOISM_RUNTIME_EXPORT_ACCELERATION";

    RuntimeExportAccelerationHookContributor() {
        super(ID, HOOK_POLICY_ID, OptimizationCatalog.RUNTIME_EXPORT_ACCELERATION);
    }

    @Override
    protected ClassFileTransformer createTransformer() {
        return new RuntimeExportAccelerationTransformer();
    }
}
