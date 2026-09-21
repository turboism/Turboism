package dev.turboism.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the install handles returned by {@link HookContributor} instances.
 *
 * <p>The agent records each successful install here. On the process-exit
 * path only handles flagged {@link HookContributor#closesOnProcessExit()}
 * close (before {@code runtime.closeForProcessExit()}); on a full shutdown
 * every enrolled handle closes before the preview runtime, in reverse
 * install order. Flagged handles report
 * {@code cleanup=COMPLETE phase=...} exactly like the hand-wired agent.</p>
 *
 * <p>Enrollment on the premain and bootstrap threads races the JVM
 * shutdown-hook close pass, so entries live in a {@link CopyOnWriteArrayList}.
 * Each close pass walks a snapshot and claims entries with an atomic
 * {@code remove}: a handle enrolled mid-pass is simply left enrolled, and
 * two racing passes never close the same handle twice.</p>
 */
final class HookRegistry {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    HookRegistry() {
    }

    /**
     * Enrolls an installed hook handle.
     *
     * @param contributor the contributor that produced the handle
     * @param handle the close handle; never {@code null}
     */
    void enroll(final HookContributor contributor, final AutoCloseable handle) {
        if (handle == null) {
            return;
        }
        entries.add(new Entry(
            contributor.id(), handle, contributor.closesOnProcessExit(), contributor.phase()));
    }

    /**
     * @return {@code true} when a hook with this identity is enrolled
     */
    boolean contains(final String id) {
        return entries.stream().anyMatch(entry -> entry.id.equals(id));
    }

    /**
     * Closes only the handles installed in the given phase, removing them from
     * the registry. Used by the bootstrap failure path to tear down the
     * runtime-dependent hooks without touching premain host fixes.
     *
     * @param phase the phase whose handles close
     * @param warn receives one message per failed close
     * @param info receives the {@code cleanup=COMPLETE} protocol lines
     */
    void closePhase(
        final HookContributor.Phase phase,
        final Consumer<String> warn,
        final Consumer<String> info
    ) {
        closeMatching(warn, info, false, "bootstrap-failure", entry -> entry.phase == phase);
    }

    /**
     * Closes only the handles flagged {@link HookContributor#closesOnProcessExit()},
     * removing them from the registry. Each close is isolated: a failing hook
     * is reported and the rest still close.
     *
     * @param warn receives one message per failed close
     * @param info receives the {@code cleanup=COMPLETE} protocol lines
     */
    void closeOnProcessExit(final Consumer<String> warn, final Consumer<String> info) {
        closeMatching(warn, info, true, "process-exit");
    }

    /**
     * Closes every remaining handle in reverse install order, removing them
     * from the registry.
     *
     * @param warn receives one message per failed close
     * @param info receives the {@code cleanup=COMPLETE} protocol lines
     */
    void closeAll(final Consumer<String> warn, final Consumer<String> info) {
        closeMatching(warn, info, false, "runtime-close");
    }

    private void closeMatching(
        final Consumer<String> warn,
        final Consumer<String> info,
        final boolean processExitOnly,
        final String phase
    ) {
        closeMatching(warn, info, processExitOnly, phase, entry -> true);
    }

    private void closeMatching(
        final Consumer<String> warn,
        final Consumer<String> info,
        final boolean processExitOnly,
        final String phase,
        final java.util.function.Predicate<Entry> filter
    ) {
        final List<Entry> snapshot = new ArrayList<>(entries);
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            final Entry entry = snapshot.get(index);
            if (processExitOnly && !entry.processExit) {
                continue;
            }
            if (!filter.test(entry)) {
                continue;
            }
            if (!entries.remove(entry)) {
                continue;
            }
            try {
                entry.handle.close();
                if (entry.processExit) {
                    info.accept(entry.id + " cleanup=COMPLETE phase=" + phase);
                }
            } catch (Throwable failure) {
                warn.accept(
                    "Turboism hook cleanup failed safely: " + entry.id + " phase=" + phase
                );
            }
        }
    }

    private static final class Entry {
        private final String id;
        private final AutoCloseable handle;
        private final boolean processExit;
        private final HookContributor.Phase phase;

        private Entry(
            final String id,
            final AutoCloseable handle,
            final boolean processExit,
            final HookContributor.Phase phase
        ) {
            this.id = id;
            this.handle = handle;
            this.processExit = processExit;
            this.phase = phase;
        }
    }
}
