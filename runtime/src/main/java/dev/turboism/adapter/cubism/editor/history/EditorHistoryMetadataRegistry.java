package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryEntryId;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Process-local, weakly held metadata for native Undo entries. */
public final class EditorHistoryMetadataRegistry {

    private static final List<Metadata> ENTRIES = new ArrayList<>();
    private static long nextEntryId;

    private EditorHistoryMetadataRegistry() {
    }

    /** Associates a compatibility action with a native Undo entry. */
    public static synchronized void register(
        final Object nativeEntry,
        final HistoryAction action
    ) {
        final Object entry = Objects.requireNonNull(nativeEntry, "nativeEntry");
        final Metadata current = metadataLocked(entry);
        replace(current, current.withAction(Objects.requireNonNull(action, "action")));
    }

    /** Associates authoritative operation-time semantic detail with a native Undo entry. */
    public static synchronized void registerDetail(
        final Object nativeEntry,
        final HistoryEntryDetail detail
    ) {
        final Object entry = Objects.requireNonNull(nativeEntry, "nativeEntry");
        final Metadata current = metadataLocked(entry);
        replace(current, current.withDetail(Objects.requireNonNull(detail, "detail")));
    }

    /** Atomically associates captured detail and an optional compatibility action. */
    public static synchronized void registerCaptured(
        final Object nativeEntry,
        final HistoryEntryDetail detail,
        final Optional<HistoryAction> action
    ) {
        final Object entry = Objects.requireNonNull(nativeEntry, "nativeEntry");
        final HistoryEntryDetail trustedDetail = Objects.requireNonNull(detail, "detail");
        final Optional<HistoryAction> trustedAction = Objects.requireNonNull(action, "action");
        trustedAction.ifPresent(value -> {
            if (!trustedDetail.isCompatibleWith(value)) {
                throw new IllegalArgumentException("captured action conflicts with semantic detail");
            }
        });
        final Metadata current = metadataLocked(entry);
        replace(current, new Metadata(
            current.entry(),
            current.entryId(),
            current.transactionId(),
            trustedAction.isPresent() ? trustedAction : current.action(),
            Optional.of(trustedDetail)
        ));
    }

    /** Associates a committed authoring transaction with a native Undo entry. */
    public static synchronized void registerTransaction(
        final Object nativeEntry,
        final String transactionId
    ) {
        final Object entry = Objects.requireNonNull(nativeEntry, "nativeEntry");
        final Metadata current = metadataLocked(entry);
        replace(current, current.withTransaction(normalizedTransactionId(transactionId)));
    }

    /** Atomically registers the complete authoritative transaction annotation. */
    public static synchronized void registerTransaction(
        final Object nativeEntry,
        final String transactionId,
        final HistoryEntryDetail detail,
        final Optional<HistoryAction> action
    ) {
        final Object entry = Objects.requireNonNull(nativeEntry, "nativeEntry");
        final HistoryEntryDetail trustedDetail = Objects.requireNonNull(detail, "detail");
        final Optional<HistoryAction> trustedAction = Objects.requireNonNull(action, "action");
        trustedAction.ifPresent(value -> {
            if (!trustedDetail.isCompatibleWith(value)) {
                throw new IllegalArgumentException("transaction action conflicts with semantic detail");
            }
        });
        final Metadata current = metadataLocked(entry);
        replace(current, new Metadata(
            current.entry(),
            current.entryId(),
            Optional.of(normalizedTransactionId(transactionId)),
            trustedAction.isPresent() ? trustedAction : current.action(),
            Optional.of(trustedDetail)
        ));
    }

    /**
     * Prepares finalized metadata before native admission can notify observers.
     * The returned identity does not imply commitment; snapshots expose only reachable entries.
     */
    public static synchronized HistoryEntryId prepareTransaction(
        final Object nativeEntry,
        final String transactionId,
        final HistoryEntryDetail detail,
        final Optional<HistoryAction> action
    ) {
        registerTransaction(nativeEntry, transactionId, detail, action);
        return metadataLocked(nativeEntry).entryId();
    }

    /** Conservatively annotates exactly one appended entry with a compatibility action. */
    public static void registerAppended(
        final List<?> before,
        final List<?> after,
        final HistoryAction action
    ) {
        appended(before, after).ifPresent(entry -> register(entry, action));
    }

    /** Conservatively annotates exactly one appended entry with captured semantic metadata. */
    public static void registerAppended(
        final List<?> before,
        final List<?> after,
        final HistoryEntryDetail detail,
        final Optional<HistoryAction> action
    ) {
        appended(before, after).ifPresent(entry -> registerCaptured(entry, detail, action));
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
        final Metadata value = metadataLocked(
            Objects.requireNonNull(nativeEntry, "nativeEntry")
        );
        return new EntryMetadata(
            value.entryId(),
            value.transactionId(),
            value.action(),
            value.detail()
        );
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

    private static Metadata metadataLocked(final Object nativeEntry) {
        ENTRIES.removeIf(metadata -> metadata.entry().get() == null);
        for (Metadata metadata : ENTRIES) {
            if (metadata.entry().get() == nativeEntry) return metadata;
        }
        final Metadata created = new Metadata(
            new WeakReference<>(nativeEntry),
            new HistoryEntryId("history-entry-" + Long.toUnsignedString(++nextEntryId, 36)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
        ENTRIES.add(created);
        return created;
    }

    private static void replace(final Metadata current, final Metadata replacement) {
        final int index = ENTRIES.indexOf(current);
        if (index < 0) {
            throw new IllegalStateException("history metadata entry disappeared during update");
        }
        ENTRIES.set(index, replacement);
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
        Optional<HistoryAction> action,
        Optional<HistoryEntryDetail> detail
    ) {
        EntryMetadata {
            Objects.requireNonNull(entryId, "entryId");
            transactionId = Objects.requireNonNull(transactionId, "transactionId");
            action = Objects.requireNonNull(action, "action");
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    private record Metadata(
        WeakReference<Object> entry,
        HistoryEntryId entryId,
        Optional<String> transactionId,
        Optional<HistoryAction> action,
        Optional<HistoryEntryDetail> detail
    ) {
        Metadata withAction(final HistoryAction value) {
            return new Metadata(entry, entryId, transactionId, Optional.of(value), detail);
        }

        Metadata withDetail(final HistoryEntryDetail value) {
            return new Metadata(entry, entryId, transactionId, action, Optional.of(value));
        }

        Metadata withTransaction(final String value) {
            return new Metadata(entry, entryId, Optional.of(value), action, detail);
        }
    }
}
