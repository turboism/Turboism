package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryId;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Process-local, weakly held metadata for native Undo entries. */
public final class EditorHistoryMetadataRegistry {

    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();
    private static final Map<IdentityReference, EntryMetadata> ENTRIES = new HashMap<>();
    private static long nextEntryId;

    private EditorHistoryMetadataRegistry() {
    }

    /**
     * Associates a Turboism {@link HistoryAction} with a native Undo entry.
     *
     * <p>The entry is held only weakly, and an existing stable entry identity or transaction
     * association is preserved.</p>
     *
     * @param nativeEntry the host Undo entry to annotate, compared by identity
     * @param action the metadata to attach
     */
    public static synchronized void register(
        final Object nativeEntry,
        final HistoryAction action
    ) {
        final Object entry = Objects.requireNonNull(nativeEntry, "nativeEntry");
        final EntryMetadata current = metadataLocked(entry);
        ENTRIES.put(new IdentityReference(entry, null), new EntryMetadata(
            current.entryId(),
            current.transactionId(),
            Optional.of(Objects.requireNonNull(action, "action"))
        ));
    }

    /**
     * Associates a committed authoring transaction with a native Undo entry.
     *
     * @param nativeEntry exact native Undo entry created by the transaction
     * @param transactionId opaque Turboism transaction identity
     */
    public static synchronized void registerTransaction(
        final Object nativeEntry,
        final String transactionId
    ) {
        final Object entry = Objects.requireNonNull(nativeEntry, "nativeEntry");
        final EntryMetadata current = metadataLocked(entry);
        ENTRIES.put(new IdentityReference(entry, null), new EntryMetadata(
            current.entryId(),
            Optional.of(normalizedTransactionId(transactionId)),
            current.action()
        ));
    }

    /**
     * Annotates the single entry a host operation appended to the Undo stack, given before and after
     * views of it.
     *
     * <p>Deliberately conservative: it registers nothing unless {@code after} is exactly one longer
     * than {@code before} and every earlier element is identical by reference.</p>
     */
    public static void registerAppended(
        final List<?> before,
        final List<?> after,
        final HistoryAction action
    ) {
        appended(before, after).ifPresent(entry -> register(entry, action));
    }

    /** Conservatively associates a transaction with exactly one appended native history entry. */
    public static void registerAppendedTransaction(
        final List<?> before,
        final List<?> after,
        final String transactionId
    ) {
        appended(before, after).ifPresent(entry -> registerTransaction(entry, transactionId));
    }

    static synchronized EntryMetadata metadata(final Object nativeEntry) {
        return metadataLocked(Objects.requireNonNull(nativeEntry, "nativeEntry"));
    }

    static synchronized Optional<HistoryAction> action(final Object nativeEntry) {
        return metadata(nativeEntry).action();
    }

    private static Optional<Object> appended(final List<?> before, final List<?> after) {
        final List<?> oldEntries = List.copyOf(Objects.requireNonNull(before, "before"));
        final List<?> newEntries = List.copyOf(Objects.requireNonNull(after, "after"));
        if (newEntries.size() != oldEntries.size() + 1) return Optional.empty();
        for (int index = 0; index < oldEntries.size(); index++) {
            if (oldEntries.get(index) != newEntries.get(index)) return Optional.empty();
        }
        return Optional.of(newEntries.get(newEntries.size() - 1));
    }

    private static EntryMetadata metadataLocked(final Object nativeEntry) {
        for (Reference<?> collected; (collected = COLLECTED.poll()) != null;) {
            ENTRIES.remove(collected);
        }
        final EntryMetadata current = ENTRIES.get(new IdentityReference(nativeEntry, null));
        if (current != null) return current;
        final EntryMetadata created = new EntryMetadata(
            new HistoryEntryId("history-entry-" + Long.toUnsignedString(++nextEntryId, 36)),
            Optional.empty(),
            Optional.empty()
        );
        ENTRIES.put(new IdentityReference(nativeEntry, COLLECTED), created);
        return created;
    }

    private static String normalizedTransactionId(final String value) {
        final String normalized = Objects.requireNonNull(value, "transactionId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        if (normalized.length() > HistoryEntryId.MAX_LENGTH) {
            throw new IllegalArgumentException(
                "transactionId must not exceed " + HistoryEntryId.MAX_LENGTH + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("transactionId must not contain control characters");
        }
        return normalized;
    }

    record EntryMetadata(
        HistoryEntryId entryId,
        Optional<String> transactionId,
        Optional<HistoryAction> action
    ) {
        EntryMetadata {
            Objects.requireNonNull(entryId, "entryId");
            transactionId = Objects.requireNonNull(transactionId, "transactionId");
            action = Objects.requireNonNull(action, "action");
        }
    }

    private static final class IdentityReference extends WeakReference<Object> {
        private final int identityHash;

        private IdentityReference(final Object entry, final ReferenceQueue<Object> queue) {
            super(entry, queue);
            identityHash = System.identityHashCode(entry);
        }

        @Override public int hashCode() { return identityHash; }

        @Override public boolean equals(final Object other) {
            if (this == other) return true;
            final Object entry = get();
            return entry != null && other instanceof IdentityReference reference
                && entry == reference.get();
        }
    }
}
