package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Read-only Main-mode projection of the active document's verified native Undo manager. */
public final class EditorHistorySnapshotProvider implements CubismHistory {

    private final Supplier<Optional<VerifiedMemberResolver>> resolver;
    private final LongSupplier generation;
    private final Object revisionLock = new Object();
    private long revisionGeneration = -1;
    private long revision;
    private String lastFingerprint = "";

    public EditorHistorySnapshotProvider(
        final Supplier<Optional<VerifiedMemberResolver>> resolver,
        final LongSupplier generation
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    @Override
    public HistorySnapshot snapshot() {
        final long expectedGeneration = generation.getAsLong();
        if (expectedGeneration <= 0) return HistorySnapshot.unavailable();
        final Optional<VerifiedMemberResolver> available = resolver.get();
        if (available.isEmpty()) return HistorySnapshot.unavailable();
        try {
            return onEdt(() -> project(available.orElseThrow(), expectedGeneration));
        } catch (Exception exception) {
            return HistorySnapshot.unavailable();
        }
    }

    @Override
    public HistoryMoveResult moveTo(
        final long expectedGeneration,
        final long expectedRevision,
        final int position
    ) {
        final Optional<VerifiedMemberResolver> available = resolver.get();
        if (available.isEmpty()) return unavailableMove("history.move.unavailable");
        if (!available.orElseThrow().authorizesFeature(
            "adapter.editor-model.readwrite",
            "cubism.editor-history.move",
            java.util.Set.of("cubism.editor-history.manager.move-to")
        )) return unavailableMove("history.move.unavailable");
        try {
            return onEdt(() -> move(
                available.orElseThrow(),
                expectedGeneration,
                expectedRevision,
                position
            ));
        } catch (Exception exception) {
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.FAILED_UNKNOWN_POSITION,
                HistorySnapshot.unavailable(),
                Optional.of("history.move.failed-unknown-position")
            );
        }
    }

    private HistoryMoveResult move(
        final VerifiedMemberResolver resolver,
        final long expectedGeneration,
        final long expectedRevision,
        final int target
    ) {
        final long currentGeneration = generation.getAsLong();
        final HistorySnapshot before = project(resolver, currentGeneration);
        if (before.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.UNAVAILABLE,
                before,
                Optional.of("history.move.unavailable")
            );
        }
        if (currentGeneration != expectedGeneration || before.revision() != expectedRevision) {
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.REJECTED_STALE,
                before,
                Optional.of("history.move.stale")
            );
        }
        if (target < 0 || target > before.entries().size()) {
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.INVALID_POSITION,
                before,
                Optional.of("history.move.invalid-position")
            );
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
                HistoryMoveResult.Outcome.REJECTED_STALE,
                bindingCheck,
                Optional.of("history.move.stale")
            );
        }
        try {
            resolver.invoke("cubism.editor-history.manager.move-to", manager, target);
        } catch (RuntimeException failure) {
            final HistorySnapshot afterFailure = project(resolver, currentGeneration);
            if (afterFailure.availability() == HistorySnapshot.Availability.AVAILABLE) {
                return new HistoryMoveResult(
                    HistoryMoveResult.Outcome.PARTIAL_MOVE,
                    afterFailure,
                    Optional.of("history.move.partial")
                );
            }
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.FAILED_UNKNOWN_POSITION,
                afterFailure,
                Optional.of("history.move.failed-unknown-position")
            );
        }
        final HistorySnapshot after = project(resolver, currentGeneration);
        if (after.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.FAILED_UNKNOWN_POSITION,
                after,
                Optional.of("history.move.failed-unknown-position")
            );
        }
        return new HistoryMoveResult(
            after.position() == target
                ? HistoryMoveResult.Outcome.MOVED
                : HistoryMoveResult.Outcome.PARTIAL_MOVE,
            after,
            after.position() == target ? Optional.empty() : Optional.of("history.move.partial")
        );
    }

    private HistoryMoveResult unavailableMove(final String diagnosticId) {
        return new HistoryMoveResult(
            HistoryMoveResult.Outcome.UNAVAILABLE,
            snapshot(),
            Optional.of(diagnosticId)
        );
    }

    private Object currentManager(final VerifiedMemberResolver resolver) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        if (document == null || !resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            throw new IllegalStateException("Active Modeling document is unavailable");
        }
        final Object manager = resolver.invoke("cubism.editor-history.document.undo-manager", document);
        if (!resolver.isInstance("cubism.editor-history.manager.class", manager)) {
            throw new IllegalStateException("Active history manager is unavailable");
        }
        return manager;
    }

    private HistorySnapshot project(
        final VerifiedMemberResolver resolver,
        final long expectedGeneration
    ) {
        if (generation.getAsLong() != expectedGeneration) return HistorySnapshot.unavailable();
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        if (document == null || !resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            return HistorySnapshot.unavailable();
        }
        final Object manager = resolver.invoke("cubism.editor-history.document.undo-manager", document);
        if (!resolver.isInstance("cubism.editor-history.manager.class", manager)) {
            return HistorySnapshot.unavailable();
        }
        final Object rawEntries = resolver.invoke("cubism.editor-history.manager.entries", manager);
        if (!(rawEntries instanceof List<?> values)) return HistorySnapshot.unavailable();
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
            entries.add(new HistoryEntry(
                index,
                text,
                flag,
                EditorHistoryMetadataRegistry.action(entry)
            ));
        }
        final int position = number(resolver.invoke("cubism.editor-history.manager.position", manager));
        final boolean canUndo = flag(resolver.invoke("cubism.editor-history.manager.can-undo", manager));
        final boolean canRedo = flag(resolver.invoke("cubism.editor-history.manager.can-redo", manager));
        if (generation.getAsLong() != expectedGeneration) return HistorySnapshot.unavailable();
        return new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            expectedGeneration,
            nextRevision(expectedGeneration, manager, position, entries, canUndo, canRedo),
            position,
            entries,
            canUndo,
            canRedo
        );
    }

    private long nextRevision(
        final long currentGeneration,
        final Object manager,
        final int position,
        final List<HistoryEntry> entries,
        final boolean canUndo,
        final boolean canRedo
    ) {
        final String fingerprint = System.identityHashCode(manager) + ":" + position + ":" + canUndo + ":" + canRedo
            + ":" + entries;
        synchronized (revisionLock) {
            if (revisionGeneration != currentGeneration) {
                revisionGeneration = currentGeneration;
                revision = 0;
                lastFingerprint = "";
            }
            if (!fingerprint.equals(lastFingerprint)) {
                revision++;
                lastFingerprint = fingerprint;
            }
            return revision;
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
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(call.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) {
            final Throwable throwable = failure.get();
            if (throwable instanceof Exception exception) throw exception;
            throw new InvocationTargetException(throwable);
        }
        return result.get();
    }
}
