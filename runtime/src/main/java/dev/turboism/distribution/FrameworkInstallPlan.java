package dev.turboism.distribution;

import java.util.List;
import java.util.Objects;

/** Immutable inspection output. Installation must revalidate package identity and extract link-aware. */
public final class FrameworkInstallPlan {
    public enum Requirement {
        PREFLIGHT_REVALIDATION_REQUIRED
    }

    private final PackageIdentity packageIdentity;
    private final List<PlannedFile> files;
    private final Requirement requirement;

    FrameworkInstallPlan(PackageIdentity packageIdentity, List<PlannedFile> files,
                         Requirement requirement) {
        this.packageIdentity = Objects.requireNonNull(packageIdentity, "packageIdentity");
        this.files = List.copyOf(files);
        if (this.files.isEmpty()) throw new IllegalArgumentException("files must not be empty");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
    }

    public PackageIdentity packageIdentity() { return packageIdentity; }
    public List<PlannedFile> files() { return files; }
    public Requirement requirement() { return requirement; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FrameworkInstallPlan that)) return false;
        return packageIdentity.equals(that.packageIdentity) && files.equals(that.files)
            && requirement == that.requirement;
    }

    @Override public int hashCode() {
        return Objects.hash(packageIdentity, files, requirement);
    }

    @Override public String toString() {
        return "FrameworkInstallPlan[packageIdentity=" + packageIdentity + ", files=" + files
            + ", requirement=" + requirement + "]";
    }
}
