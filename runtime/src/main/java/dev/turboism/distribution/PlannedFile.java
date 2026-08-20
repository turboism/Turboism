package dev.turboism.distribution;

import java.util.Objects;

/**
 * One file an install plan would extract, with the digest and length observed inside the archive.
 *
 * <p>Immutable. Construction rejects blank fields, a {@code sha256} that is not 64 lowercase hex
 * characters, and a negative size. The recorded digest is what a preflight check re-verifies before
 * the file is published - see {@link PluginJarPreflight}.
 */
public final class PlannedFile {
    private final String role;
    private final String archivePath;
    private final String installPath;
    private final String sha256;
    private final long size;

    PlannedFile(String role, String archivePath, String installPath, String sha256, long size) {
        this.role = require(role, "role");
        this.archivePath = require(archivePath, "archivePath");
        this.installPath = require(installPath, "installPath");
        this.sha256 = require(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid sha256");
        if (size < 0) throw new IllegalArgumentException("size must be non-negative");
        this.size = size;
    }

    /** @return the part this file plays in the package, for example {@code PLUGIN_JAR}; never blank */
    public String role() { return role; }

    /** @return the entry name this file occupies inside the archive; never blank */
    public String archivePath() { return archivePath; }

    /** @return the relative destination the file would be written to on install; never blank */
    public String installPath() { return installPath; }

    /** @return SHA-256 of the file's uncompressed contents, 64 lowercase hex characters */
    public String sha256() { return sha256; }

    /** @return uncompressed byte length of the file; never negative */
    public long size() { return size; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PlannedFile that)) return false;
        return size == that.size && role.equals(that.role) && archivePath.equals(that.archivePath)
            && installPath.equals(that.installPath) && sha256.equals(that.sha256);
    }

    @Override public int hashCode() {
        return Objects.hash(role, archivePath, installPath, sha256, size);
    }

    @Override public String toString() {
        return "PlannedFile[role=" + role + ", archivePath=" + archivePath
            + ", installPath=" + installPath + ", sha256=" + sha256 + ", size=" + size + "]";
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
