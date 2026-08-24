package dev.turboism.sdk.cubism.recentfile;


import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable summary of one recently opened Cubism project file.
 *
 * <p><strong>Deliberate deviation from the snapshot-path convention:</strong> unlike
 * {@link dev.turboism.sdk.cubism.DocumentSnapshot}, {@code AnimationSnapshot}, and
 * {@code ProjectContentSnapshot} — which never carry absolute paths — this record
 * intentionally exposes {@link #path()} as the full normalized absolute path of the
 * project file. The recent-files projection is a host-menu read surface (permission
 * {@code turboism.cubism.recent-file.read}) whose consumers need the path to key
 * plugin-local caches and to derive stable file identities; hiding it would force
 * plugins to re-derive paths from opaque ids, which the menu itself does not provide.
 * The id remains the opaque cross-session key ({@link RecentFileId}); the path is a
 * supplementary display/cache key only.</p>
 *
 * <p>{@link #lastModified()} and {@link #path()} are {@link Optional} because a menu
 * entry may reference a file that no longer exists on disk; in that case both are
 * {@link Optional#empty()} and the summary carries no fabricated data.</p>
 */
public record RecentFileSummary(
    RecentFileId id,
    String displayName,
    Optional<Instant> lastModified,
    Optional<String> path
) {
    public RecentFileSummary {
        id = Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (displayName.length() > 512) {
            throw new IllegalArgumentException("displayName must not exceed 512 characters");
        }
        lastModified = Objects.requireNonNull(lastModified, "lastModified");
        path = Objects.requireNonNull(path, "path");
    }

    /** Convenience constructor for entries whose file no longer exists on disk. */
    public RecentFileSummary(final RecentFileId id, final String displayName) {
        this(id, displayName, Optional.empty(), Optional.empty());
    }
}
