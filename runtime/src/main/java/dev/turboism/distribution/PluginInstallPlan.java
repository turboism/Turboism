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

    /** @return always {@link PackageKind#PLUGIN}; this plan type describes nothing else */
    public PackageKind packageKind() { return PackageKind.PLUGIN; }

    /** @return the plugin package's declared identity together with the raw archive bytes observed */
    public PluginPackageIdentity packageIdentity() { return packageIdentity; }

    /** @return the descriptor read from the plugin JAR, already cross-checked against the package manifest */
    public PluginDescriptorSnapshot descriptor() { return descriptor; }

    /** @return SHA-256 of the descriptor bytes inside the JAR, 64 lowercase hex characters */
    public String descriptorSha256() { return descriptorSha256; }

    /**
     * @return the files the package would install; never empty and unmodifiable (copied at
     *         construction), so the plan stays a read-only observation
     */
    public List<PlannedFile> files() { return files; }

    /**
     * @return the obligation a caller must honour; always
     *         {@link Requirement#INSPECTION_PREFLIGHT_REVALIDATION_REQUIRED}, meaning every byte
     *         bound by this plan must be revalidated before the plugin is published
     */
    public Requirement requirement() { return requirement; }
}
