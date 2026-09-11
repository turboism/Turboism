package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.adapter.cubism.editor.history.decoder.NativeHistoryDecodeResult;
import dev.turboism.adapter.cubism.editor.history.decoder.NativeHistoryDecoderRegistry;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;

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

    /** Publishes one confirmed host-observed operation with its origin and subject. */
    @FunctionalInterface
    public interface Publisher {
        void publish(
            CubismOperation operation,
            CubismOperationOrigin origin,
            Optional<String> subjectId
        );
    }

    private final VerifiedMemberResolver resolver;
    private final Publisher publisher;
    private final NativeHistoryDecoderRegistry decoders = new NativeHistoryDecoderRegistry();
    private final NativeUndoIngressObserver observer;
    private final LongAdder published = new LongAdder();
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
                case UNDO -> publish(CubismOperation.UNDO, CubismOperationOrigin.UNDO);
                case REDO -> publish(CubismOperation.REDO, CubismOperationOrigin.REDO);
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
            publish(CubismOperation.EXECUTE_EDITOR_COMMAND, CubismOperationOrigin.HOST_UI);
            return;
        }
        if (EditorHistoryMetadataRegistry.claimsProvenance(entry.orElseThrow())) {
            suppressedOwnCommits.increment();
            return;
        }
        final NativeHistoryDecodeResult decoded = decoders.decode(
            resolver,
            entry.orElseThrow(),
            event.label().orElse("History entry")
        );
        final HistoryEntryDetail detail = decoded.outcome() == NativeHistoryDecodeResult.Outcome.DECODED
            ? decoded.detail().orElse(null)
            : null;
        final NativeHistoryOperations.Resolution resolution = NativeHistoryOperations.resolve(detail);
        publish(resolution.operation(), CubismOperationOrigin.HOST_UI, resolution.subjectId());
    }

    private void publish(final CubismOperation operation, final CubismOperationOrigin origin) {
        publish(operation, origin, Optional.empty());
    }

    private void publish(
        final CubismOperation operation,
        final CubismOperationOrigin origin,
        final Optional<String> subjectId
    ) {
        publisher.publish(operation, origin, subjectId);
        published.increment();
    }
}
