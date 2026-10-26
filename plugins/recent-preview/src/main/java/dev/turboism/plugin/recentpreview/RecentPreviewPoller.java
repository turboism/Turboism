package dev.turboism.plugin.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Poll-track capture timing (robustness fallback for the exact-timing hook track).
 *
 * <p>Every tick samples the host recent-file projection. The host projection merges
 * the current project ahead of the menu entries, so the first entry is the current
 * document. Emitted ids are deduplicated by the controller against successfully
 * cached captures (id + lastModified), leaving failed captures retryable. Repeated emissions of
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

    private final Clock clock;
    private final java.util.Map<RecentFileId, Long> lastEmittedAt = new java.util.HashMap<>();

    RecentPreviewPoller(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Poll one recent-file snapshot. Rechecks the current document at the minimum
     * interval so failed captures can recover without another open or save.
     * Empty lists clear the tracked state.
     */
    Optional<RecentFileId> sample(final List<RecentFileSummary> files) {
        if (files == null || files.isEmpty()) {
            lastEmittedAt.clear();
            return Optional.empty();
        }
        final RecentFileSummary current = files.get(0);
        if (!lastEmittedAt.isEmpty()) {
            final java.util.Set<RecentFileId> live = new java.util.HashSet<>();
            for (RecentFileSummary file : files) live.add(file.id());
            lastEmittedAt.keySet().retainAll(live);
        }
        if (current.lastModified().isEmpty()) {
            return Optional.empty();
        }
        final long now = clock.nowMillis();
        final long last = lastEmittedAt.getOrDefault(current.id(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && now - last < MIN_CAPTURE_INTERVAL.toMillis()) {
            return Optional.empty();
        }
        lastEmittedAt.put(current.id(), now);
        return Optional.of(current.id());
    }
}
