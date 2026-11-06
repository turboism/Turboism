package dev.turboism.sdk.cubism.recentfile;

import dev.turboism.sdk.CubismEditor;
import java.util.List;
import java.util.Objects;

/** Read-only projection of the host's Recent Files menu (merged with the current project). */
@CubismEditor(from = "5.0.00")
public interface RecentFileService {

    /**
     * Returns the recent project files in host menu order. The currently open project
     * is merged ahead of the menu entries and deduplicated against them. A menu entry
     * whose file no longer exists is omitted (fail closed).
     */
    List<RecentFileSummary> list();

    /**
     * Opens the recent project identified by {@code id} as if the user picked it from
     * the host's Recent Files menu.
     *
     * <p>The request is only dispatched: the host reports the actual open through the
     * normal model-file lifecycle hooks and events. Implementations fail closed and
     * return {@code false} for an unknown, stale, or unavailable entry.</p>
     *
     * @param id the opaque recent-file identity returned by {@link #list()}
     * @return {@code true} when the host received an open request; {@code false} when
     *         nothing was dispatched
     * @throws NullPointerException when {@code id} is {@code null}
     */
    default boolean open(final RecentFileId id) {
        Objects.requireNonNull(id, "id");
        return false;
    }

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} when the backend backing this instance is
     *         unavailable, including the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /** Safe-mode instance: never touches the host and returns no fabricated files. */
    static RecentFileService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements RecentFileService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public List<RecentFileSummary> list() {
            return List.of();
        }
    }
}
