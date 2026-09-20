package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.ui.context.NativeObjectContextMenuBridge;
import dev.turboism.ui.context.NativeParameterPointContextMenuBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Declarative contributor for the object context-menu hook. The native
 * bridge registrations and both installers succeed or roll back as one unit,
 * matching the previous manual wiring.
 */
final class ObjectContextMenuHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_OBJECT_CONTEXT_MENU_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.fullRuntimeAdmission();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final ObjectContextMenuHostProfile profile = ObjectContextMenuHostProfile.forArtifact(
            HostArtifactDigest.from(host.artifact())
        ).orElseThrow(() -> new IllegalStateException(
            "Unsupported object context-menu host artifact"
        ));
        final var handler = runtime.hostAccess().objectContextMenuHandler();
        if (handler == null) {
            throw new IllegalStateException(
                "Object context-menu runtime handler is unavailable"
            );
        }
        final List<AutoCloseable> handles = new ArrayList<>(4);
        try {
            handles.add(NativeObjectContextMenuBridge.install(handler));
            final var parameterPointHandler = runtime.hostAccess().parameterPointMenuHandler();
            if (parameterPointHandler == null) {
                throw new IllegalStateException(
                    "Parameter-point context-menu runtime handler is unavailable"
                );
            }
            handles.add(NativeParameterPointContextMenuBridge.install(parameterPointHandler));

            final VerifiedObjectContextMenuHookInstaller installer =
                new VerifiedObjectContextMenuHookInstaller(
                    environment.instrumentation(),
                    profile.bindings(),
                    host.classLoader()
                );
            installer.install();
            handles.add(installer);

            final ParameterPointContextMenuHostProfile parameterPointProfile =
                ParameterPointContextMenuHostProfile.forArtifact(
                    HostArtifactDigest.from(host.artifact())
                ).orElseThrow(() -> new IllegalStateException(
                    "Unsupported parameter-point context-menu host artifact"
                ));
            final VerifiedParameterPointContextMenuHookInstaller parameterPointInstaller =
                new VerifiedParameterPointContextMenuHookInstaller(
                    environment.instrumentation(),
                    parameterPointProfile.owner(),
                    parameterPointProfile.contextDescriptor(),
                    host.classLoader()
                );
            parameterPointInstaller.install();
            handles.add(parameterPointInstaller);
        } catch (Throwable failure) {
            closeQuietly(handles);
            throw failure;
        }
        return () -> closeQuietly(handles);
    }

    private static void closeQuietly(final List<AutoCloseable> handles) {
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).close();
            } catch (Throwable ignored) {
                // Best-effort rollback; the install already failed closed.
            }
        }
    }
}
