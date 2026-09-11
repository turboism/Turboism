package dev.turboism.adapter.cubism.editor.history;

import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Fail-closed static entrypoint that the exact {@code beginEdit} transformer calls.
 *
 * <p>The injected bytecode runs at the entry of a native editing action, before the host mutates
 * the model, on the host's event thread. Two rules follow from that position and neither may be
 * relaxed:</p>
 *
 * <ul>
 *   <li><b>Nothing is read and nothing is published here.</b> The callback only records that an
 *       edit started and asks for a drain, so a Turboism bug cannot disturb the host's own edit
 *       setup. All publication happens later, off the host stack.</li>
 *   <li><b>It never throws.</b> A throw would propagate into the host's edit entry, so every
 *       failure is counted instead. Only a {@link VirtualMachineError} is allowed through.</li>
 * </ul>
 *
 * <p>One native edit can enter the instrumented code twice. {@code CModelingEditMode_Main}
 * overrides {@code beginEdit} and, on the branch where no form animation is active, calls its base
 * implementation {@code ACEditMode.beginEdit}, which is instrumented too; while form animation is
 * active it returns through {@code CModelEditAnimationHandler} and never reaches the base. The
 * per-thread frame therefore publishes only an <em>outermost</em> entry, so a plain modeling edit
 * reports one start rather than two, and both branches report exactly one. The frame is released
 * by the drain that the outermost entry itself scheduled, which also covers an edit the host
 * abandons before it ever reaches undo.</p>
 */
public final class NativeEditBeginBridge {

    /** Receives one observed native edit start, off the host stack. */
    @FunctionalInterface
    public interface BeforeSink {
        void nativeEditStarted(Optional<String> label);
    }

    /** Longest label forwarded to the sink; longer native names are truncated, never rejected. */
    static final int MAX_LABEL_LENGTH = 256;

    /** Bound on queued starts. Reaching it drops the newest start and counts the drop. */
    static final int MAX_PENDING = 64;

    private static final int MAX_DRAIN = 64;

    private static final Consumer<String> INGRESS = NativeEditBeginBridge::accept;

    /** Per-thread frame marking that an edit is already being observed on this thread. */
    private static final ThreadLocal<Boolean> INSIDE_EDIT = new ThreadLocal<>();

    private static final Queue<Optional<String>> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<Binding> BINDING = new AtomicReference<>();
    private static final AtomicLong NOTIFICATIONS = new AtomicLong();
    private static final AtomicLong DRAINED = new AtomicLong();
    private static final AtomicLong NESTED = new AtomicLong();
    private static final AtomicLong UNBOUND = new AtomicLong();
    private static final AtomicLong OVERFLOWED = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();

    private static volatile boolean closed;

    private NativeEditBeginBridge() {
    }

    private record Binding(BeforeSink sink, Runnable drainRequest) {
    }

    /** Binds one session's observer; a later bind replaces it. */
    public static void bind(final BeforeSink sink, final Runnable drainRequest) {
        BINDING.set(new Binding(
            Objects.requireNonNull(sink, "sink"),
            Objects.requireNonNull(drainRequest, "drainRequest")
        ));
        INSIDE_EDIT.remove();
    }

    /** Releases the observer without closing the bridge; a later bind re-arms it. */
    public static void unbind() {
        BINDING.set(null);
        PENDING.clear();
        INSIDE_EDIT.remove();
    }

    /**
     * Records one observed native edit start.
     *
     * @param editName the localizable native edit name, possibly null or empty. It is presentation
     *                 only: it never becomes an identity and is never used to classify anything
     */
    /** {@return the loader-neutral receiver the injected host code calls} */
    public static Consumer<String> ingress() {
        return INGRESS;
    }

    static void accept(final String editName) {
        NOTIFICATIONS.incrementAndGet();
        try {
            if (closed) {
                // Terminal: no counter, because a closed bridge is a shutdown state and not a
                // missed observation.
                return;
            }
            final Binding binding = BINDING.get();
            if (binding == null) {
                // Observed, but no session is attached. Counted so a silent drop is diagnosable.
                UNBOUND.incrementAndGet();
                return;
            }
            if (Boolean.TRUE.equals(INSIDE_EDIT.get())) {
                NESTED.incrementAndGet();
                return;
            }
            INSIDE_EDIT.set(Boolean.TRUE);
            if (PENDING.size() >= MAX_PENDING) {
                // Nothing was queued, so no drain will run to release the frame. Release it here,
                // otherwise the next edit on this thread would look nested and be suppressed.
                INSIDE_EDIT.remove();
                OVERFLOWED.incrementAndGet();
                return;
            }
            PENDING.add(label(editName));
            try {
                binding.drainRequest().run();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                // A drain that never runs would leave the frame set for the rest of this thread's
                // life, so the start is dropped together with the frame it opened.
                INSIDE_EDIT.remove();
                FAILURES.incrementAndGet();
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            FAILURES.incrementAndGet();
        }
    }

    /**
     * Hands every queued start to the sink in arrival order and releases this thread's frame.
     *
     * @param sink receives the queued labels in order
     * @return the number of starts handed over
     */
    public static int drain(final BeforeSink sink) {
        Objects.requireNonNull(sink, "sink");
        int count = 0;
        try {
            while (count < MAX_DRAIN) {
                final Optional<String> pending = PENDING.poll();
                if (pending == null) {
                    break;
                }
                count++;
                DRAINED.incrementAndGet();
                try {
                    sink.nativeEditStarted(pending);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    FAILURES.incrementAndGet();
                }
            }
        } finally {
            INSIDE_EDIT.remove();
        }
        return count;
    }

    /** Terminal: a later {@link #accept} is dropped, and no further start is observed. */
    public static void close() {
        closed = true;
        unbind();
    }

    /** {@return whether a start is currently queued} */
    static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    static long notificationCount() {
        return NOTIFICATIONS.get();
    }

    static long drainedCount() {
        return DRAINED.get();
    }

    static long nestedEntryCount() {
        return NESTED.get();
    }

    static long unboundCount() {
        return UNBOUND.get();
    }

    static long overflowCount() {
        return OVERFLOWED.get();
    }

    static long failureCount() {
        return FAILURES.get();
    }

    /** Restores the bridge to its unbound, empty, zeroed state. Intended for tests only. */
    static void reset() {
        BINDING.set(null);
        PENDING.clear();
        INSIDE_EDIT.remove();
        NOTIFICATIONS.set(0L);
        DRAINED.set(0L);
        NESTED.set(0L);
        UNBOUND.set(0L);
        OVERFLOWED.set(0L);
        FAILURES.set(0L);
        closed = false;
    }

    private static Optional<String> label(final String editName) {
        if (editName == null) {
            return Optional.empty();
        }
        final String stripped = editName.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            stripped.length() <= MAX_LABEL_LENGTH
                ? stripped
                : stripped.substring(0, MAX_LABEL_LENGTH)
        );
    }
}
