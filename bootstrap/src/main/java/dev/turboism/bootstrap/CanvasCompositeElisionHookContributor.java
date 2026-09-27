package dev.turboism.bootstrap;

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

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(CanvasCompositeElisionTransformer.ENABLE_PROPERTY)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        final VerifiedCanvasCompositeElisionInstaller installer =
            new VerifiedCanvasCompositeElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(environment, id() + " elision=ACTIVE sites=" + installer.sites()
            + " targets=javax/swing/RepaintManager$PaintManager.paint(IIII)Z"
            + "+com/formdev/flatlaf/ui/FlatPanelUI.update(Ljava/awt/Graphics;"
            + "Ljavax/swing/JComponent;)V");
        return installer;
    }
}
