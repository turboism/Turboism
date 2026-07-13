package dev.turboism.distribution;

import java.util.List;
import java.util.Objects;

/** Immutable read-only inspection output; lifecycle preflight must revalidate all bound bytes. */
public final class PluginInstallPlan {
    public enum Requirement {
        INSPECTION_PREFLIGHT_REVALIDATION_REQUIRED
    }

    private final PluginPackageIdentity packageIdentity;
    private final PluginDescriptorSnapshot descriptor;
    private final String descriptorSha256;
    private final List<PlannedFile> files;
    private final Requirement requirement;

    PluginInstallPlan(PluginPackageIdentity identity, PluginDescriptorSnapshot descriptor,
                      String descriptorSha256, List<PlannedFile> files, Requirement requirement) {
        this.packageIdentity = Objects.requireNonNull(identity, "packageIdentity");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.descriptorSha256 = Objects.requireNonNull(descriptorSha256, "descriptorSha256");
        if (!descriptorSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid descriptorSha256");
        this.files = List.copyOf(files);
        if (this.files.isEmpty()) throw new IllegalArgumentException("plugin files required");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
    }

    public PackageKind packageKind() { return PackageKind.PLUGIN; }
    public PluginPackageIdentity packageIdentity() { return packageIdentity; }
    public PluginDescriptorSnapshot descriptor() { return descriptor; }
    public String descriptorSha256() { return descriptorSha256; }
    public List<PlannedFile> files() { return files; }
    public Requirement requirement() { return requirement; }
}
