package dev.turboism.distribution;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.List;
import java.util.Objects;

/** Immutable read-only inspection output; lifecycle preflight must revalidate all bound bytes. */
public final class PluginInstallPlan {
    public enum Requirement {
        INSPECTION_PREFLIGHT_REVALIDATION_REQUIRED
    }

    private final PluginPackageIdentity packageIdentity;
    private final PluginDescriptor descriptor;
    private final List<PlannedFile> files;
    private final Requirement requirement;

    PluginInstallPlan(PluginPackageIdentity identity, PluginDescriptor descriptor,
                      List<PlannedFile> files, Requirement requirement) {
        this.packageIdentity = Objects.requireNonNull(identity, "packageIdentity");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.files = List.copyOf(files);
        if (this.files.isEmpty()) throw new IllegalArgumentException("plugin files required");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
    }

    public PackageKind packageKind() { return PackageKind.PLUGIN; }
    public PluginPackageIdentity packageIdentity() { return packageIdentity; }
    public PluginDescriptor descriptor() { return descriptor; }
    public List<PlannedFile> files() { return files; }
    public Requirement requirement() { return requirement; }
}
