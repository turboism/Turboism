package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;
import java.util.Objects;

/**
 * Outcome of one {@link ProtectedExportPlanner} run: an {@link Admitted} plan the runtime
 * still has to verify against its own census, or a {@link Rejected} bounded reason key.
 *
 * <p>A result never authorizes work. {@code Rejected} is the fail-closed answer for a
 * census the planner cannot rewrite deterministically; the runtime reports the key and
 * refuses the export.</p>
 */
@CubismEditor(from = "5.1.00")
public sealed interface ProtectedExportPlanResult {

    /** The planner's complete plan; the runtime verifies it entry by entry before use. */
    @CubismEditor(from = "5.1.00")
    record Admitted(ProtectedExportPlan plan) implements ProtectedExportPlanResult {
        public Admitted {
            plan = Objects.requireNonNull(plan, "plan");
        }
    }

    /** The planner refused the census; {@code reasonKey} is a bounded message key. */
    @CubismEditor(from = "5.1.00")
    record Rejected(String reasonKey) implements ProtectedExportPlanResult {
        private static final int MAX_KEY_LENGTH = 128;

        public Rejected {
            Objects.requireNonNull(reasonKey, "reasonKey");
            if (reasonKey.length() > MAX_KEY_LENGTH || !reasonKey.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("reasonKey must be a lowercase key");
            }
        }
    }
}
