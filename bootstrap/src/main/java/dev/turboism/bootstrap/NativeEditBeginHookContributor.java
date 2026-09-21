package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.editor.history.NativeEditBeginBridge;
import dev.turboism.adapter.cubism.editor.history.VerifiedNativeEditBeginHookInstaller;

/**
 * Declarative contributor for the verified native edit-begin hook. It needs
 * the runtime's verified editor-model resolver, so it installs in
 * {@link Phase#RUNTIME_STARTED} and closes on the process-exit path with the
 * {@code cleanup=COMPLETE} protocol line.
 */
final class NativeEditBeginHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_NATIVE_EDIT_BEGIN_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean closesOnProcessExit() {
        return true;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.ordinaryReviewedRuntimeAdmitted();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        VerifiedNativeEditBeginHookInstaller installer = null;
        try {
            installer = VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                environment.instrumentation(),
                runtime.editorModelResolver(),
                host.classLoader()
            );
            installer.install(NativeEditBeginBridge.ingress());
            NativeOptimizationHookContributor.log(
                environment,
                "TURBOISM_NATIVE_EDIT_BEGIN_HOOK installation=COMPLETE retransformed="
                    + String.join(",", installer.retransformedClassNames())
            );
            final VerifiedNativeEditBeginHookInstaller installed = installer;
            return () -> {
                NativeEditBeginBridge.unbind();
                installed.close();
            };
        } catch (final Throwable failure) {
            if (installer != null) {
                try {
                    installer.close();
                } catch (final Throwable ignored) {
                    // cleanup is best effort
                }
            }
            NativeOptimizationHookContributor.log(
                environment,
                "Turboism native edit entry hook disabled safely: "
                    + failure.getClass().getName()
            );
            return () -> { };
        }
    }
}
