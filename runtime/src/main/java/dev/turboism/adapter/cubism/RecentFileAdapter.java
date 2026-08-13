package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Adapter seam for the host Recent Files menu projection. */
public interface RecentFileAdapter {

    List<RecentFileSummary> list();

    Optional<RecentFileId> current();

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

    interface HostOperations {
        List<RecentFileSummary> list();

        Optional<RecentFileId> current();
    }
}
