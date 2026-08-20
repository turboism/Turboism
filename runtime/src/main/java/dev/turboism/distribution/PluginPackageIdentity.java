package dev.turboism.distribution;

import java.util.Objects;

/** Plugin package identity and its distinct raw transport observation. */
public final class PluginPackageIdentity {
    private final String packageHash;
    private final String packageId;
    private final String version;
    private final String rawArchiveSha256;
    private final long rawArchiveSize;

    PluginPackageIdentity(String packageHash, String packageId, String version,
                          String rawArchiveSha256, long rawArchiveSize) {
        this.packageHash = hash(packageHash, "packageHash");
        this.packageId = text(packageId, "packageId");
        this.version = text(version, "version");
        this.rawArchiveSha256 = hash(rawArchiveSha256, "rawArchiveSha256");
        if (rawArchiveSize < 0) throw new IllegalArgumentException("rawArchiveSize must be non-negative");
        this.rawArchiveSize = rawArchiveSize;
    }

    /**
     * @return the package hash the archive's own manifest declares, 64 lowercase hex characters.
     *         This is the package's claim about itself and is deliberately kept distinct from
     *         {@link #rawArchiveSha256()}, which is what the inspector actually read.
     */
    public String packageHash() { return packageHash; }

    /** @return the package identifier declared in the manifest, cross-checked against the plugin descriptor id */
    public String packageId() { return packageId; }

    /** @return the version declared in the manifest, cross-checked against the plugin descriptor version */
    public String version() { return version; }

    /** @return SHA-256 of the raw archive bytes as observed by the inspector, 64 lowercase hex characters */
    public String rawArchiveSha256() { return rawArchiveSha256; }

    /** @return byte length of the raw archive as observed by the inspector; never negative */
    public long rawArchiveSize() { return rawArchiveSize; }

    private static String hash(String value, String name) {
        value = text(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
