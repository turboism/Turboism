package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.adapter.cubism.editor.history.decoder.NativeHistoryDecodeResult;
import dev.turboism.adapter.cubism.editor.history.decoder.NativeHistoryDecoderRegistry;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Publishes host-performed Editor edits as semantic lifecycle facts.
 *
 * <p>This is the single claimed consumer of {@link NativeUndoIngressObserver} events. It runs
 * outside the host's undo admission, so it may read and decode the host, but it still converts
 * every failure into a counted observation instead of throwing: the observer is attached inside
 * the host's listener loop, and an exception escaping the drain would abort a caller that is
 * usually a UI action.</p>
 *
 * <p>Classification is structural. A committed entry is decoded through the same exact-class
 * decoders the history projection uses, and only the typed relation that decode proves selects a
 * semantic operation; anything else falls back to the generic editor command. A native edit name
 * is never an identity.</p>
 *
 * <p>An entry the Turboism capture itself authored is not re-published: the capture already
 * published its own lifecycle for the invitation it controlled. The de-duplication key is the
 * identity of the native entry object Turboism annotated, never a label, count or time window. A
 * snapshot projection that merely allocated an identity for an entry is not authorship and does
 * not suppress publication.</p>
 */
public final class NativeEditIngress implements AutoCloseable {

    /**
     * Publishes one confirmed host-observed operation with its origin, subject and presentation
     * label.
     */
    @FunctionalInterface
    public interface Publisher {
        void publish(
            CubismOperation operation,
            CubismOperationOrigin origin,
            Optional<String> subjectId,
            Optional<String> label
        );
    }

    private final VerifiedMemberResolver resolver;
    private final Object manager;
    private final Publisher publisher;
    private final NativeHistoryDecoderRegistry decoders = new NativeHistoryDecoderRegistry();
    private final NativeUndoIngressObserver observer;
    private final LongAdder published = new LongAdder();

    private static final String COMPONENT = "native-edit-ingress";

    /** How many confirmed edits one session describes in the log before it stops. */
    private static final long MAX_DIAGNOSED_COMMITS = 40L;

    private final LongAdder diagnosed = new LongAdder();
    private final LongAdder suppressedOwnCommits = new LongAdder();
    private final LongAdder failures = new LongAdder();

    /**
     * Creates an ingress over one active document's native undo manager.
     *
     * @param resolver the resolver already authorised for the history read and ingress selectors
     * @param manager  the active document's native undo manager
     * @param publisher receives confirmed host-observed operations
     */
    public NativeEditIngress(
        final VerifiedMemberResolver resolver,
        final Object manager,
        final Publisher publisher
    ) {
        this(resolver, manager, publisher, () -> { });
    }

    /**
     * Creates an ingress that also signals when a native change is waiting to be classified.
     *
     * @param onNotification enqueue-only signal delivered from inside the host listener loop; it
     *     must never read the host and never run plugin logic
     */
    public NativeEditIngress(
        final VerifiedMemberResolver resolver,
        final Object manager,
        final Publisher publisher,
        final Runnable onNotification
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.observer = new NativeUndoIngressObserver(
            this.resolver,
            Objects.requireNonNull(manager, "manager"),
            this::accept,
            Objects.requireNonNull(onNotification, "onNotification")
        );
    }

    /**
     * Registers the native listener.
     *
     * @throws dev.turboism.mapping.verification.VerifiedAccessException when the exact listener
     *     interface or registration selector is not admitted
     */
    public void attach() {
        observer.attach();
    }

    /**
     * Consumes every native change observed since the previous drain and publishes the semantic
     * facts they prove.
     *
     * @return the number of classified native changes consumed, including the ones suppressed as
     *     Turboism's own commit
     */
    public int drain() {
        return observer.drain();
    }

    /** {@return the number of semantic facts published for host-performed edits} */
    public long publishedCount() {
        return published.sum();
    }

    /** {@return the number of host notifications suppressed as Turboism's own commit} */
    public long suppressedOwnCommitCount() {
        return suppressedOwnCommits.sum();
    }

    /** {@return the number of classifications or publications that failed safely} */
    public long failureCount() {
        return failures.sum();
    }

    /** {@return the number of native listener callbacks observed} */
    public long notificationCount() {
        return observer.notificationCount();
    }

    /** Removes the native listener; safe to call more than once. */
    @Override
    public void close() {
        observer.close();
    }

    /**
     * Consumes one classified native change. Never throws: a failure here would escape the drain
     * into whichever host or plugin action triggered it.
     */
    void accept(final NativeUndoIngressObserver.Event event) {
        try {
            switch (event.kind()) {
                case COMMITTED -> acceptCommit(event);
                case UNDO -> publish(
                    CubismOperation.UNDO,
                    CubismOperationOrigin.UNDO,
                    event.label()
                );
                case REDO -> publish(
                    CubismOperation.REDO,
                    CubismOperationOrigin.REDO,
                    event.label()
                );
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            failures.increment();
        }
    }

    private void acceptCommit(final NativeUndoIngressObserver.Event event) {
        final Optional<Object> entry = event.entry();
        if (entry.isEmpty()) {
            publish(
                CubismOperation.EXECUTE_EDITOR_COMMAND,
                CubismOperationOrigin.HOST_UI,
                event.label()
            );
            return;
        }
        if (EditorHistoryMetadataRegistry.claimsProvenance(entry.orElseThrow())) {
            suppressedOwnCommits.increment();
            return;
        }
        final boolean tip = isCurrentTip(entry.orElseThrow());
        final NativeHistoryDecodeResult decoded = decoders.decode(
            resolver,
            entry.orElseThrow(),
            event.label().orElse("History entry"),
            tip
        );
        final HistoryEntryDetail detail = decoded.outcome() == NativeHistoryDecodeResult.Outcome.DECODED
            ? decoded.detail().orElse(null)
            : null;
        // The snapshot projection runs later, when this entry may no longer be the tip and its
        // post state is no longer provably this entry's own. Persisting the commit-time decode is
        // the only way a later projection can still show what the commit actually did.
        if (detail != null) {
            EditorHistoryMetadataRegistry.registerObserved(entry.orElseThrow(), detail);
        }
        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(detail);
        diagnose(tip, decoded, detail, resolution);
        publish(
            resolution.operation(),
            CubismOperationOrigin.HOST_UI,
            resolution.subjectId(),
            event.label()
        );
    }

    /**
     * Records why one confirmed edit classified the way it did.
     *
     * <p>Bounded and exception-proof: this exists to make a host run self-explaining, so it must
     * never fail an edit or flood the log. Only the first few edits of a session are described,
     * which is enough to tell a refused live-target read from a refusal by the mapping.</p>
     */
    private void diagnose(
        final boolean tip,
        final NativeHistoryDecodeResult decoded,
        final HistoryEntryDetail detail,
        final NativeHistoryOperations.Resolution resolution
    ) {
        try {
            diagnosed.increment();
            if (diagnosed.sum() > MAX_DIAGNOSED_COMMITS) return;
            RuntimeDiagnostics.info(
                COMPONENT,
                "classified entry tip=" + tip
                    + " outcome=" + decoded.outcome()
                    + " diagnostic=" + decoded.diagnosticId()
                    + " detail=" + (detail == null ? "none" : detail.detailLevel())
                    + " group=" + (detail != null && detail.group().isPresent())
                    + " operation=" + resolution.operation()
            );
        } catch (Throwable ignored) {
            // Diagnostics must never disturb classification.
        }
    }

    /**
     * Calculates whether this entry is still the undo manager's current tip.
     *
     * <p>A decode runs outside the host's listener loop, so the operator may have committed another
     * edit since the change was observed. An entry that is no longer the tip has a live target
     * holding a later edit's result, which must never be read as this entry's post state.</p>
     */
    private boolean isCurrentTip(final Object entry) {
        try {
            final Object raw = resolver.invoke("cubism.editor-history.manager.entries", manager);
            final Object positionValue =
                resolver.invoke("cubism.editor-history.manager.position", manager);
            if (!(raw instanceof List<?> values) || !(positionValue instanceof Number number)) {
                return false;
            }
            return isCurrentTip(values, number.intValue(), entry);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Calculates whether one entry is the tip of the undo list the host just reported.
     *
     * <p>Package-private and pure so the rule can be pinned without a live host: the tip is the
     * entry at {@code position - 1} and only when the cursor is at the end of the list, because a
     * cursor behind the tail means later entries exist that could already have overwritten the
     * value being read.</p>
     *
     * @param values   the host's undo list
     * @param position the host's current position
     * @param entry    the entry being decoded
     * @return whether the live target still holds this entry's own result
     */
    static boolean isCurrentTip(
        final List<?> values,
        final int position,
        final Object entry
    ) {
        return values != null
            && position > 0
            && position == values.size()
            && values.get(position - 1) == entry;
    }

    private void publish(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> label
    ) {
        publish(operation, origin, Optional.empty(), label);
    }

    private void publish(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId,
        final Optional<String> label
    ) {
        publisher.publish(operation, origin, subjectId, label);
        published.increment();
    }
}
