package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.adapter.cubism.editor.history.decoder.NativeHistoryDecodeResult;
import dev.turboism.adapter.cubism.editor.history.decoder.NativeHistoryDecoderRegistry;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistoryMoveSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.ui.host.EdtDispatch;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Read-only Main-mode projection of the active document's verified native Undo manager. */
public final class EditorHistorySnapshotProvider implements CubismHistory {

    private final Supplier<Optional<VerifiedMemberResolver>> resolver;
    private final LongSupplier generation;
    private final Object revisionLock = new Object();
    private final BindingIdentityTracker documentIdentities;
    private final NativeHistoryDecoderRegistry nativeDecoders = new NativeHistoryDecoderRegistry();
    private final BindingIdentityTracker managerIdentities;
    private final DecodedDetailMemo decodedDetails = new DecodedDetailMemo();
    private long revisionGeneration = -1;
    private long revision;
    private HistorySnapshot lastSnapshot;

    public EditorHistorySnapshotProvider(
            final Supplier<Optional<VerifiedMemberResolver>> resolver, final LongSupplier generation) {
        this(
                resolver,
                generation,
                new BindingIdentityTracker("history-document-"),
                new BindingIdentityTracker("history-manager-"));
    }

    EditorHistorySnapshotProvider(
            final Supplier<Optional<VerifiedMemberResolver>> resolver,
            final LongSupplier generation,
            final BindingIdentityTracker documentIdentities,
            final BindingIdentityTracker managerIdentities) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.documentIdentities = Objects.requireNonNull(documentIdentities, "documentIdentities");
        this.managerIdentities = Objects.requireNonNull(managerIdentities, "managerIdentities");
    }

    @Override
    public HistorySnapshot snapshot() {
        final long expectedGeneration = generation.getAsLong();
        if (expectedGeneration <= 0) return HistorySnapshot.unavailable();
        final Optional<VerifiedMemberResolver> available = resolver.get();
        if (available.isEmpty()
                || !available
                        .orElseThrow()
                        .authorizesFeature(
                                EditorHistoryReadSelectorContract.ADAPTER_SLICE_ID,
                                EditorHistoryReadSelectorContract.CAPABILITY_ID,
                                EditorHistoryReadSelectorContract.REQUIRED_ALIASES))
            return HistorySnapshot.unavailable();
        try {
            return onEdt(() -> project(available.orElseThrow(), expectedGeneration));
        } catch (Exception exception) {
            return HistorySnapshot.unavailable();
        }
    }

    @Override
    public boolean isCurrentBinding(final HistorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE
                || snapshot.documentBindingId().isEmpty()
                || snapshot.managerBindingId().isEmpty()) {
            return false;
        }
        final long expectedGeneration = generation.getAsLong();
        if (expectedGeneration != snapshot.generation()) return false;
        final Optional<VerifiedMemberResolver> available = resolver.get();
        if (available.isEmpty()) return false;
        try {
            return onEdt(() -> {
                final Binding binding = currentBinding(available.orElseThrow());
                return generation.getAsLong() == expectedGeneration
                        && documentBindingId(binding.document()).equals(snapshot.documentBindingId())
                        && managerBindingId(binding.manager()).equals(snapshot.managerBindingId());
            });
        } catch (Exception failure) {
            return false;
        }
    }

    @Override
    public HistoryMoveResult moveTo(final long expectedGeneration, final long expectedRevision, final int position) {
        return moveToAuthorized(expectedGeneration, expectedRevision, "", "", position);
    }

    @Override
    public HistoryMoveResult moveTo(final HistorySnapshot expected, final int position) {
        Objects.requireNonNull(expected, "expected");
        if (expected.availability() != HistorySnapshot.Availability.AVAILABLE
                || expected.documentBindingId().isEmpty()
                || expected.managerBindingId().isEmpty()) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.REJECTED_STALE, snapshot(), Optional.of("history.move.binding-required"));
        }
        return moveToAuthorized(
                expected.generation(),
                expected.revision(),
                expected.documentBindingId(),
                expected.managerBindingId(),
                position);
    }

    private HistoryMoveResult moveToAuthorized(
            final long expectedGeneration,
            final long expectedRevision,
            final String expectedDocumentBindingId,
            final String expectedManagerBindingId,
            final int position) {
        final Optional<VerifiedMemberResolver> available = resolver.get();
        if (available.isEmpty()) return unavailableMove("history.move.unavailable");
        if (!available
                .orElseThrow()
                .authorizesFeature(
                        EditorHistoryMoveSelectorContract.ADAPTER_SLICE_ID,
                        EditorHistoryMoveSelectorContract.CAPABILITY_ID,
                        EditorHistoryMoveSelectorContract.REQUIRED_ALIASES))
            return unavailableMove("history.move.unavailable");
        try {
            return onEdt(() -> move(
                    available.orElseThrow(),
                    expectedGeneration,
                    expectedRevision,
                    expectedDocumentBindingId,
                    expectedManagerBindingId,
                    position));
        } catch (Exception exception) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.FAILED_UNKNOWN_POSITION,
                    HistorySnapshot.unavailable(),
                    Optional.of("history.move.failed-unknown-position"));
        }
    }

    private HistoryMoveResult move(
            final VerifiedMemberResolver resolver,
            final long expectedGeneration,
            final long expectedRevision,
            final String expectedDocumentBindingId,
            final String expectedManagerBindingId,
            final int target) {
        final long currentGeneration = generation.getAsLong();
        final boolean bindingRequired = !expectedDocumentBindingId.isEmpty() || !expectedManagerBindingId.isEmpty();
        if (bindingRequired) {
            final Binding activeBinding;
            try {
                activeBinding = currentBinding(resolver);
            } catch (IllegalStateException unavailable) {
                return new HistoryMoveResult(
                        HistoryMoveResult.Outcome.REJECTED_STALE,
                        HistorySnapshot.unavailable(),
                        Optional.of("history.move.binding-stale"));
            }
            if (!documentBindingId(activeBinding.document()).equals(expectedDocumentBindingId)
                    || !managerBindingId(activeBinding.manager()).equals(expectedManagerBindingId)) {
                return new HistoryMoveResult(
                        HistoryMoveResult.Outcome.REJECTED_STALE,
                        HistorySnapshot.unavailable(),
                        Optional.of("history.move.binding-stale"));
            }
        }
        final HistorySnapshot before = project(resolver, currentGeneration);
        if (before.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.UNAVAILABLE, before, Optional.of("history.move.unavailable"));
        }
        if (currentGeneration != expectedGeneration || before.revision() != expectedRevision) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.REJECTED_STALE, before, Optional.of("history.move.stale"));
        }
        if (target < 0 || target > before.entries().size()) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.INVALID_POSITION, before, Optional.of("history.move.invalid-position"));
        }
        if (target == before.position()) {
            return new HistoryMoveResult(HistoryMoveResult.Outcome.NO_CHANGE, before, Optional.empty());
        }
        final Object manager = currentManager(resolver);
        final HistorySnapshot bindingCheck = project(resolver, currentGeneration);
        if (bindingCheck.availability() != HistorySnapshot.Availability.AVAILABLE
                || bindingCheck.revision() != before.revision()
                || currentManager(resolver) != manager) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.REJECTED_STALE, bindingCheck, Optional.of("history.move.stale"));
        }
        try {
            resolver.invoke("cubism.editor-history.manager.move-to", manager, target);
        } catch (RuntimeException failure) {
            final HistorySnapshot afterFailure = project(resolver, currentGeneration);
            if (afterFailure.availability() == HistorySnapshot.Availability.AVAILABLE) {
                return new HistoryMoveResult(
                        HistoryMoveResult.Outcome.PARTIAL_MOVE, afterFailure, Optional.of("history.move.partial"));
            }
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.FAILED_UNKNOWN_POSITION,
                    afterFailure,
                    Optional.of("history.move.failed-unknown-position"));
        }
        final HistorySnapshot after = project(resolver, currentGeneration);
        if (after.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.FAILED_UNKNOWN_POSITION,
                    after,
                    Optional.of("history.move.failed-unknown-position"));
        }
        return new HistoryMoveResult(
                after.position() == target ? HistoryMoveResult.Outcome.MOVED : HistoryMoveResult.Outcome.PARTIAL_MOVE,
                after,
                after.position() == target ? Optional.empty() : Optional.of("history.move.partial"));
    }

    private HistoryMoveResult unavailableMove(final String diagnosticId) {
        return new HistoryMoveResult(HistoryMoveResult.Outcome.UNAVAILABLE, snapshot(), Optional.of(diagnosticId));
    }

    private Object currentManager(final VerifiedMemberResolver resolver) {
        return currentBinding(resolver).manager();
    }

    private Binding currentBinding(final VerifiedMemberResolver resolver) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        if (document == null || !resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            throw new IllegalStateException("Active Modeling document is unavailable");
        }
        final Object manager = resolver.invoke("cubism.editor-history.document.undo-manager", document);
        if (!resolver.isInstance("cubism.editor-history.manager.class", manager)) {
            throw new IllegalStateException("Active history manager is unavailable");
        }
        return new Binding(document, manager);
    }

    private HistorySnapshot project(final VerifiedMemberResolver resolver, final long expectedGeneration) {
        if (generation.getAsLong() != expectedGeneration) return HistorySnapshot.unavailable();
        final Binding binding;
        try {
            binding = currentBinding(resolver);
        } catch (IllegalStateException unavailable) {
            return HistorySnapshot.unavailable();
        }
        final Object document = binding.document();
        final Object manager = binding.manager();
        final Object rawEntries = resolver.invoke("cubism.editor-history.manager.entries", manager);
        if (!(rawEntries instanceof List<?> values)) return HistorySnapshot.unavailable();
        final int position = number(resolver.invoke("cubism.editor-history.manager.position", manager));
        if (position < 0 || position > values.size()) return HistorySnapshot.unavailable();
        decodedDetails.enterHistory(expectedGeneration, resolver, document, manager);
        final ArrayList<HistoryEntry> entries = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            final Object entry = values.get(index);
            if (!resolver.isInstance("cubism.editor-history.entry.class", entry)) {
                return HistorySnapshot.unavailable();
            }
            final Object label = resolver.invoke("cubism.editor-history.entry.presentation-name", entry);
            final Object significant = resolver.invoke("cubism.editor-history.entry.significant", entry);
            if (!(label instanceof String text) || !(significant instanceof Boolean flag)) {
                return HistorySnapshot.unavailable();
            }
            final EditorHistoryMetadataRegistry.EntryMetadata metadata = EditorHistoryMetadataRegistry.metadata(entry);
            // Entries below the undo tip are settled host history. The tip can still be coalesced
            // in place (a list undo or a group keeps growing while the edit is open) and the host
            // rewrites the redo tail on the next edit, so those entries are always projected fresh.
            final HistoryEntryDetail detail = entryDetail(resolver, entry, text, flag, metadata, index < position - 1);
            entries.add(new HistoryEntry(
                    index,
                    text,
                    flag,
                    metadata.action(),
                    Optional.of(metadata.entryId()),
                    metadata.transactionId(),
                    detail));
        }
        decodedDetails.finishProjection();
        final boolean canUndo = flag(resolver.invoke("cubism.editor-history.manager.can-undo", manager));
        final boolean canRedo = flag(resolver.invoke("cubism.editor-history.manager.can-redo", manager));
        if (generation.getAsLong() != expectedGeneration) return HistorySnapshot.unavailable();
        return snapshotFor(
                expectedGeneration,
                position,
                entries,
                canUndo,
                canRedo,
                documentBindingId(document),
                managerBindingId(manager));
    }

    /**
     * Projects one entry's detail from the most authoritative source available.
     *
     * <p>Only the native decode is memoized: it is the one read whose cost grows with the entry,
     * and its inputs are the mutable host label, the significance flag and the entry's immutable
     * metadata record. An unsupported or failed decode is never memoized, so a later admission or
     * a transient native failure still changes the next projection.</p>
     *
     * @param settled whether the cursor already passed this entry, which is the only case a decode
     *     is reused for; see {@link DecodedDetailMemo}
     */
    private HistoryEntryDetail entryDetail(
            final VerifiedMemberResolver resolver,
            final Object entry,
            final String label,
            final boolean significant,
            final EditorHistoryMetadataRegistry.EntryMetadata metadata,
            final boolean settled) {
        if (metadata.detail().isPresent()) return metadata.detail().orElseThrow();
        if (metadata.action().isPresent()) {
            return HistoryEntryDetail.fromAction(
                    label, metadata.action().orElseThrow(), HistoryOrigin.hostUnattributed());
        }
        if (observedUsable(resolver, entry, metadata.observedDetail())) {
            // The commit-time decode is the only read that could still prove this entry's own
            // post state; a fresh decode here could not read it, so the observed projection is
            // reused instead of recomputing a poorer one.
            return metadata.observedDetail().orElseThrow();
        }
        if (settled) {
            final HistoryEntryDetail memoized = decodedDetails.detailFor(entry, label, significant, metadata);
            if (memoized != null && observedUsable(resolver, entry, Optional.of(memoized))) return memoized;
        }
        final NativeHistoryDecodeResult decoded = nativeDecoders.decode(resolver, entry, label);
        final Optional<HistoryEntryDetail> authoritative = decoded.detail();
        if (authoritative.isEmpty()) {
            return HistoryEntryDetail.labelOnly(label, HistoryOrigin.hostUnattributed(), decoded.diagnosticId());
        }
        if (settled) decodedDetails.remember(entry, label, significant, metadata, authoritative.orElseThrow());
        return authoritative.orElseThrow();
    }

    /**
     * Decides whether a commit-time observed detail still describes this entry.
     *
     * <p>A group may have been decoded while the host was still appending children. When the live
     * child count exceeds what the observed detail saw, the snapshot decodes again instead of
     * presenting a stale group as complete.</p>
     */
    private static boolean observedUsable(
            final VerifiedMemberResolver resolver, final Object entry, final Optional<HistoryEntryDetail> observed) {
        if (observed.isEmpty()) return false;
        final Optional<HistoryGroup> group = observed.orElseThrow().group();
        if (group.isEmpty()) return true;
        if (group.orElseThrow().truncated()) return false;
        try {
            final Object rawCount = resolver.invoke("cubism.editor-history.semantic.group.count", entry);
            if (!(rawCount instanceof Number count)) return true;
            return count.intValue() <= group.orElseThrow().observedChildCount();
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    private String documentBindingId(final Object document) {
        return documentIdentities.idFor(document);
    }

    private String managerBindingId(final Object manager) {
        return managerIdentities.idFor(manager);
    }

    int trackedBindingIdentityCount() {
        return documentIdentities.size() + managerIdentities.size();
    }

    int memoizedDetailCount() {
        return decodedDetails.size();
    }

    /**
     * Assigns opaque IDs by reference identity without retaining native objects after the host
     * releases them. A {@link java.util.WeakHashMap} is deliberately not used: host objects may
     * override {@code equals}/{@code hashCode}, while a binding must identify the exact instance.
     */
    static final class BindingIdentityTracker {
        private final Object lock = new Object();
        private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        private final List<BindingIdentity> identities = new ArrayList<>();
        private final String prefix;
        private long nextIdentity;

        BindingIdentityTracker(final String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
        }

        String idFor(final Object object) {
            Objects.requireNonNull(object, "object");
            synchronized (lock) {
                removeCollectedIdentities();
                for (BindingIdentity identity : identities) {
                    if (identity.get() == object) return identity.id();
                }
                final String id = prefix + Long.toUnsignedString(++nextIdentity, 36);
                identities.add(new BindingIdentity(object, collected, id));
                return id;
            }
        }

        int size() {
            synchronized (lock) {
                removeCollectedIdentities();
                return identities.size();
            }
        }

        /** Test-only deterministic stand-in for the reference collector enqueueing a dead key. */
        void clearAndEnqueueForTesting(final Object object) {
            Objects.requireNonNull(object, "object");
            synchronized (lock) {
                for (BindingIdentity identity : identities) {
                    if (identity.get() == object) {
                        identity.clear();
                        identity.enqueue();
                        return;
                    }
                }
                throw new IllegalArgumentException("binding identity is not tracked");
            }
        }

        private void removeCollectedIdentities() {
            BindingIdentity collectedIdentity;
            while ((collectedIdentity = (BindingIdentity) collected.poll()) != null) {
                identities.remove(collectedIdentity);
            }
            final Iterator<BindingIdentity> iterator = identities.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().get() == null) iterator.remove();
            }
        }
    }

    private HistorySnapshot snapshotFor(
            final long currentGeneration,
            final int position,
            final List<HistoryEntry> entries,
            final boolean canUndo,
            final boolean canRedo,
            final String documentId,
            final String managerId) {
        synchronized (revisionLock) {
            if (revisionGeneration != currentGeneration) {
                revisionGeneration = currentGeneration;
                revision = 0;
                lastSnapshot = null;
            }
            final HistorySnapshot previous = lastSnapshot;
            if (previous != null
                    && previous.position() == position
                    && previous.canUndo() == canUndo
                    && previous.canRedo() == canRedo
                    && previous.documentBindingId().equals(documentId)
                    && previous.managerBindingId().equals(managerId)
                    && previous.entries().equals(entries)) {
                return previous;
            }
            // Compare immutable values, not their formatted representation. Native objects are
            // never retained here, and mutable host labels/metadata are still checked each read.
            lastSnapshot = new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    currentGeneration,
                    ++revision,
                    position,
                    entries,
                    canUndo,
                    canRedo,
                    documentId,
                    managerId);
            return lastSnapshot;
        }
    }

    /**
     * Memoizes the recursive native decode for settled entries whose projected inputs did not
     * change.
     *
     * <p>The host can rename an entry in place, so every projection still re-reads each entry's
     * label and significance flag; only the decode is reused. A memo is therefore keyed by the
     * exact native entry plus the three inputs that can change its projection: the host label, the
     * significance flag and the entry's immutable metadata values, which a registration in
     * {@link EditorHistoryMetadataRegistry} replaces.</p>
     *
     * <p>A decode reads the entry's own recorded undo/redo data and the ids and display names of
     * the targets it names, never the model state at the cursor, so appending an entry or moving the
     * cursor cannot change an entry the cursor already passed. Those settled entries keep their
     * memo; the undo tip and the redo tail do not, because the host coalesces the tip in place (a
     * {@code ListUndo} or group keeps growing while the edit is open, which the group-count recheck
     * below cannot see) and rewrites the tail on the next edit. Excluding the tail also covers the
     * host filling in a {@code SimpleUndo}'s post state when that entry is undone. A settled row may
     * still name a target by the display name the host had when it was projected, which is the same
     * freeze a commit-time observed detail already has.</p>
     *
     * <p>Every memo is dropped when the generation, resolver, document or manager changes, and a
     * memo the current projection did not read is pruned, so an entry the host dropped (a truncated
     * redo tail) does not linger. The caller still re-checks group growth through
     * {@code observedUsable}.</p>
     *
     * <p>Only decoded details are retained; they hold bounded scalars, never native objects. The
     * native keys are weakly held so an entry the host released is never kept alive here. The memo
     * is read and written only by one projection, which the provider always runs on the editor
     * dispatch thread, so it needs no lock of its own.</p>
     */
    static final class DecodedDetailMemo {
        private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        private final Map<EntryKey, MemoizedDetail> details = new HashMap<>();
        private WeakReference<Object> document = new WeakReference<>(null);
        private WeakReference<Object> manager = new WeakReference<>(null);
        private VerifiedMemberResolver resolver;
        private long generation = Long.MIN_VALUE;
        private long projection;

        void enterHistory(
                final long generation,
                final VerifiedMemberResolver resolver,
                final Object document,
                final Object manager) {
            projection++;
            if (this.generation == generation
                    && this.resolver == resolver
                    && this.document.get() == document
                    && this.manager.get() == manager) {
                return;
            }
            details.clear();
            this.generation = generation;
            this.resolver = resolver;
            this.document = new WeakReference<>(document);
            this.manager = new WeakReference<>(manager);
        }

        /** Returns the memoized detail, or {@code null} when this entry has to be decoded again. */
        HistoryEntryDetail detailFor(
                final Object entry,
                final String label,
                final boolean significant,
                final EditorHistoryMetadataRegistry.EntryMetadata metadata) {
            removeCollected();
            final EntryKey key = new EntryKey(entry, collected);
            final MemoizedDetail memo = details.get(key);
            if (memo == null) return null;
            if (!memo.label().equals(label)
                    || memo.significant() != significant
                    || !memo.metadata().equals(metadata)) {
                details.remove(key);
                return null;
            }
            memo.seen = projection;
            return memo.detail();
        }

        void remember(
                final Object entry,
                final String label,
                final boolean significant,
                final EditorHistoryMetadataRegistry.EntryMetadata metadata,
                final HistoryEntryDetail detail) {
            removeCollected();
            details.put(
                    new EntryKey(entry, collected),
                    new MemoizedDetail(label, significant, metadata, detail, projection));
        }

        /** Drops every memo this projection did not read, including a truncated redo tail. */
        void finishProjection() {
            removeCollected();
            final Iterator<MemoizedDetail> memos = details.values().iterator();
            while (memos.hasNext()) {
                if (memos.next().seen != projection) memos.remove();
            }
        }

        int size() {
            removeCollected();
            return details.size();
        }

        private void removeCollected() {
            for (Object expired; (expired = collected.poll()) != null; ) {
                details.remove(expired);
            }
        }
    }

    /**
     * Identifies one native entry by reference identity. A {@link java.util.WeakHashMap} is
     * deliberately not used: host objects may override {@code equals}/{@code hashCode}, while a
     * memo must belong to the exact instance.
     */
    private static final class EntryKey extends WeakReference<Object> {
        private final int identityHash;

        private EntryKey(final Object entry, final ReferenceQueue<Object> queue) {
            super(entry, queue);
            identityHash = System.identityHashCode(entry);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) return true;
            final Object entry = get();
            return entry != null && other instanceof EntryKey key && entry == key.get();
        }
    }

    /** One memoized decode, stamped with the projection that last read it. */
    private static final class MemoizedDetail {
        private final String label;
        private final boolean significant;
        private final EditorHistoryMetadataRegistry.EntryMetadata metadata;
        private final HistoryEntryDetail detail;
        private long seen;

        private MemoizedDetail(
                final String label,
                final boolean significant,
                final EditorHistoryMetadataRegistry.EntryMetadata metadata,
                final HistoryEntryDetail detail,
                final long seen) {
            this.label = Objects.requireNonNull(label, "label");
            this.significant = significant;
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.detail = Objects.requireNonNull(detail, "detail");
            this.seen = seen;
        }

        private String label() {
            return label;
        }

        private boolean significant() {
            return significant;
        }

        private EditorHistoryMetadataRegistry.EntryMetadata metadata() {
            return metadata;
        }

        private HistoryEntryDetail detail() {
            return detail;
        }
    }

    private record Binding(Object document, Object manager) {
        Binding {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(manager, "manager");
        }
    }

    private static final class BindingIdentity extends WeakReference<Object> {
        private final String id;

        private BindingIdentity(final Object referent, final ReferenceQueue<Object> queue, final String id) {
            super(referent, queue);
            this.id = id;
        }

        private String id() {
            return id;
        }
    }

    private static int number(final Object value) {
        if (!(value instanceof Integer number)) throw new IllegalStateException("History position is invalid");
        return number;
    }

    private static boolean flag(final Object value) {
        if (!(value instanceof Boolean flag)) throw new IllegalStateException("History flag is invalid");
        return flag;
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        try {
            return EdtDispatch.callExact("editor history EDT operation", EdtDispatch.DEFAULT_ACCEPT_TIMEOUT, call);
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            if (failure instanceof Exception exception) throw exception;
            // Callers catch Exception only; keep non-fatal Errors reachable behind the same
            // checked wrapper the pre-dispatch handoff produced.
            throw new java.lang.reflect.InvocationTargetException(failure);
        }
    }
}
