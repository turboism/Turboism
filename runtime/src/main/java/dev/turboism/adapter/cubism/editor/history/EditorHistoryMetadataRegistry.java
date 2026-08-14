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

    public static synchronized void register(final Object nativeEntry, final HistoryAction action) {
        final Object entry = java.util.Objects.requireNonNull(nativeEntry, "nativeEntry");
        ACTIONS.removeIf(metadata -> metadata.entry().get() == null || metadata.entry().get() == entry);
        ACTIONS.add(new Metadata(new WeakReference<>(entry),
            java.util.Objects.requireNonNull(action, "action")));
    }

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
