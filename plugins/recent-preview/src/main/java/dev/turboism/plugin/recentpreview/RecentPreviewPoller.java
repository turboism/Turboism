package dev.turboism.plugin.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Poll-track capture timing (robustness fallback for the exact-timing hook track).
 *
 * <p>Every tick samples the host recent-file projection and emits the current
 * document's id when (i) the current document changed — after-open semantics — or
 * (ii) the current document's file last-modified time changed — save semantics.
 * The host projection merges the current project ahead of the menu entries, so the
 * first entry is the current document. Emitted ids are deduplicated by the
 * controller against the hook track (id + lastModified), and repeated emissions of
 * the same document are spaced by {@link #MIN_CAPTURE_INTERVAL}. Entries without a
 * last-modified time are ignored (nothing to key the dedupe on).</p>
 */
final class RecentPreviewPoller {

    /** Minimal spacing between two poll-track captures of the same document. */
    static final Duration MIN_CAPTURE_INTERVAL = Duration.ofMillis(2500);

    /** Injectable clock so the sample logic is testable without real time. */
    interface Clock {
        long nowMillis();
    }

    private static final long UNKNOWN_MODIFIED = -1L;

    private final Clock clock;
    private final java.util.Map<RecentFileId, Long> lastEmittedAt = new java.util.HashMap<>();
    private RecentFileId currentId;
    private long currentModified = UNKNOWN_MODIFIED;

    RecentPreviewPoller(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Poll one recent-file snapshot. Emits the current document's id when the
     * current document changed (after-open) or its file was rewritten (save); the
     * state only advances once an emission is accepted, so a suppressed tick is
     * retried on the next one. Empty list clears the tracked state.
     */
    Optional<RecentFileId> sample(final List<RecentFileSummary> files) {
        if (files == null || files.isEmpty()) {
            currentId = null;
            currentModified = UNKNOWN_MODIFIED;
            lastEmittedAt.clear();
            return Optional.empty();
        }
        final RecentFileSummary current = files.get(0);
        final Optional<Instant> modified = current.lastModified();
        final long modifiedMillis = modified.map(Instant::toEpochMilli).orElse(UNKNOWN_MODIFIED);
        final boolean documentChanged = currentId == null || !currentId.equals(current.id());
        final boolean saved = !documentChanged
            && modifiedMillis != UNKNOWN_MODIFIED
            && modifiedMillis != currentModified;
        if (!documentChanged && !saved) {
            return Optional.empty();
        }
        if (modifiedMillis == UNKNOWN_MODIFIED) {
            // Nothing to key the dedupe on; adopt the id without emitting.
            currentId = current.id();
            currentModified = modifiedMillis;
            return Optional.empty();
        }
        final long now = clock.nowMillis();
        final long last = lastEmittedAt.getOrDefault(current.id(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE
            && now - last < MIN_CAPTURE_INTERVAL.toMillis()) {
            return Optional.empty();
        }
        currentId = current.id();
        currentModified = modifiedMillis;
        lastEmittedAt.put(current.id(), now);
        return Optional.of(current.id());
    }
}
