package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.PerformanceFpsHook;
import dev.turboism.adapter.cubism.performance.PerformanceFpsHookRegistry;

/**
 * Declarative contributor for the FPS counting hook. It publishes the agent-
 * owned handle into the JVM-wide registry before the preview runtime starts,
 * and clears that registration when the handle closes.
 */
final class FpsHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_FPS_HOOK";
    }

    @Override public Phase phase() {
        return Phase.HOST_RESOLVED;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.ordinaryReviewedRuntimeAdmitted();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var host = environment.host().orElseThrow();
        final PerformanceFpsHookInstaller installer = new PerformanceFpsHookInstaller(
            environment.instrumentation(),
            host.artifact(),
            host.classLoader()
        );
        try {
            PerformanceFpsHookRegistry.publish(installer);
        } catch (Throwable failure) {
            installer.close();
            throw failure;
        }
        return new Handle(installer);
    }

    private static final class Handle implements AutoCloseable {
        private final PerformanceFpsHook hook;
        private boolean closed;

        private Handle(final PerformanceFpsHook hook) {
            this.hook = hook;
        }

        @Override public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                hook.close();
            } finally {
                PerformanceFpsHookRegistry.clear(hook);
            }
        }
    }
}
