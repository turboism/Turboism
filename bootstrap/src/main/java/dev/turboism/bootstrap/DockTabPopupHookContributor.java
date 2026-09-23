package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory;

/** Declarative contributor for the verified dock-tab popup hook. */
final class DockTabPopupHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_DOCK_TAB_POPUP_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        if (!environment.fullRuntimeAdmission()) {
            return false;
        }
        if (environment.safeMode()) {
            environment.runtime().ifPresent(runtime ->
                runtime.info("bootstrap", "Turboism dock-tab popup hook skipped in safe mode"));
            return false;
        }
        return true;
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var host = environment.host().orElseThrow();
        final var resolver = new VerifiedEmbeddedPanelResolverFactory().create(
            environment.verificationRecord(
                "cubism-" + environment.profile() + "-ui-embedded-panel.json"
            ),
            host.artifact(),
            host.classLoader()
        );
        final VerifiedDockTabPopupHookInstaller installer =
            new VerifiedDockTabPopupHookInstaller(
                environment.instrumentation(),
                resolver.verifiedSelector("cubism.ui-panel.dock-tab-popup.operation"),
                resolver.verifiedSelector("cubism.ui-panel.dock-tab-popup.palette-field"),
                resolver.verifiedSelector("cubism.ui-panel.dock-tab-popup.menu-append"),
                host.classLoader()
            );
        installer.install();
        return installer;
    }
}
