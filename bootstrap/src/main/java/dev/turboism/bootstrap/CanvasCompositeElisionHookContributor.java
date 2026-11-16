package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTarget;
import dev.turboism.adapter.cubism.optimization.composite.CanvasCompositeElisionTransformer;

/**
 * Contributor for the test-only canvas-composite elision experiment. The sole
 * switch is {@code -Dturboism.validation.canvasCompositeElision=true}; the
 * hook policy is not consulted because this is a per-leg diagnostic, not a
 * product optimization. The {@code elision=ACTIVE} log marker, not
 * {@code installation=COMPLETE} alone, is the driver's evidence that the
 * reviewed {@code PaintManager.paint} and {@code FlatPanelUI.update} entries
 * were actually rewritten; the close marker reports the per-site counters for
 * reconciliation with the workload-side {@code leg.N.canvasComposite.*}
 * report keys.
 */
final class CanvasCompositeElisionHookContributor extends NativeOptimizationHookContributor {

    CanvasCompositeElisionHookContributor() {
        super("TURBOISM_CANVAS_COMPOSITE");
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(CanvasCompositeElisionTransformer.ENABLE_PROPERTY)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        final VerifiedCanvasCompositeElisionInstaller installer = new VerifiedCanvasCompositeElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " elision=ACTIVE sites=" + installer.sites()
                        + " targets=" + CanvasCompositeElisionTarget.PAINT_OWNER + '.'
                        + CanvasCompositeElisionTarget.PAINT_METHOD
                        + CanvasCompositeElisionTarget.PAINT_DESCRIPTOR
                        + '+' + CanvasCompositeElisionTarget.FILL_OWNER + '.'
                        + CanvasCompositeElisionTarget.FILL_METHOD
                        + CanvasCompositeElisionTarget.FILL_DESCRIPTOR);
        return installer;
    }
}
