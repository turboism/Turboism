package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Adapter seam for the host Recent Files menu projection. */
public interface RecentFileAdapter {

    /**
     * @return the projected Recent Files entries in host order (copied defensively by the
     *         connected adapter); empty when the host reports none
     */
    List<RecentFileSummary> list();

    /**
     * @return the id of the project file the host currently has open; empty when the host
     *         reports none
     */
    Optional<RecentFileId> current();

    /**
     * An adapter for when no host is attached.
     *
     * @return a host-free adapter that answers an empty list and no current file
     */
    static RecentFileAdapter safeMode() {
        return connected(new HostOperations() {
            @Override
            public List<RecentFileSummary> list() {
                return List.of();
            }

            @Override
            public Optional<RecentFileId> current() {
                return Optional.empty();
            }
        });
    }

    /**
     * An adapter that reads through the given host operations.
     *
     * @param host the live host operations, non-null
     * @return an adapter bound to that host
     * @throws NullPointerException if {@code host} is null
     */
    static RecentFileAdapter connected(final HostOperations host) {
        Objects.requireNonNull(host, "host");
        return new RecentFileAdapter() {
            @Override
            public List<RecentFileSummary> list() {
                return List.copyOf(host.list());
            }

            @Override
            public Optional<RecentFileId> current() {
                return host.current();
            }
        };
    }

    /** The raw host call surface the connected adapter delegates to. */
    interface HostOperations {
        /**
         * @return the host Recent Files entries in menu order; may be empty, never null
         */
        List<RecentFileSummary> list();

        /**
         * @return the id of the project file currently open on the host; empty when none
         */
        Optional<RecentFileId> current();
    }
}
