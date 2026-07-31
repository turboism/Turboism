package dev.turboism.distribution;

import java.nio.file.Path;
import java.util.Objects;

/** Hash-bound staged plugin JAR produced from an accepted strict package plan. */
public record PreparedPluginPackage(PluginInstallPlan plan, Path stagedJar) {
    public PreparedPluginPackage {
        plan = Objects.requireNonNull(plan, "plan");
        stagedJar = Objects.requireNonNull(stagedJar, "stagedJar").toAbsolutePath().normalize();
    }
}
