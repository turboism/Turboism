package dev.turboism.validation.atlasimage.t039;

import dev.turboism.validation.atlasimage.t038.T038ArrayHelper;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validation-only shadow helper. It records bounded scalar facts and always
 * returns the original full bounds; it never reads or writes a pixel.
 *
 * <p>The sample and all counters are one immutable state. A snapshot therefore
 * cannot combine fields from two concurrent calls.</p>
 */
public final class T039ShadowHelper {
    public static final String INTERNAL_NAME =
        "dev/turboism/validation/atlasimage/t039/T039ShadowHelper";
    public static final String BINARY_NAME = INTERNAL_NAME.replace('/', '.');
    public static final String BOUNDS_DESCRIPTOR = "(II[III[IIIIIZ)J";
    private static final long MAX_EVENT_LIMIT = 128L;
    private static final AtomicReference<State> STATE =
        new AtomicReference<>(State.initial(64L));
    private static final Object DIAGNOSTIC_LOCK = new Object();
    private static volatile boolean FROZEN;
    private static volatile Stats FROZEN_STATS;

    private T039ShadowHelper() {
    }

    /** Prewarms the helper and resets the bounded scalar counters. */
    public static void prewarm(final int maximumEvents) {
        if (maximumEvents <= 0 || maximumEvents > MAX_EVENT_LIMIT) {
            throw new IllegalArgumentException("T039 event limit outside [1,128]");
        }
        synchronized (DIAGNOSTIC_LOCK) {
            ensureMutable();
            STATE.set(State.initial(maximumEvents));
        }
    }

    /** Test-only reset; it retains the already validated event limit. */
    public static void resetCounters() {
        synchronized (DIAGNOSTIC_LOCK) {
            ensureMutable();
            STATE.updateAndGet(previous -> State.initial(previous.eventLimit()));
        }
    }

    /** Freezes the scalar sample and counters for the one-shot T040 bridge. */
    static Stats freezeSnapshot() {
        synchronized (DIAGNOSTIC_LOCK) {
            if (FROZEN) {
                throw new IllegalStateException("T039 helper already frozen");
            }
            final Stats frozen = STATE.get().toStats();
            FROZEN_STATS = frozen;
            FROZEN = true;
            return frozen;
        }
    }

    /**
     * Matches the two-boundary helper descriptor. Shadow mode deliberately
     * returns fullWidth/fullHeight even when a trim would be admissible.
     */
    public static long bounds(
        final int kx,
        final int ky,
        final int[] source,
        final int sourceWidth,
        final int sourceHeight,
        final int[] destination,
        final int stride,
        final int fullWidth,
        final int fullHeight,
        final int padding,
        final boolean optimize
    ) {
        if (FROZEN) {
            return pack(fullWidth, fullHeight);
        }
        final int sourceLength = source == null ? -1 : source.length;
        final int destinationLength = destination == null ? -1 : destination.length;
        final boolean aliased = source != null && source == destination;
        final boolean admitted = source != null && destination != null
            && T038ArrayHelper.isAdmitted(
                kx, ky, source, sourceWidth, sourceHeight, destination,
                stride, fullWidth, fullHeight, padding);
        final long candidate = admitted
            ? T038ArrayHelper.bounds(
                kx, ky, source, sourceWidth, sourceHeight, destination,
                stride, fullWidth, fullHeight, padding, true)
            : pack(fullWidth, fullHeight);
        final boolean potentialTrim = admitted
            && (T038ArrayHelper.unpackWidth(candidate) != fullWidth
                || T038ArrayHelper.unpackHeight(candidate) != fullHeight);
        synchronized (DIAGNOSTIC_LOCK) {
            if (!FROZEN) {
                record(new Sample(
                    kx, ky, sourceWidth, sourceHeight, stride, fullWidth, fullHeight,
                    padding, sourceLength, destinationLength, aliased, admitted,
                    potentialTrim, optimize), admitted, aliased, potentialTrim);
            }
        }
        return pack(fullWidth, fullHeight);
    }

    public static Stats snapshot() {
        final Stats frozen = FROZEN_STATS;
        return frozen == null ? STATE.get().toStats() : frozen;
    }

    private static void ensureMutable() {
        if (FROZEN) {
            throw new IllegalStateException("T039 helper is frozen");
        }
    }

    private static void record(
        final Sample sample,
        final boolean admitted,
        final boolean aliased,
        final boolean potentialTrim
    ) {
        for (;;) {
            final State current = STATE.get();
            final State next;
            if (current.recorded() >= current.eventLimit()) {
                next = current.withDropped();
            } else {
                next = current.withRecorded(sample, admitted, aliased, potentialTrim);
            }
            if (STATE.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private static long pack(final int width, final int height) {
        return ((long) height << 32) | (width & 0xffffffffL);
    }

    private static long increment(final long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    private record State(
        long recorded,
        long dropped,
        long admitted,
        long rejected,
        long aliased,
        long potentialTrim,
        long eventLimit,
        Sample sample
    ) {
        private static State initial(final long limit) {
            return new State(0L, 0L, 0L, 0L, 0L, 0L, limit, Sample.EMPTY);
        }

        private State withDropped() {
            return new State(
                recorded, increment(dropped), admitted, rejected, aliased,
                potentialTrim, eventLimit, sample);
        }

        private State withRecorded(
            final Sample nextSample,
            final boolean nextAdmitted,
            final boolean nextAliased,
            final boolean nextPotentialTrim
        ) {
            return new State(
                increment(recorded), dropped,
                nextAdmitted ? increment(admitted) : admitted,
                nextAdmitted ? rejected : increment(rejected),
                nextAliased ? increment(aliased) : aliased,
                nextPotentialTrim ? increment(potentialTrim) : potentialTrim,
                eventLimit, nextSample);
        }

        private Stats toStats() {
            return new Stats(
                recorded, dropped, admitted, rejected, aliased, potentialTrim,
                eventLimit, sample.kx(), sample.ky(), sample.sourceWidth(),
                sample.sourceHeight(), sample.stride(), sample.fullWidth(),
                sample.fullHeight(), sample.padding(), sample.sourceLength(),
                sample.destinationLength(), sample.aliased(), sample.admitted(),
                sample.potentialTrim(), sample.optimizationRequested());
        }
    }

    private record Sample(
        int kx,
        int ky,
        int sourceWidth,
        int sourceHeight,
        int stride,
        int fullWidth,
        int fullHeight,
        int padding,
        int sourceLength,
        int destinationLength,
        boolean aliased,
        boolean admitted,
        boolean potentialTrim,
        boolean optimizationRequested
    ) {
        private static final Sample EMPTY = new Sample(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            false, false, false, false);
    }

    public record Stats(
        long recorded,
        long dropped,
        long admitted,
        long rejected,
        long aliased,
        long potentialTrim,
        long eventLimit,
        int lastKx,
        int lastKy,
        int lastSourceWidth,
        int lastSourceHeight,
        int lastStride,
        int lastFullWidth,
        int lastFullHeight,
        int lastPadding,
        int lastSourceLength,
        int lastDestinationLength,
        boolean lastAliased,
        boolean lastAdmitted,
        boolean lastPotentialTrim,
        boolean lastOptimizationRequested
    ) {
    }
}
