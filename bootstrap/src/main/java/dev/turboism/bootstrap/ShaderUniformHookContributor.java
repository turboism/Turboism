package dev.turboism.bootstrap;

import dev.turboism.runtime.log.RuntimeDiagnostics;

/**
 * Declarative contributor for the verified shader uniform optimization. The
 * transformer must observe the host's shader classes before they load, so it
 * installs in {@link Phase#PREMAIN} and closes on the process-exit path.
 */
final class ShaderUniformHookContributor implements HookContributor {

    static final String HOOK_POLICY_ID = "cubism.shader.uniform";

    @Override
    public String id() {
        return "TURBOISM_SHADER_UNIFORM";
    }

    @Override
    public String policyId() {
        return HOOK_POLICY_ID;
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
        return environment.startupPolicy() != null
                && TurboismAgent.shaderUniformEnabled(
                        environment.startupPolicy(),
                        TurboismAgent.shaderUniformRequested(System.getProperty("turboism.shaderUniform.enabled")));
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        try {
            return dev.turboism.adapter.cubism.shader.VerifiedShaderUniformInstaller.install(
                    environment.instrumentation(), code -> RuntimeDiagnostics.debug("shader-uniform", code));
        } catch (final RuntimeException | LinkageError failure) {
            RuntimeDiagnostics.error("shader-uniform", "Shader uniform optimization admission declined", failure);
            return () -> {};
        }
    }
}
