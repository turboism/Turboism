package dev.turboism.bootstrap;

import dev.turboism.runtime.log.RuntimeDiagnostics;

/**
 * Base for the native optimization hooks that install after the exact host is
 * admitted but before the preview runtime starts, because runtime startup can
 * already open and decode the initial document.
 *
 * <p>Admission is layered exactly as the previous hand-wired agent layered it:
 * {@link #admitted(HookEnvironment)} only carries the full-runtime gate, while
 * {@link #installAdmitted(HookEnvironment)} re-checks the persisted preference
 * and the installer's own verified admission, reporting
 * {@code installation=NOT_ADMITTED} and returning a no-op handle when refused.
 * Installation failures never escape: they are reported as
 * {@code installation=FAILED} and leave the hook uninstalled, so a failed
 * optimization can never stop official startup.</p>
 *
 * <p>The completion flag kept here is per start attempt, matching the
 * {@link MeshMirrorHookContributor} precedent; the close handle itself owns no
 * protocol line because these hooks do not close on the process-exit path.</p>
 */
abstract class NativeOptimizationHookContributor implements HookContributor {

    private final String protocolId;
    private final String policyId;
    private boolean complete;
    private String refusalReason = "not-admitted";
    private static final AutoCloseable NOT_INSTALLED = () -> {};

    NativeOptimizationHookContributor(final String protocolId) {
        this(protocolId, protocolId);
    }

    NativeOptimizationHookContributor(final String protocolId, final String policyId) {
        this.protocolId = protocolId;
        this.policyId = policyId;
    }

    @Override
    public final String id() {
        return protocolId;
    }

    @Override
    public final String policyId() {
        return policyId;
    }

    @Override
    public final Phase phase() {
        return Phase.HOST_RESOLVED;
    }

    @Override
    public final boolean admitted(final HookEnvironment environment) {
        final boolean admitted = environment.runtimeSliceAdmitted(HOOK_SLICE);
        if (!admitted) {
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    protocolId, "not-installed", "host-not-admitted");
        }
        return admitted;
    }

    @Override
    public final AutoCloseable install(final HookEnvironment environment) {
        complete = false;
        refusalReason = "not-admitted";
        try {
            final AutoCloseable handle = installAdmitted(environment);
            complete = handle != NOT_INSTALLED;
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    protocolId, complete ? "installed" : "not-installed", complete ? "verified" : refusalReason);
            if (complete) log(environment, protocolId + " installation=COMPLETE");
            return () -> {
                try {
                    handle.close();
                } finally {
                    complete = false;
                    dev.turboism.config.NativeOptimizationDiagnostics.installed(protocolId, "not-installed", "closed");
                }
            };
        } catch (final Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    protocolId, "failed", failure.getClass().getSimpleName());
            log(
                    environment,
                    protocolId + " installation=FAILED " + failure.getClass().getName() + ": " + failure.getMessage());
            return () -> {};
        }
    }

    @Override
    public final void bind(final HookEnvironment environment) {
        if (complete) {
            log(environment, protocolId + " installation=COMPLETE phase=runtime-ready");
        }
    }

    /**
     * Runs the preference and verified-installer admission gates and installs
     * the hook. A refused hook reports {@code installation=NOT_ADMITTED} (where
     * the previous wiring did) and returns a no-op handle.
     *
     * @param environment the resolved host environment
     * @return the verified installer close handle, or a no-op
     * @throws Exception when installation fails
     */
    abstract AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception;

    /**
     * Every native optimization contract is recovered against the host's
     * editor-model classes; the slice gate only decides whether a hook may attempt
     * its own target proof — {@code ReviewedHostContract} still resolves the actual
     * target class bytes before any transformation.
     */
    static final String HOOK_SLICE = "editor-model";

    static void log(final HookEnvironment environment, final String line) {
        final var runtime = environment.runtime();
        if (runtime.isEmpty()) {
            RuntimeDiagnostics.info("bootstrap", line);
        } else {
            runtime.get().info("bootstrap", line);
        }
    }

    /** Refuses installation with a diagnostic reason, without reporting completion. */
    final AutoCloseable refused(final String reason) {
        refusalReason = reason;
        return NOT_INSTALLED;
    }

    static AutoCloseable noOp() {
        return NOT_INSTALLED;
    }
}
