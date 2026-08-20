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

    /** @return identity of the inspected framework package, including its raw archive digest and size */
    public PackageIdentity packageIdentity() { return packageIdentity; }

    /**
     * @return the files the package would install, in inspection order; never empty and
     *         unmodifiable (copied at construction, so the plan cannot be mutated afterwards)
     */
    public List<PlannedFile> files() { return files; }

    /**
     * @return the obligation an installer must honour before acting on this plan; always
     *         {@link Requirement#PREFLIGHT_REVALIDATION_REQUIRED}, meaning the bytes recorded here
     *         are an observation and must be re-hashed at install time
     */
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
