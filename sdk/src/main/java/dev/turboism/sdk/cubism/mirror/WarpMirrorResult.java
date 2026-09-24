package dev.turboism.sdk.cubism.mirror;

import java.util.List;
import java.util.Objects;

/**
 * Result of one {@link WarpMirrorService#apply(WarpMirrorRequest)} call.
 *
 * @param outcome terminal outcome
 * @param changedPointCount number of grid control points rewritten, {@code 0} when not applied
 * @param compensatedDescendantCount number of descendants whose forms were compensated,
 *        {@code 0} when preservation was not requested or not applied
 * @param blockers typed rejection details; empty for {@link WarpMirrorOutcome#APPLIED}
 *        and {@link WarpMirrorOutcome#NO_CHANGE}
 */
public record WarpMirrorResult(
    WarpMirrorOutcome outcome,
    int changedPointCount,
    int compensatedDescendantCount,
    List<WarpMirrorBlocker> blockers
) {
    public WarpMirrorResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
    }

    /** Builds an applied result. */
    public static WarpMirrorResult applied(final int changedPoints, final int compensated) {
        return new WarpMirrorResult(WarpMirrorOutcome.APPLIED, changedPoints, compensated, List.of());
    }

    /** Builds a no-change result. */
    public static WarpMirrorResult noChange() {
        return new WarpMirrorResult(WarpMirrorOutcome.NO_CHANGE, 0, 0, List.of());
    }

    /** Builds a blocked result with the given typed blockers. */
    public static WarpMirrorResult blocked(final List<WarpMirrorBlocker> blockers) {
        if (blockers.isEmpty()) {
            throw new IllegalArgumentException("blocked results require at least one blocker");
        }
        return new WarpMirrorResult(WarpMirrorOutcome.BLOCKED, 0, 0, blockers);
    }

    /** Builds a recovery-failed result. */
    public static WarpMirrorResult recoveryFailed(final String reason) {
        return new WarpMirrorResult(
            WarpMirrorOutcome.RECOVERY_FAILED, 0, 0,
            List.of(new WarpMirrorBlocker(WarpMirrorBlockerCode.WRITE_FAILED, reason)));
    }
}
