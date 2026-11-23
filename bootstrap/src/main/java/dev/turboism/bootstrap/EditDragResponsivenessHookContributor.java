package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.drag.EditDragResponsivenessBridge;

/**
 * Contributor for canvas drag coalescing, drag-scoped inspector/deformer-tree update deferral, and
 * same-cursor elision under the warp-deform edit acceleration preference.
 */
final class EditDragResponsivenessHookContributor extends NativeOptimizationHookContributor {

    EditDragResponsivenessHookContributor() {
        super("TURBOISM_EDIT_DRAG_RESPONSIVENESS", VerifiedEditDragResponsivenessInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean production = NativeOptimizationPolicy.warpDeformEditAccelerationEnabled(environment);
        final String override = System.getProperty(EditDragResponsivenessBridge.VALIDATION_PROPERTY);
        final boolean enabled = override == null ? production : Boolean.parseBoolean(override);
        if (!enabled) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedEditDragResponsivenessInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedEditDragResponsivenessInstaller installer =
                new VerifiedEditDragResponsivenessInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " dragResponsiveness=ACTIVE");
        return installer;
    }
}
