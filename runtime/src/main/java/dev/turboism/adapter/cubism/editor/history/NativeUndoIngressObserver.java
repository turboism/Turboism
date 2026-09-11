package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Observes native Editor edits through the admitted native undo state-change listener.
 *
 * <p>A native Cubism UI edit never traverses the Turboism facade, so no semantic lifecycle can
 * be produced from the adapter's own invocation path. The host undo manager, however, exposes a
 * public state-change listener that fires inside {@code addEdit} and inside
 * {@code undo}/{@code redo}. Registering that listener is the only hook-free way to learn that a
 * native edit happened.</p>
 *
 * <p>The listener is a <em>trigger</em>, never commit proof. The callback runs inline on the
 * thread that committed the edit, inside the host's own synchronized admission path, and the
 * host iterates its listener list without exception isolation. The callback therefore only
 * records that state changed and returns. All host reads happen later in {@link #drain()}, which
 * is the single claimed consumer. Nothing captured here may be published as a verified fact
 * before it passes the existing registry and entry-identity verification.</p>
 *
 * <p>Classification reads only the already-admitted history snapshot selectors:
 * {@code getUndoList()} size, {@code getCurrentPos()} and, when needed, the entry at the
 * current position.</p>
 *
 * <ul>
 *   <li>size changed — one entry was admitted (a native edit) or the history was reset;</li>
 *   <li>size unchanged, position decreased — undo;</li>
 *   <li>size unchanged, position advanced by more than one — redo;</li>
 *   <li>size unchanged, position advanced by exactly one — redo when the entry that is now
 *       current is the same instance that was already the next redo target, otherwise a new
 *       entry that replaced the redo tail. Without that identity check an edit performed after
 *       an undo is indistinguishable from a redo.</li>
 * </ul>
 *
 * <p>Each drain reports at most the net change since the previous drain, so several host
 * transitions that complete before the next drain are coalesced into one observation. The
 * pull-based history snapshot remains the authoritative source for entry content.</p>
 */
public final class NativeUndoIngressObserver implements AutoCloseable {

    static final String LISTENER_CLASS_ALIAS = "cubism.editor-history.undo-state-listener.class";
    static final String LISTENER_ADD_ALIAS = "cubism.editor-history.manager.undo-state-listener";
    static final String LISTENER_REMOVE_ALIAS = "cubism.editor-history.manager.undo-state-listener-remove";

    /** Bounded number of coalesced drains performed by one {@link #drain()} call. */
    private static final int MAX_DRAIN_ROUNDS = 8;
    private static final int MAX_LABEL_LENGTH = 256;

    /** One classified native history state change. */
    public enum Kind {
        /** A native edit was admitted as a new history entry. */
        COMMITTED,
        /** The native history position moved backwards. */
        UNDO,
        /** The native history position moved forwards. */
        REDO
    }

    /**
     * One observed native history state change.
     *
     * @param kind  the classified change
     * @param label the native presentation name of the newly admitted entry; empty for
     *              {@link Kind#UNDO} and {@link Kind#REDO}
     * @param entry the native entry the change is about, when the host still exposes it. It is a
     *              strong reference to host undo state and exists only so the single claimed
     *              consumer can compare entry identity and decode the entry; it must never be
     *              retained beyond the consumer call
     */
    public record Event(Kind kind, Optional<String> label, Optional<Object> entry) {
        public Event {
            Objects.requireNonNull(kind, "kind");
            label = Objects.requireNonNull(label, "label");
            entry = Objects.requireNonNull(entry, "entry");
            if (kind != Kind.COMMITTED && label.isPresent()) {
                throw new IllegalArgumentException("only a committed native edit carries a label");
            }
        }
    }

    private final VerifiedMemberResolver resolver;
    private final Object manager;
    private final Consumer<Event> sink;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder notifications = new LongAdder();
    private final LongAdder emitted = new LongAdder();
    private final LongAdder callbackFailures = new LongAdder();
    private final LongAdder rejectedWhileClosed = new LongAdder();

    private Object listener;
    private int lastSize;
    private int lastPosition;
    /**
     * The entry that would be applied by the next redo, held weakly: classification only needs
     * object identity, and a strong reference would retain host undo state after the host has
     * released it.
     */
    private WeakReference<Object> redoTarget = new WeakReference<>(null);
    /** True while the baseline state could not be read, so the first change is only recorded. */
    private boolean baselineUnknown;

    /**
     * Captures the current native history state without registering anything.
     *
     * @param resolver the verified resolver authorised for the undo state-change selectors
     * @param manager  the active document's native undo manager
     * @param sink     receives classified changes during {@link #drain()}
     */
    public NativeUndoIngressObserver(
        final VerifiedMemberResolver resolver,
        final Object manager,
        final Consumer<Event> sink
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.sink = Objects.requireNonNull(sink, "sink");
        final Object entries = this.resolver.invoke("cubism.editor-history.manager.entries", this.manager);
        final Object index = this.resolver.invoke("cubism.editor-history.manager.position", this.manager);
        if (entries instanceof List<?> values && index instanceof Number number
            && number.intValue() >= 0 && number.intValue() <= values.size()) {
            record(values, values.size(), number.intValue());
        } else {
            // Without a baseline the first observed change cannot be classified; record it only.
            this.baselineUnknown = true;
            record(List.of(), -1, -1);
        }
    }

    /**
     * Registers the listener on the native undo manager.
     *
     * @throws dev.turboism.mapping.verification.VerifiedAccessException when the exact host
     *     interface or listener registration selector is not admitted
     */
    public void attach() {
        if (closed.get()) {
            throw new IllegalStateException("observer is closed");
        }
        if (listener != null) {
            throw new IllegalStateException("listener is already attached");
        }
        final Object proxy = resolver.createFunctionalProxy(
            LISTENER_CLASS_ALIAS,
            ignored -> {
                onNativeStateChanged();
                return null;
            }
        );
        resolver.invoke(LISTENER_ADD_ALIAS, manager, proxy);
        listener = proxy;
    }

    /**
     * Classifies and emits the net native history change since the previous drain.
     *
     * @return the number of emitted {@link Event}s, {@code 0} when nothing changed
     */
    public int drain() {
        if (closed.get()) return 0;
        int count = 0;
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            if (!pending.compareAndSet(true, false)) break;
            if (closed.get()) break;
            final Optional<Event> event = classify();
            if (event.isPresent()) {
                count++;
                emitted.increment();
                sink.accept(event.orElseThrow());
            }
        }
        return count;
    }

    /** {@return the number of listener callbacks observed} */
    public long notificationCount() {
        return notifications.sum();
    }

    /** {@return the number of emitted classified changes} */
    public long emittedCount() {
        return emitted.sum();
    }

    /** {@return the number of callbacks that failed before they could be recorded} */
    public long callbackFailureCount() {
        return callbackFailures.sum();
    }

    /** {@return the number of callbacks ignored because the observer was already closed} */
    public long rejectedWhileClosedCount() {
        return rejectedWhileClosed.sum();
    }

    /** {@return whether a callback is waiting to be classified} */
    public boolean hasPendingChange() {
        return pending.get();
    }

    /** Removes the listener; safe to call more than once and safe before {@link #attach()}. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.set(false);
        final Object current = listener;
        listener = null;
        if (current == null) return;
        try {
            resolver.invoke(LISTENER_REMOVE_ALIAS, manager, current);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException removalFailure) {
            callbackFailures.increment();
        }
    }

    /**
     * Records one host notification. This runs inside the host's synchronized undo admission and
     * its listener loop has no exception isolation, so it must never throw: a failure here would
     * propagate into the host's own undo registration.
     */
    private void onNativeStateChanged() {
        try {
            if (closed.get()) {
                rejectedWhileClosed.increment();
                return;
            }
            notifications.increment();
            pending.set(true);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            callbackFailures.increment();
        }
    }

    private Optional<Event> classify() {
        final Object raw = resolver.invoke("cubism.editor-history.manager.entries", manager);
        final Object positionValue = resolver.invoke("cubism.editor-history.manager.position", manager);
        if (!(raw instanceof List<?> values) || !(positionValue instanceof Number number)) {
            return Optional.empty();
        }
        final int size = values.size();
        final int position = number.intValue();
        if (position < 0 || position > size) return Optional.empty();
        if (baselineUnknown) {
            baselineUnknown = false;
            record(values, size, position);
            return Optional.empty();
        }
        if (size == lastSize && position == lastPosition) return Optional.empty();
        final int previousSize = lastSize;
        final int previousPosition = lastPosition;
        if (size == 0 && position == 0 && previousSize != 0) {
            // The host dropped the whole history; that is not a user edit.
            record(values, size, position);
            return Optional.empty();
        }
        final Kind kind = size != previousSize
            ? Kind.COMMITTED
            : classifyUnchangedSize(values, position, previousPosition);
        if (kind == null) {
            record(values, size, position);
            return Optional.empty();
        }
        final Optional<Object> entry = entryAt(values, position);
        record(values, size, position);
        if (kind == Kind.COMMITTED) {
            return Optional.of(new Event(Kind.COMMITTED, labelAfter(values, position), entry));
        }
        return Optional.of(new Event(kind, Optional.empty(), entry));
    }

    /**
     * Classifies a change whose entry count did not change, so it is an undo, a redo, or an edit
     * that replaced the redo tail.
     *
     * @return the classified kind, or {@code null} when the host already released the entry that
     *     would prove which of the two it was
     */
    private Kind classifyUnchangedSize(final List<?> values, final int position, final int previousPosition) {
        if (position > values.size()) return null;
        if (position < previousPosition) return Kind.UNDO;
        if (position - previousPosition > 1) return Kind.REDO;
        if (position <= 0) return null;
        final Object current = values.get(position - 1);
        final Object previousRedoTarget = redoTarget.get();
        if (previousRedoTarget == null) return null;
        return current == previousRedoTarget ? Kind.REDO : Kind.COMMITTED;
    }

    private void record(final List<?> values, final int size, final int position) {
        this.lastSize = size;
        this.lastPosition = position;
        this.redoTarget = position >= 0 && position < size
            ? new WeakReference<>(values.get(position))
            : new WeakReference<>(null);
    }

    private static Optional<Object> entryAt(final List<?> values, final int position) {
        if (position <= 0 || position > values.size()) return Optional.empty();
        return Optional.ofNullable(values.get(position - 1));
    }

    private Optional<String> labelAfter(final List<?> values, final int position) {
        if (position <= 0 || position > values.size()) return Optional.empty();
        final Object entry = values.get(position - 1);
        if (entry == null) return Optional.empty();
        final Object name = resolver.invoke("cubism.editor-history.entry.presentation-name", entry);
        if (!(name instanceof String text)) return Optional.empty();
        final String stripped = text.strip();
        if (stripped.isEmpty()) return Optional.empty();
        return Optional.of(
            stripped.length() <= MAX_LABEL_LENGTH ? stripped : stripped.substring(0, MAX_LABEL_LENGTH)
        );
    }
}
