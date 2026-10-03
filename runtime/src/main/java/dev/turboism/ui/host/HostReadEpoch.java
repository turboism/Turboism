package dev.turboism.ui.host;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One synchronous unit of host-thread work — the coherence window for host reads.
 *
 * <p>All host document state is read and written on the same host thread (the Swing EDT), so while
 * one dispatched work item runs, no other work item can change that state. {@link #enter()} marks
 * the start of such a unit and {@link #exit()} ends it. Nested work — a synchronous dispatch back
 * onto the same thread, or a queued body dispatched during a nested event pump — gets its own
 * frame: the child sees a fresh epoch, and when it exits the enclosing frame's write count is
 * bumped so a memoized outer read can never cross a unit that may have mutated the host.
 *
 * <p>Readers of this type see a pair {@code (epoch, writes)}: a captured host observation is
 * reusable exactly while both values are unchanged on the same thread. Outside any epoch —
 * {@link #current()} is {@code 0} — callers must always read fresh.
 *
 * <p>The epoch bounds coherence to task structure only; it cannot see raw host-side UI events that
 * never pass through a dispatched body (for example a native modal event pump), and callers must
 * keep treating every returned value as a snapshot of when it was observed.
 */
public final class HostReadEpoch {

    private HostReadEpoch() {}

    /** One nested frame of host work; {@code parent} is restored on exit. */
    private static final class Frame {
        private final long id;
        private final Frame parent;
        private long writes;

        private Frame(final long id, final Frame parent) {
            this.id = id;
            this.parent = parent;
        }
    }

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    /**
     * Opens a host-read epoch on the calling thread. Every {@link #enter()} must be paired with
     * {@link #exit()} on the same thread; closing a nested frame marks the enclosing frame as
     * potentially mutated.
     */
    public static void enter() {
        CURRENT.set(new Frame(NEXT_ID.getAndIncrement(), CURRENT.get()));
    }

    /**
     * Closes the calling thread's current epoch. Any host mutation the closed frame may have
     * performed invalidates the enclosing frame's memoized reads.
     */
    public static void exit() {
        final Frame frame = CURRENT.get();
        if (frame == null) {
            return;
        }
        CURRENT.set(frame.parent);
        if (frame.parent != null) {
            frame.parent.writes++;
        }
    }

    /**
     * Marks the calling thread's current epoch as potentially mutated. Dispatched bodies that may
     * write host state call this before running so memoized reads re-observe afterwards.
     */
    public static void noteHostWrite() {
        final Frame frame = CURRENT.get();
        if (frame != null) {
            frame.writes++;
        }
    }

    /**
     * @return the calling thread's current epoch id, or {@code 0} when no epoch is open; the value
     *     is unique per frame and never repeats on this thread
     */
    public static long current() {
        final Frame frame = CURRENT.get();
        return frame == null ? 0L : frame.id;
    }

    /**
     * @return how many nested writes or completed child frames the calling thread's current epoch
     *     has absorbed; {@code 0} when no epoch is open
     */
    public static long writes() {
        final Frame frame = CURRENT.get();
        return frame == null ? 0L : frame.writes;
    }
}
