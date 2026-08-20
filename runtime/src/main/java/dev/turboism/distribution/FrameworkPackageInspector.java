package dev.turboism.distribution;

import java.nio.file.Path;
import java.util.List;

/**
 * Inspects a framework distribution package and decides whether it may be installed.
 *
 * <p>Implementations are total: {@code inspect} never propagates an exception, reporting every
 * failure as {@link Rejected} carrying {@link DistributionProblem}s instead. Inspection is
 * read-only - it produces a plan, it never installs anything.
 */
public interface FrameworkPackageInspector {
    Result inspect(Path packagePath);

    sealed interface Result permits Accepted, Rejected {
    }

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

    record Rejected(List<DistributionProblem> problems) implements Result {
        public Rejected {
            problems = List.copyOf(problems);
            if (problems.isEmpty()) throw new IllegalArgumentException("problems must not be empty");
        }
    }
}
