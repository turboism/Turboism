package dev.turboism.distribution;

import java.util.Objects;

/**
 * Immutable identity of an inspected distribution package: what the archive claims to be, plus the
 * raw bytes actually observed while reading it.
 *
 * <p>Construction rejects blank text fields, a {@code sha256} that is not 64 lowercase hex
 * characters, a negative size, and a non-positive Java version. Instances are created only by the
 * inspectors in this package.
 */
public final class PackageIdentity {
    private final String sha256;
    private final long size;
    private final String id;
    private final String version;
    private final String apiVersion;
    private final int javaVersion;

    PackageIdentity(String sha256, long size, String id, String version,
                    String apiVersion, int javaVersion) {
        this.sha256 = require(sha256, "sha256");
        this.id = require(id, "id");
        this.version = require(version, "version");
        this.apiVersion = require(apiVersion, "apiVersion");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid sha256");
        if (size < 0) throw new IllegalArgumentException("size must be non-negative");
        if (javaVersion <= 0) throw new IllegalArgumentException("javaVersion must be positive");
        this.size = size;
        this.javaVersion = javaVersion;
    }

    /** @return SHA-256 of the raw archive bytes as observed during inspection, 64 lowercase hex characters */
    public String sha256() { return sha256; }

    /** @return byte length of the raw archive as read during inspection; never negative */
    public long size() { return size; }

    /** @return the package's declared identifier, never blank */
    public String id() { return id; }

    /** @return the package's declared release version, never blank */
    public String version() { return version; }

    /** @return the Turboism API version the package declares it targets, never blank */
    public String apiVersion() { return apiVersion; }

    /** @return the minimum Java runtime version the package declares; always positive */
    public int javaVersion() { return javaVersion; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PackageIdentity that)) return false;
        return size == that.size && javaVersion == that.javaVersion && sha256.equals(that.sha256)
            && id.equals(that.id) && version.equals(that.version) && apiVersion.equals(that.apiVersion);
    }

    @Override public int hashCode() {
        return Objects.hash(sha256, size, id, version, apiVersion, javaVersion);
    }

    @Override public String toString() {
        return "PackageIdentity[sha256=" + sha256 + ", size=" + size + ", id=" + id
            + ", version=" + version + ", apiVersion=" + apiVersion
            + ", javaVersion=" + javaVersion + "]";
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
