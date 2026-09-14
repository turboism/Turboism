package dev.turboism.distribution;

import java.nio.file.Path;
import java.util.List;

/**
 * Inspects a plugin distribution package and decides whether it may be installed.
 *
 * <p>Implementations are total: {@code inspect} reports every failure as {@link Rejected} carrying
 * {@link DistributionProblem}s rather than throwing. Inspection is read-only and produces a plan
 * whose recorded bytes must still be revalidated before publication.
 */
public interface PluginPackageInspector {
    /**
     * Inspects one plugin package without installing anything.
     *
     * @param packagePath the package file to inspect
     * @return {@link Accepted} with a validated install plan, or {@link Rejected} carrying
     *         the observed problems; never throws
     */
    Result inspect(Path packagePath);

    /** The verdict of one inspection. */
    sealed interface Result permits Accepted, Rejected {}

    /** Inspection succeeded; carries the validated install plan. */
    final class Accepted implements Result {
        private final PluginInstallPlan plan;

        Accepted(PluginInstallPlan plan) {
            this.plan = java.util.Objects.requireNonNull(plan, "plan");
        }

        /** @return the validated install plan; never {@code null} */
        public PluginInstallPlan plan() { return plan; }
    }

    /**
     * Inspection failed closed.
     *
     * @param problems the observed problems, copied defensively; never empty
     */
    record Rejected(List<DistributionProblem> problems) implements Result {
        public Rejected {
            problems = List.copyOf(problems);
            if (problems.isEmpty()) throw new IllegalArgumentException("problems must not be empty");
        }
    }
}
