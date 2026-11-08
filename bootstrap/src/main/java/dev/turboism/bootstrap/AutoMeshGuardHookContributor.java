package dev.turboism.bootstrap;

import dev.turboism.automesh.host.AutoMeshHostProfile;
import dev.turboism.automesh.host.NativeAutoMeshBridge;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.kernel.KernelRuntime;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

/**
 * Declarative contributor for the verified Auto Mesh funnel guard.
 *
 * <p>The guard answers one confirmation of the native Auto Mesh Generate entry. Until the runtime
 * binds a dispatch policy that resolves host targets, the policy answers "not handled" for every
 * confirmation, so the Editor's own generator keeps its geometry, its Undo group and its
 * diagnostics. The guard is installed anyway, because the transformed bytecode must be proven
 * live on a real host before a selector can depend on it.</p>
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
     * <p>The shared gate admits every reviewed build, but the funnel selectors are pinned per
     * exact reviewed build. Narrowing here keeps the gate and the capability in agreement, so an
     * admitted-but-unsupported build never attempts installation.</p>
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
        final VerifiedAutoMeshHookInstaller installer = new VerifiedAutoMeshHookInstaller(
                environment.instrumentation(),
                profile,
                dispatchPolicyOf(runtime),
                message -> runtime.warn(LOG_COMPONENT, message),
                host.classLoader());
        try {
            installer.install();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            installer.close();
            throw failure;
        }
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        return installer;
    }

    /**
     * Binds the funnels to whatever policy the runtime holds now.
     *
     * <p>The delegate resolves per confirmation instead of at install time, because the policy is
     * bound after plugins load and must survive a plugin reload without reinstalling the
     * transformers.</p>
     */
    private static NativeAutoMeshBridge.Handler dispatchPolicyOf(final KernelRuntime runtime) {
        return (funnel, hostArguments) -> runtime.autoMeshGuard()
                .map(policy -> policy.handle(funnel, hostArguments))
                .orElse(Boolean.FALSE);
    }
}
