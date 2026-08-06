package dev.turboism.sdk.cubism.recentfile;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/** Read-only projection of the host's Recent Files menu (merged with the current project). */
@PreviewApi
public interface RecentFileService {

    /**
     * Returns the recent project files in host menu order. The currently open project
     * is merged ahead of the menu entries and deduplicated against them. A menu entry
     * whose file no longer exists is omitted (fail closed).
     */
    List<RecentFileSummary> list();

    /** Safe-mode instance: never touches the host and returns no fabricated files. */
    static RecentFileService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements RecentFileService {
        INSTANCE;

        @Override
        public List<RecentFileSummary> list() {
            return List.of();
        }
    }
}
