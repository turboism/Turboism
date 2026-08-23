package dev.turboism.mapping.draft;

/** Conservative limits for an untrusted, local input JAR. */
public record JarScanPolicy(
    long maxArtifactBytes,
    int maxEntries,
    int maxClassEntries,
    long maxEntryBytes,
    long maxExpandedBytes
) {
    public JarScanPolicy {
        if (maxArtifactBytes <= 0 || maxEntries <= 0 || maxClassEntries <= 0
            || maxEntryBytes <= 0 || maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("scan limits must be positive");
        }
    }

    /**
     * @return the standard limits the CLI runs with: 512 MiB artifact, 100000 entries of which at
     *     most 80000 are classes, 32 MiB per entry, and 2 GiB expanded — sized to admit a real
     *     Editor artifact while still bounding a decompression bomb
     */
    public static JarScanPolicy defaults() {
        return new JarScanPolicy(512L * 1024 * 1024, 100_000, 80_000, 32L * 1024 * 1024, 2L * 1024 * 1024 * 1024);
    }
}
