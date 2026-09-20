package dev.turboism.bootstrap;

import java.util.ArrayList;
import java.util.List;
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
 */
final class HookRegistry {

    private final List<Entry> entries = new ArrayList<>();

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
        entries.add(new Entry(contributor.id(), handle, contributor.closesOnProcessExit()));
    }

    /**
     * @return {@code true} when a hook with this identity is enrolled
     */
    boolean contains(final String id) {
        return entries.stream().anyMatch(entry -> entry.id.equals(id));
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
        for (int index = entries.size() - 1; index >= 0; index--) {
            final Entry entry = entries.get(index);
            if (processExitOnly && !entry.processExit) {
                continue;
            }
            entries.remove(index);
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

        private Entry(final String id, final AutoCloseable handle, final boolean processExit) {
            this.id = id;
            this.handle = handle;
            this.processExit = processExit;
        }
    }
}
