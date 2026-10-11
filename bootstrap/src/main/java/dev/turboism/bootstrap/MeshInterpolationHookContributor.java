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
            return false;
        }
        final boolean interpolation = TurboismAgent.meshInterpolationEnabled(
                policy,
                TurboismAgent.meshInterpolationRequested(
                        System.getProperty("turboism.meshInterpolation.enabled")));
        final boolean serialization = TurboismAgent.modelSerializationEnabled(
                policy,
                Boolean.parseBoolean(System.getProperty("turboism.modelSerialization.enabled", "true")));
        return interpolation || serialization;
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final dev.turboism.config.RuntimeStartupConfig policy = environment.startupPolicy();
        final boolean interpolation = TurboismAgent.meshInterpolationEnabled(
                policy,
                TurboismAgent.meshInterpolationRequested(
                        System.getProperty("turboism.meshInterpolation.enabled")));
        final boolean serialization = TurboismAgent.modelSerializationEnabled(
                policy,
                Boolean.parseBoolean(System.getProperty("turboism.modelSerialization.enabled", "true")));
        try {
            return dev.turboism.adapter.cubism.interpolation.VerifiedMeshInterpolationInstaller.install(
                    environment.instrumentation(),
                    code -> RuntimeDiagnostics.debug("mesh-interpolation", code),
                    interpolation,
                    serialization);
        } catch (final RuntimeException | LinkageError failure) {
            RuntimeDiagnostics.error(
                    "mesh-interpolation", "Interpolation optimization admission declined", failure);
            return () -> {};
        }
    }
}
