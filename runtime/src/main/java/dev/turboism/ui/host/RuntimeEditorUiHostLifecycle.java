package dev.turboism.ui.host;

import dev.turboism.sdk.plugin.Registration;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Thread-safe runtime owner of Editor UI host generation and family readiness. */
public final class RuntimeEditorUiHostLifecycle implements EditorUiHostLifecycle, AutoCloseable {

    private final Map<Long, Consumer<EditorUiHostSnapshot>> listeners = new LinkedHashMap<>();
    private EditorUiHostSnapshot snapshot = EditorUiHostSnapshot.safeMode();
    private long nextListenerId;
    private boolean closed;

    @Override
    public synchronized EditorUiHostSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public Registration subscribe(final Consumer<EditorUiHostSnapshot> listener) {
        final Consumer<EditorUiHostSnapshot> registered = Objects.requireNonNull(listener, "listener");
        final long id;
        final EditorUiHostSnapshot current;
        synchronized (this) {
            requireOpen();
            id = ++nextListenerId;
            listeners.put(id, registered);
            current = snapshot;
        }
        registered.accept(current);
        return () -> removeListener(id);
    }

    /**
     * Opens a new host generation with no families ready yet.
     *
     * @return the published {@code CONNECTING} snapshot, carrying a freshly incremented generation
     */
    public EditorUiHostSnapshot connecting() {
        return transition(
            EditorUiHostSnapshot.State.CONNECTING,
            nextGeneration(),
            Set.of(),
            Optional.empty()
        );
    }

    /**
     * Records that the host attached but has not yet published any ready family.
     *
     * @param generation generation the caller is reporting for; supplied rather than incremented,
     *     so a stale connect cannot overwrite a newer generation
     * @return the published {@code CONNECTED_NOT_READY} snapshot
     */
    public EditorUiHostSnapshot connected(final long generation) {
        return transition(
            EditorUiHostSnapshot.State.CONNECTED_NOT_READY,
            generation,
            Set.of(),
            Optional.empty()
        );
    }

    /**
     * Publishes the set of UI families that are ready for a generation. The families are copied
     * defensively, so later mutation of the caller's set does not affect the snapshot.
     *
     * @param generation generation the readiness belongs to
     * @param readyFamilies families now usable; an empty set yields {@code CONNECTED_NOT_READY}
     *     rather than {@code READY}
     * @return the published snapshot
     * @throws NullPointerException if {@code readyFamilies} is null
     */
    public EditorUiHostSnapshot ready(
        final long generation,
        final Set<EditorUiFamily> readyFamilies
    ) {
        final Set<EditorUiFamily> families = EditorUiHostSnapshot.immutableFamilies(
            Objects.requireNonNull(readyFamilies, "readyFamilies")
        );
        return transition(
            families.isEmpty()
                ? EditorUiHostSnapshot.State.CONNECTED_NOT_READY
                : EditorUiHostSnapshot.State.READY,
            generation,
            families,
            Optional.empty()
        );
    }

    /**
     * Marks the host as being swapped out: all families stop being ready, but the generation is
     * deliberately kept so the replacement can report against the same one.
     *
     * @return the published {@code REPLACING} snapshot
     */
    public EditorUiHostSnapshot replacing() {
        final EditorUiHostSnapshot current = snapshot();
        return transition(
            EditorUiHostSnapshot.State.REPLACING,
            current.generation(),
            Set.of(),
            Optional.empty()
        );
    }

    /**
     * Retires the current generation because no host is present, clearing every ready family and
     * recording a {@code HOST_UNAVAILABLE} failure.
     *
     * @return the published {@code ABSENT} snapshot with a freshly incremented generation
     */
    public EditorUiHostSnapshot absent() {
        return transition(
            EditorUiHostSnapshot.State.ABSENT,
            nextGeneration(),
            Set.of(),
            Optional.of(EditorUiHostFailure.host(
                EditorUiHostFailure.Code.HOST_UNAVAILABLE,
                "Editor UI host is unavailable."
            ))
        );
    }

    /**
     * Retires the current generation with a recorded failure, clearing every ready family.
     *
     * @param failure sanitized reason the host failed
     * @return the published {@code FAILED} snapshot with a freshly incremented generation
     * @throws NullPointerException if {@code failure} is null
     */
    public EditorUiHostSnapshot failed(final EditorUiHostFailure failure) {
        return transition(
            EditorUiHostSnapshot.State.FAILED,
            nextGeneration(),
            Set.of(),
            Optional.of(Objects.requireNonNull(failure, "failure"))
        );
    }

    /**
     * Drops one UI family from the ready set without disturbing the others or the generation. The
     * host falls back to {@code CONNECTED_NOT_READY} only when this was the last ready family.
     *
     * @param family family that can no longer be used
     * @param message runtime-authored explanation recorded as a {@code FAMILY_UNAVAILABLE} failure;
     *     must not be blank
     * @return the published snapshot
     * @throws NullPointerException if {@code family} is null
     */
    public EditorUiHostSnapshot markFamilyUnavailable(
        final EditorUiFamily family,
        final String message
    ) {
        Objects.requireNonNull(family, "family");
        final EditorUiHostSnapshot current = snapshot();
        final EnumSet<EditorUiFamily> remaining = current.readyFamilies().isEmpty()
            ? EnumSet.noneOf(EditorUiFamily.class)
            : EnumSet.copyOf(current.readyFamilies());
        remaining.remove(family);
        return transition(
            remaining.isEmpty()
                ? EditorUiHostSnapshot.State.CONNECTED_NOT_READY
                : EditorUiHostSnapshot.State.READY,
            current.generation(),
            remaining,
            Optional.of(EditorUiHostFailure.family(
                EditorUiHostFailure.Code.FAMILY_UNAVAILABLE,
                message,
                family
            ))
        );
    }

    @Override
    public void close() {
        final List<Consumer<EditorUiHostSnapshot>> notificationTargets;
        final EditorUiHostSnapshot closedSnapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            closedSnapshot = new EditorUiHostSnapshot(
                EditorUiHostSnapshot.State.CLOSED,
                snapshot.generation() + 1,
                Set.of(),
                Optional.of(EditorUiHostFailure.host(
                    EditorUiHostFailure.Code.CLOSED,
                    "Editor UI host lifecycle is closed."
                ))
            );
            snapshot = closedSnapshot;
            notificationTargets = List.copyOf(listeners.values());
            listeners.clear();
        }
        notifyListeners(notificationTargets, closedSnapshot);
    }

    private EditorUiHostSnapshot transition(
        final EditorUiHostSnapshot.State state,
        final long generation,
        final Set<EditorUiFamily> readyFamilies,
        final Optional<EditorUiHostFailure> failure
    ) {
        final List<Consumer<EditorUiHostSnapshot>> notificationTargets;
        final EditorUiHostSnapshot next;
        synchronized (this) {
            requireOpen();
            if (generation < snapshot.generation()) {
                throw new IllegalArgumentException("generation must not move backwards");
            }
            next = new EditorUiHostSnapshot(state, generation, readyFamilies, failure);
            if (next.equals(snapshot)) {
                return snapshot;
            }
            snapshot = next;
            notificationTargets = List.copyOf(listeners.values());
        }
        notifyListeners(notificationTargets, next);
        return next;
    }

    private synchronized long nextGeneration() {
        requireOpen();
        return snapshot.generation() + 1;
    }

    private synchronized void removeListener(final long id) {
        listeners.remove(id);
    }

    private synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Editor UI host lifecycle is closed");
        }
    }

    private static void notifyListeners(
        final List<Consumer<EditorUiHostSnapshot>> listeners,
        final EditorUiHostSnapshot snapshot
    ) {
        RuntimeException first = null;
        for (Consumer<EditorUiHostSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException exception) {
                if (first == null) {
                    first = exception;
                } else {
                    first.addSuppressed(exception);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
