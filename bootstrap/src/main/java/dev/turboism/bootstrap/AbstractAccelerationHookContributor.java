package dev.turboism.bootstrap;

import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.optimization.Optimization;
import java.lang.instrument.ClassFileTransformer;
import java.util.Objects;

/** Shared lifecycle and diagnostics reporting for premain bytecode acceleration contributors. */
abstract class AbstractAccelerationHookContributor implements ReportedHookContributor {

    private static final String INSTALLED = "installed";
    private static final String NOT_INSTALLED = "not-installed";
    private static final String PREFERENCE_DISABLED = "preference-disabled";
    private static final String POLICY_DISABLED = "policy-disabled";
    private static final String CLOSED = "closed";

    private final String id;
    private final String policyId;
    private final Optimization optimization;

    AbstractAccelerationHookContributor(final String id, final String policyId, final Optimization optimization) {
        this.id = Objects.requireNonNull(id, "id");
        this.policyId = Objects.requireNonNull(policyId, "policyId");
        this.optimization = Objects.requireNonNull(optimization, "optimization");
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String policyId() {
        return policyId;
    }

    @Override
    public final Phase phase() {
        return Phase.PREMAIN;
    }

    @Override
    public final boolean closesOnProcessExit() {
        return true;
    }

    @Override
    public final boolean admitted(final HookEnvironment environment) {
        final boolean requested = environment.plan().choice(optimization).enabled();
        final boolean admitted = environment.startupPolicy() != null
                && environment.startupPolicy().hookEnabled(policyId)
                && requested;
        if (!admitted) {
            NativeOptimizationDiagnostics.installed(
                    id, NOT_INSTALLED, requested ? POLICY_DISABLED : PREFERENCE_DISABLED);
        }
        return admitted;
    }

    @Override
    public final AutoCloseable install(final HookEnvironment environment) {
        final var instrumentation = environment.instrumentation();
        final ClassFileTransformer transformer = createTransformer();
        instrumentation.addTransformer(transformer);
        NativeOptimizationDiagnostics.installed(id, INSTALLED, INSTALLED);
        return () -> {
            try {
                instrumentation.removeTransformer(transformer);
            } finally {
                NativeOptimizationDiagnostics.installed(id, NOT_INSTALLED, CLOSED);
            }
        };
    }

    /** Creates the hook's {@link ClassFileTransformer} for premain registration. */
    protected abstract ClassFileTransformer createTransformer();
}
