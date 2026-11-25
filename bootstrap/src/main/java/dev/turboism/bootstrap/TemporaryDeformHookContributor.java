package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import dev.turboism.adapter.cubism.temporarydeform.TemporaryDeformHostProfile;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.SelectorAdmission;
import java.util.Set;

/** Installs verified temporary deform tool hooks for the active Cubism Modeling session. */
final class TemporaryDeformHookContributor implements HookContributor {

    @Override
    public String id() {
        return "TURBOISM_TEMPORARY_DEFORM_HOOK";
    }

    @Override
    public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override
    public boolean closesOnProcessExit() {
        return true;
    }

    @Override
    public Set<String> runtimeHookIds() {
        return Set.of("temporary-deform-tools");
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        return environment.attachmentMode() == AttachmentMode.PREMAIN
                && environment.runtimeSliceAdmitted("editor-model")
                && environment
                        .runtime()
                        .map(runtime -> {
                            final var resolver = runtime.editorModelResolver();
                            return SelectorAdmission.authorizesModelingSelection(resolver)
                                    && TemporaryDeformHostProfile.forVersion(resolver.admittedCubismVersion())
                                            .isPresent();
                        })
                        .orElse(false);
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) {
        if (!admitted(environment)) {
            throw new IllegalStateException("temporary deform hook is not admitted");
        }
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final var resolver = runtime.editorModelResolver();
        final var coordinator = runtime.hostAccess().temporaryDeformCoordinator();
        final long generation =
                runtime.hostAccess().editorUiLifecycle().snapshot().generation();
        final var installer = new VerifiedTemporaryDeformHookInstaller(
                environment.instrumentation(),
                environment.attachmentMode(),
                host.classLoader(),
                TemporaryDeformHostProfile.from(resolver),
                host.artifact(),
                coordinator,
                environment.loadedClasses());
        try {
            coordinator.connect(resolver, generation);
            installer.install();
            coordinator.lifecycleReady(generation, true);
        } catch (RuntimeException | Error failure) {
            // @containment-exempt: disconnects coordinator and closes installer before rethrowing
            Throwable toThrow = failure;
            coordinator.disconnect();
            try {
                installer.close();
            } catch (RuntimeException | Error cleanup) {
                // @containment-exempt: cleanup failure merged, prioritizing fatal error
                toThrow = FatalErrors.mergeForCleanup(toThrow, cleanup);
            }
            throw FatalErrors.propagate(toThrow);
        }
        return () -> {
            coordinator.lifecycleReady(generation, false);
            try {
                installer.close();
            } finally {
                coordinator.disconnect();
            }
        };
    }
}
