package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Contributor for the combined {@code launcher.mesaGlThread} option, default
 * on under Wine/Proton. Deferred checks require the verified uniform lifecycle
 * and report path. Only {@code deferred=ACTIVE}, not installation=COMPLETE,
 * proves both reviewed rewrites were admitted. A requested but inactive hook
 * emits a warning because Mesa alone can regress performance.
 */
final class DeferredGlErrorCheckHookContributor extends NativeOptimizationHookContributor {

    private static final Map<String, MethodType> REQUIRED_SEAM = Map.of(
        UniformLocationHookBridge.BEGIN_PROPERTY, MethodType.methodType(long.class, Object.class),
        UniformLocationHookBridge.END_PROPERTY, MethodType.methodType(void.class, long.class),
        UniformLocationHookBridge.ERROR_PROPERTY, MethodType.methodType(void.class, Object.class, int.class),
        UniformLocationHookBridge.DEFER_QUERY_PROPERTY,
            MethodType.methodType(int.class, Object.class, String.class, boolean.class),
        UniformLocationHookBridge.DEFER_REPORT_PROPERTY, MethodType.methodType(Object.class));

    DeferredGlErrorCheckHookContributor() {
        super("TURBOISM_DEFERRED_GL_ERROR_CHECK");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!DeferredGlErrorCheckTransformer.enabledByPreference()) {
            log(environment, id() + " deferred=INACTIVE installation=NOT_ADMITTED reason=preference-disabled");
            return noOp();
        }
        final String seamFailure = seamFailure();
        if (seamFailure != null) {
            warnInactive(environment, seamFailure);
            return noOp();
        }
        try {
            final var host = environment.host().orElseThrow();
            if (!VerifiedDeferredGlErrorCheckInstaller.admitted(
                    HostArtifactDigest.from(host.artifact()),
                    NativeOptimizationPolicy.load(environment.options().home()),
                    true,
                    Runtime.version().feature())) {
                warnInactive(environment, "admission-refused");
                return noOp();
            }
            final VerifiedDeferredGlErrorCheckInstaller installer =
                new VerifiedDeferredGlErrorCheckInstaller(
                    environment.instrumentation(), host.artifact(), host.classLoader());
            installer.install();
            log(environment, id() + " deferred=ACTIVE targets=" + installer.targetDescription());
            return installer;
        } catch (Exception | Error failure) {
            warnInactive(environment, "installation-failed");
            throw failure;
        }
    }

    private static String seamFailure() {
        for (var entry : REQUIRED_SEAM.entrySet()) {
            final Object slot = System.getProperties().get(entry.getKey());
            if (!(slot instanceof MethodHandle handle) || !handle.type().equals(entry.getValue())) {
                return "uniform-seam-unavailable";
            }
        }
        try {
            final Object statistics = System.getProperties().get(UniformLocationHookBridge.STATS_PROPERTY);
            if (statistics instanceof Supplier<?> supplier && supplier.get() instanceof Map<?, ?> values
                && Long.valueOf(1).equals(values.get("active"))
                && Long.valueOf(1).equals(values.get("deferredReady"))
                && Long.valueOf(1).equals(values.get("mutationCoverage"))) {
                return null;
            }
        } catch (RuntimeException | Error unavailable) {
            // Broken observers cannot turn an unknown seam into an admitted one.
        }
        return "uniform-seam-not-ready";
    }

    private void warnInactive(final HookEnvironment environment, final String reason) {
        final String line = id() + " deferred=INACTIVE reason=" + reason
            + "; requested mesaGlThread lacks deferred checks; Mesa-only performance may regress";
        if (environment.runtime().isPresent()) {
            environment.runtime().get().warn("bootstrap", line);
        } else {
            RuntimeDiagnostics.warn("bootstrap", line);
        }
    }
}
