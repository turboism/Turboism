package dev.turboism.distribution;

import java.util.Objects;

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

    public String sha256() { return sha256; }
    public long size() { return size; }
    public String id() { return id; }
    public String version() { return version; }
    public String apiVersion() { return apiVersion; }
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
