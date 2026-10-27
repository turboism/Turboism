package dev.turboism.bootstrap;

import dev.turboism.runtime.log.RuntimeDiagnostics;

/**
 * Declarative contributor for the verified mesh interpolation optimization.
 * The transformer must observe the host's interpolation classes before they
 * load, so it installs in {@link Phase#PREMAIN} and closes on the
 * process-exit path. The installer also carries the model-serialization
 * feature flag, so admission passes when either feature is enabled.
 */
final class MeshInterpolationHookContributor implements HookContributor {

    static final String INTERPOLATION_POLICY_ID = "cubism.mesh.interpolation";
    static final String SERIALIZATION_POLICY_ID = "cubism.model.serialization";

    @Override
    public String id() {
        return "TURBOISM_MESH_INTERPOLATION";
    }

    @Override
    public String policyId() {
        return INTERPOLATION_POLICY_ID;
    }

    @Override
    public Phase phase() {
        return Phase.PREMAIN;
    }

    @Override
    public boolean closesOnProcessExit() {
        return true;
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        final dev.turboism.config.RuntimeStartupConfig policy = environment.startupPolicy();
        if (policy == null) {
            dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "policy-missing");
            return false;
        }
        final boolean requested = NativeOptimizationPolicy.meshInterpolationEnabled(environment);
        final boolean interpolation = TurboismAgent.meshInterpolationEnabled(policy, requested);
        final boolean serialization = TurboismAgent.modelSerializationEnabled(
                policy, Boolean.parseBoolean(System.getProperty("turboism.modelSerialization.enabled", "false")));
        final boolean admitted = interpolation || serialization;
        if (!interpolation) {
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    id(), "not-installed", requested ? "policy-disabled" : "preference-disabled");
        }
        return admitted;
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final dev.turboism.config.RuntimeStartupConfig policy = environment.startupPolicy();
        final boolean requested = NativeOptimizationPolicy.meshInterpolationEnabled(environment);
        final boolean interpolation = TurboismAgent.meshInterpolationEnabled(policy, requested);
        final boolean serialization = TurboismAgent.modelSerializationEnabled(
                policy, Boolean.parseBoolean(System.getProperty("turboism.modelSerialization.enabled", "false")));

        if (interpolation) {
            dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "pending", "waiting-class-load");
        } else {
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    id(), "not-installed", requested ? "policy-disabled" : "preference-disabled");
        }

        final java.util.function.Consumer<String> diagnosticCallback = code -> {
            RuntimeDiagnostics.debug("mesh-interpolation", code);
            if (interpolation) {
                if (code.startsWith("MESH_INTERPOLATION_PATCHED_")) {
                    dev.turboism.config.NativeOptimizationDiagnostics.installed(
                            id(), "installed", code.substring("MESH_INTERPOLATION_".length()));
                } else if (code.startsWith("MESH_INTERPOLATION_DECLINED_")) {
                    dev.turboism.config.NativeOptimizationDiagnostics.installed(
                            id(), "not-installed", code.substring("MESH_INTERPOLATION_".length()));
                }
            }
        };

        try {
            final var handle = dev.turboism.adapter.cubism.interpolation.VerifiedMeshInterpolationInstaller.install(
                    environment.instrumentation(), diagnosticCallback, interpolation, serialization);
            if (handle != null) {
                return () -> {
                    try {
                        handle.close();
                    } finally {
                        dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "closed");
                    }
                };
            } else {
                dev.turboism.config.NativeOptimizationDiagnostics.installed(
                        id(), "not-installed", requested ? "declined" : "preference-disabled");
                return () -> {};
            }
        } catch (final RuntimeException | LinkageError failure) {
            RuntimeDiagnostics.error("mesh-interpolation", "Interpolation optimization admission declined", failure);
            dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "failed");
            return () -> {};
        }
    }
}
