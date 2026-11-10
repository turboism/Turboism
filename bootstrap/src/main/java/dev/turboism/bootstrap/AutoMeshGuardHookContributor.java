package dev.turboism.bootstrap;

import dev.turboism.automesh.AutoMeshAlgorithmCatalog;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.AutoMeshSelectorHost;
import dev.turboism.automesh.AutoMeshSelectorSource;
import dev.turboism.automesh.host.AutoMeshContainer;
import dev.turboism.automesh.host.AutoMeshFunnel;
import dev.turboism.automesh.host.AutoMeshHostProfile;
import dev.turboism.automesh.host.NativeAutoMeshBridge;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.kernel.KernelRuntime;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;

/**
 * Declarative contributor for the verified Auto Mesh funnel guard and container attach hooks.
 *
 * <p>The guard answers one confirmation of the native Auto Mesh Generate entry. Until the runtime
 * binds a dispatch policy that resolves host targets, the policy answers "not handled" for every
 * confirmation, so the Editor's own generator keeps its geometry, its Undo group and its
 * diagnostics. The guard is installed anyway, because the transformed bytecode must be proven live
 * on a real host before a selector can depend on it.</p>
 *
 * <p>The attach hooks add the algorithm selector row to the three native containers. The row is
 * created only when a plugin algorithm is registered, so an Editor without an algorithm plugin
 * shows the native popup exactly as before.</p>
 */
final class AutoMeshGuardHookContributor implements HookContributor {

    private static final String HOOK_ID = "TURBOISM_AUTOMESH_GUARD_HOOK";
    private static final String LOG_COMPONENT = "automesh";

    @Override
    public String id() {
        return HOOK_ID;
    }

    @Override
    public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override
    public boolean closesOnProcessExit() {
        return true;
    }

    /**
     * Auto-mesh admission: the ordinary reviewed-runtime gate plus this hook's own exact release
     * requirement.
     *
     * <p>The shared gate admits every reviewed build, but the funnel and container selectors are
     * pinned per exact reviewed build. Narrowing here keeps the gate and the capability in
     * agreement, so an admitted-but-unsupported build never attempts installation.</p>
     */
    @Override
    public boolean admitted(final HookEnvironment environment) {
        return runtimeAdmitted(environment.profile(), environment.fullRuntimeAdmission());
    }

    static boolean runtimeAdmitted(final String profile, final boolean fullRuntimeAdmission) {
        return profile != null
                && fullRuntimeAdmission
                && ReviewedHostArtifacts.admitsFullRuntime(profile)
                && AutoMeshHostProfile.supportedHostVersions().contains(profile);
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final KernelRuntime runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final AutoMeshHostProfile profile = AutoMeshHostProfile.forArtifact(HostArtifactDigest.from(host.artifact()))
                .orElseThrow(() -> new IllegalStateException("Unsupported auto mesh guard host artifact"));
        final AutoMeshSelectorHost selectorHost = selectorHostOf(runtime);
        final VerifiedAutoMeshHookInstaller installer = new VerifiedAutoMeshHookInstaller(
                environment.instrumentation(),
                profile,
                bridgeHandlerOf(runtime, selectorHost),
                message -> runtime.warn(LOG_COMPONENT, message),
                host.classLoader());
        try {
            installer.install();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            selectorHost.close();
            installer.close();
            throw failure;
        }
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        return new CombinedCloseable(installer, selectorHost);
    }

    /**
     * Builds the session selector host around the runtime's own catalog.
     *
     * <p>The host is created even while no algorithm is registered, because a plugin can register
     * one later and the containers are constructed lazily. A runtime without a composed plugin
     * runtime yields an empty listing, which adds no row.</p>
     *
     * <p>The row and the native guard must decide on one selection state, otherwise a preset the row
     * applies never reaches the mesh the guard computes. The runtime's bound instance is therefore
     * reused; only a runtime that has not bound one falls back to a catalog-backed state.</p>
     */
    private static AutoMeshSelectorHost selectorHostOf(final KernelRuntime runtime) {
        final AutoMeshSelectionState fallback = new AutoMeshSelectionState(new CatalogSession(runtime));
        final AutoMeshSelectorSource source = new AutoMeshSelectorSource() {
            @Override
            public List<dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm> algorithms() {
                return runtime.autoMeshCatalog()
                        .map(AutoMeshAlgorithmCatalog::algorithms)
                        .orElse(List.of());
            }

            @Override
            public AutoMeshSelectionState selection() {
                try {
                    return runtime.autoMeshSelection();
                } catch (IllegalStateException unbound) {
                    return fallback;
                }
            }
        };
        return new AutoMeshSelectorHost(
                source, runtime.effectiveLocale(), message -> runtime.warn(LOG_COMPONENT, message));
    }

    /**
     * Binds the funnels to whatever policy the runtime holds now, and routes container attaches to
     * the selector host.
     *
     * <p>The delegate resolves per confirmation instead of at install time, because the policy is
     * bound after plugins load and must survive a plugin reload without reinstalling the
     * transformers.</p>
     */
    private static NativeAutoMeshBridge.Handler bridgeHandlerOf(
            final KernelRuntime runtime, final AutoMeshSelectorHost selectorHost) {
        return new NativeAutoMeshBridge.Handler() {
            @Override
            public Boolean handle(final AutoMeshFunnel funnel, final Object[] hostArguments) {
                return runtime.autoMeshGuard()
                        .map(policy -> policy.handle(funnel, hostArguments))
                        .orElse(Boolean.FALSE);
            }

            @Override
            public void attach(final AutoMeshContainer container, final Object instance) {
                selectorHost.attach(instance);
            }
        };
    }

    /**
     * Session view over the runtime's own catalog.
     *
     * <p>The catalog owns the selection, so the row and the dispatch guard always agree on which
     * algorithm runs. A runtime without a composed plugin runtime reports no selection at all, which
     * is the Editor's own generator.</p>
     */
    private record CatalogSession(KernelRuntime runtime) implements dev.turboism.automesh.AutoMeshSessionAlgorithms {

        @Override
        public java.util.Optional<String> selectedAlgorithmId() {
            return runtime.autoMeshCatalog().flatMap(AutoMeshAlgorithmCatalog::selectedAlgorithmId);
        }

        @Override
        public java.util.Optional<dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm> find(
                final String algorithmId) {
            return runtime.autoMeshCatalog().flatMap(catalog -> catalog.find(algorithmId));
        }

        @Override
        public void select(final String algorithmId) {
            runtime.autoMeshCatalog().ifPresent(catalog -> catalog.select(algorithmId));
        }
    }

    /** Closes the installer first, so the host bytes are restored before the row owner disappears. */
    private record CombinedCloseable(AutoCloseable installer, AutoCloseable selectorHost) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            try {
                installer.close();
            } finally {
                selectorHost.close();
            }
        }
    }
}
