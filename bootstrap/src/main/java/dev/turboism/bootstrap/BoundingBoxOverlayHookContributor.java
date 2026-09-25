package dev.turboism.bootstrap;

import dev.turboism.ui.overlay.BoundingBoxOverlayButtonHookInstaller;

/** Declarative contributor for the bounding-box overlay button hook. */
final class BoundingBoxOverlayHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_BOUNDING_BOX_OVERLAY_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.fullRuntimeAdmission();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        return new BoundingBoxOverlayButtonHookInstaller(environment.instrumentation())
            .install(runtime.hostAccess().boundingBoxOverlayResolver().orElseThrow());
    }
}
