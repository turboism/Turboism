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
        final dev.turboism.config.RuntimeStartupConfig policy = environment.startupPolicy();
        if (policy == null) {
            dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "policy-missing");
            return false;
        }
        final boolean requested = NativeOptimizationPolicy.shaderUniformEnabled(environment);
        final boolean admitted = TurboismAgent.shaderUniformEnabled(policy, requested);
        if (!admitted) {
            dev.turboism.config.NativeOptimizationDiagnostics.installed(
                    id(), "not-installed", requested ? "policy-disabled" : "preference-disabled");
        }
        return admitted;
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "pending", "waiting-class-load");
        final java.util.function.Consumer<String> diagnosticCallback = code -> {
            RuntimeDiagnostics.debug("shader-uniform", code);
            if (code.startsWith("SHADER_UNIFORM_PATCHED_")) {
                dev.turboism.config.NativeOptimizationDiagnostics.installed(
                        id(), "installed", code.substring("SHADER_UNIFORM_".length()));
            } else if (code.startsWith("SHADER_UNIFORM_DECLINED_")) {
                dev.turboism.config.NativeOptimizationDiagnostics.installed(
                        id(), "not-installed", code.substring("SHADER_UNIFORM_".length()));
            }
        };
        try {
            final var handle = dev.turboism.adapter.cubism.shader.VerifiedShaderUniformInstaller.install(
                    environment.instrumentation(), diagnosticCallback);
            if (handle != null) {
                return () -> {
                    try {
                        handle.close();
                    } finally {
                        dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "closed");
                    }
                };
            }
            dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "declined");
            return () -> {};
        } catch (final RuntimeException | LinkageError failure) {
            RuntimeDiagnostics.error("shader-uniform", "Shader uniform optimization admission declined", failure);
            dev.turboism.config.NativeOptimizationDiagnostics.installed(id(), "not-installed", "failed");
            return () -> {};
        }
    }
}
