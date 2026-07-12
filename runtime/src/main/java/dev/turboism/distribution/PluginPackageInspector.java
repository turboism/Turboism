package dev.turboism.distribution;

import java.nio.file.Path;
import java.util.List;

public interface PluginPackageInspector {
    Result inspect(Path packagePath);

    sealed interface Result permits Accepted, Rejected {}

    final class Accepted implements Result {
        private final PluginInstallPlan plan;

        Accepted(PluginInstallPlan plan) {
            this.plan = java.util.Objects.requireNonNull(plan, "plan");
        }

        public PluginInstallPlan plan() { return plan; }
    }

    record Rejected(List<DistributionProblem> problems) implements Result {
        public Rejected {
            problems = List.copyOf(problems);
            if (problems.isEmpty()) throw new IllegalArgumentException("problems must not be empty");
        }
    }
}
