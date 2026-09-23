package dev.turboism.core.archive;

import java.util.List;

/**
 * Caller-injected path policy seam for the shared strict parser. The parser enforces
 * archive-internal structure itself (EOCD/CEN/LOC consistency, identity-key uniqueness,
 * size and ratio bounds); the content-owning caller decides which entry names are
 * admissible and which cross-entry name relationships are forbidden.
 */
public interface ArchivePathPolicy {

    /**
     * Validates one central-directory entry name.
     *
     * @param name the entry name as recorded in the central directory
     * @param directory whether the entry name ends with {@code '/'}
     * @throws ArchiveStructureException when the name violates the caller's policy
     */
    void validateEntry(String name, boolean directory) throws ArchiveStructureException;

    /**
     * Validates the complete entry-name set for caller-level collision rules.
     *
     * @param names every central-directory entry name in archive order
     * @throws ArchiveStructureException when the name set violates the caller's policy
     */
    void validateCollisions(List<String> names) throws ArchiveStructureException;

    /**
     * Whether directory entries written with default type metadata — platform 0
     * and no DOS directory bit, as standard JDK {@code jar}/
     * {@link java.util.jar.JarOutputStream} writers emit — are admissible.
     * Callers opting in still require every directory entry to be a safe empty
     * directory: zero expanded payload (an empty deflate stream compresses to a
     * nonzero byte count), a normalized relative path, and no conflicting
     * explicit type. An empty directory is structure, not a data channel. The
     * default keeps the historical strict rule.
     */
    default boolean permitsDefaultDirectoryMetadata() {
        return false;
    }
}
