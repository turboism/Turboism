package dev.turboism.distribution;

import java.nio.file.Path;
import java.util.List;

/**
 * Inspects a framework distribution package and decides whether it may be installed.
 *
 * <p>Inspection is read-only - it produces a plan, it never installs anything. Normal
 * validation refusals and I/O failures are reported as {@link Rejected} carrying
 * {@link DistributionProblem}s rather than thrown; implementations may still propagate
 * errors for illegal arguments (e.g. a null {@code packagePath}) and failures outside the
 * classification the runtime can map to a result.
 */
public interface FrameworkPackageInspector {
    /**
     * Inspects one framework package without installing anything.
     *
     * @param packagePath the package file to inspect, non-null
     * @return {@link Accepted} with a validated install plan, or {@link Rejected} carrying
     *         the observed problems; validation and I/O refusals map to {@link Rejected},
     *         while illegal arguments and unclassifiable failures may propagate
     */
    Result inspect(Path packagePath);

    /** The verdict of one inspection. */
    sealed interface Result permits Accepted, Rejected {
    }

    /** Inspection succeeded; carries the validated install plan. */
    final class Accepted implements Result {
        private final FrameworkInstallPlan plan;

        Accepted(FrameworkInstallPlan plan) {
            this.plan = java.util.Objects.requireNonNull(plan, "plan");
        }

        /** @return the validated install plan; never {@code null} */
        public FrameworkInstallPlan plan() { return plan; }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof Accepted that && plan.equals(that.plan);
        }

        @Override public int hashCode() { return plan.hashCode(); }

        @Override public String toString() { return "Accepted[plan=" + plan + "]"; }
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
