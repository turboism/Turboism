package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.sdk.cubism.history.HistoryAction;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Process-local metadata for native Undo entries created by Turboism. */
public final class EditorHistoryMetadataRegistry {

    private static final List<Metadata> ACTIONS = new ArrayList<>();

    private EditorHistoryMetadataRegistry() {
    }

    /**
     * Associates a Turboism {@link HistoryAction} with a native Undo entry.
     *
     * <p>The entry is held only weakly, so registration never keeps a host object alive; entries that
     * have been collected, and any previous registration for the same entry, are dropped on each
     * call. The association is process-local and is not persisted with the project.
     *
     * @param nativeEntry the host Undo entry to annotate, compared by identity
     * @param action the metadata to attach
     * @throws NullPointerException if either argument is {@code null}
     */
    public static synchronized void register(final Object nativeEntry, final HistoryAction action) {
        final Object entry = java.util.Objects.requireNonNull(nativeEntry, "nativeEntry");
        ACTIONS.removeIf(metadata -> metadata.entry().get() == null || metadata.entry().get() == entry);
        ACTIONS.add(new Metadata(new WeakReference<>(entry),
            java.util.Objects.requireNonNull(action, "action")));
    }

    /**
     * Annotates the single entry a host operation appended to the Undo stack, given before and after
     * views of it.
     *
     * <p>Deliberately conservative: it registers nothing unless {@code after} is exactly one longer
     * than {@code before} and every earlier element is identical by reference. Any other shape - a
     * truncated stack, a replaced entry, several appends - is treated as "cannot attribute" and is
     * silently ignored rather than guessed at. Both lists are copied before inspection.
     *
     * @param before the Undo entries as they stood before the operation
     * @param after the Undo entries afterwards
     * @param action the metadata to attach to the appended entry
     */
    public static void registerAppended(
        final List<?> before,
        final List<?> after,
        final HistoryAction action
    ) {
        final List<?> oldEntries = List.copyOf(before);
        final List<?> newEntries = List.copyOf(after);
        if (newEntries.size() != oldEntries.size() + 1) return;
        for (int index = 0; index < oldEntries.size(); index++) {
            if (oldEntries.get(index) != newEntries.get(index)) return;
        }
        register(newEntries.get(newEntries.size() - 1), action);
    }

    static synchronized Optional<HistoryAction> action(final Object nativeEntry) {
        ACTIONS.removeIf(metadata -> metadata.entry().get() == null);
        return ACTIONS.stream()
            .filter(metadata -> metadata.entry().get() == nativeEntry)
            .map(Metadata::action)
            .findFirst();
    }

    private record Metadata(WeakReference<Object> entry, HistoryAction action) {
    }
}
