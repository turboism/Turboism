package dev.turboism.core.runtime.psd;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Content-based save admission, independent of filesystem event delivery.
 *
 * <p>The caller must supply digests of consistent, bounded whole-file snapshots, on a
 * serialized worker lane. This class neither reads files nor proves that a PSD parses.
 * A notification is not a successful import: the session coordinator must retain the
 * latest pending revision and track native completion separately. No layer/pixel diff
 * or Undo-triggered replay is performed here.
 */
final class PsdSaveDebouncer {
    static final Duration DEFAULT_QUIET_WINDOW = Duration.ofMillis(750);

    private final long quietNanos;
    private String lastNotifiedDigest;
    private String candidateDigest;
    private long candidateSince;
    private String offeredDigest;
    private boolean stopped;

    PsdSaveDebouncer(final String initialDigest) {
        this(initialDigest, DEFAULT_QUIET_WINDOW);
    }

    PsdSaveDebouncer(final String initialDigest, final Duration quietWindow) {
        lastNotifiedDigest = requireDigest(initialDigest);
        quietNanos = Objects.requireNonNull(quietWindow, "quietWindow").toNanos();
        if (quietNanos <= 0) {
            throw new IllegalArgumentException("quietWindow must be positive");
        }
    }

    /**
     * Observes one complete snapshot using a monotonic clock (such as nanoTime) and offers the
     * content once its version has been stable for the whole quiet window. At least two
     * observations separated by the quiet window are required.
     *
     * <p>Offering is two-phase on purpose: an offered digest is not treated as notified until the
     * caller confirms a successful publication with {@link #confirm(String)}. That way a publication
     * that fails for a transient reason can still be retried, while a later delivery that genuinely
     * fails keeps this offer out of circulation and can be abandoned with
     * {@link #invalidateObservation()}. Re-observing an already confirmed digest never replays it
     * merely because time passed.</p>
     */
    Optional<String> observe(final String digest, final long nowNanos) {
        final String validated = requireDigest(digest);
        if (stopped) {
            return Optional.empty();
        }
        if (lastNotifiedDigest.equals(validated)) {
            candidateDigest = null;
            offeredDigest = null;
            return Optional.empty();
        }
        if (validated.equals(offeredDigest)) {
            // Already offered and awaiting confirmation; do not offer the same content twice.
            return Optional.empty();
        }
        if (!validated.equals(candidateDigest)) {
            candidateDigest = validated;
            candidateSince = nowNanos;
            return Optional.empty();
        }
        if (nowNanos - candidateSince < quietNanos) {
            return Optional.empty();
        }
        offeredDigest = validated;
        candidateDigest = null;
        return Optional.of(validated);
    }

    /** Records that the offered digest was published; only a confirmed digest stops being offered. */
    void confirm(final String digest) {
        final String validated = requireDigest(digest);
        if (validated.equals(offeredDigest)) {
            lastNotifiedDigest = validated;
            offeredDigest = null;
        }
    }

    /** A missing, unreadable, changing, oversized or unpublishable version breaks the window. */
    void invalidateObservation() {
        candidateDigest = null;
        offeredDigest = null;
    }

    /** Stops admission only; does not delete files or terminate external applications. */
    void stop() {
        stopped = true;
        candidateDigest = null;
        offeredDigest = null;
    }

    private static String requireDigest(final String digest) {
        Objects.requireNonNull(digest, "digest");
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be a lowercase SHA-256 digest");
        }
        return digest;
    }
}
