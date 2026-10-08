package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract;
import dev.turboism.adapter.cubism.modeling.ModelingToolHostProfile;
import dev.turboism.adapter.cubism.modeling.NativeModelingToolBridge;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import java.util.Set;

/** Adds ordinary-tool switching after the existing mesh/document lifecycle hook is admitted. */
final class ModelingToolHookContributor implements HookContributor {
    @Override
    public String id() {
        return "TURBOISM_MODELING_TOOL_HOOK";
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
        return Set.of("modeling-tool-lifecycle");
    }

    @Override
    public boolean admitted(HookEnvironment environment) {
        return environment.attachmentMode() == AttachmentMode.PREMAIN
                && environment.runtimeSliceAdmitted("editor-model")
                && environment
                        .runtime()
                        .map(runtime -> ModelingSelectionSelectorContract.authorizes(runtime.editorModelResolver()))
                        .orElse(false);
    }

    @Override
    public AutoCloseable install(HookEnvironment environment) {
        if (!admitted(environment)) throw new IllegalStateException("ordinary tool hook is not admitted");
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final var resolver = runtime.editorModelResolver();
        final var coordinator = runtime.hostAccess().modelingToolCoordinator();
        final long generation =
                runtime.hostAccess().editorUiLifecycle().snapshot().generation();
        final Object owner = new Object();
        final var installer = new VerifiedModelingToolHookInstaller(
                environment.instrumentation(),
                environment.attachmentMode(),
                host.classLoader(),
                ModelingToolHostProfile.from(resolver),
                host.artifact(),
                () -> NativeModelingToolBridge.install(owner, coordinator),
                () -> NativeModelingToolBridge.uninstall(owner));
        try {
            coordinator.connect(resolver, generation);
            installer.install();
            coordinator.lifecycleReady(generation, true);
        } catch (RuntimeException | Error failure) {
            coordinator.disconnect();
            try {
                installer.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
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
