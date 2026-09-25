package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Session snapshot source selection seam: the source delegates to the wired live reader
 * on every query instead of serving a canned empty selection.
 */
class HostSessionSnapshotSourceTest {

    private final ProjectWorkspaceAdapter projectWorkspace = ProjectWorkspaceAdapter.Impl.safeMode();

    @Test
    void selectionIsEmptyWhenNoLiveReaderIsWired() {
        final HostSnapshotSource source = HostSessionSnapshotSource.forSession(projectWorkspace);

        assertEquals(HostSelection.empty(), source.selection());
    }

    @Test
    void selectionDelegatesToTheWiredLiveReader() {
        final HostSelection live = new HostSelection(
            List.of("WarpA", "MeshA"), Optional.empty(), Optional.empty(), Optional.empty()
        );
        final HostSnapshotSource source = HostSessionSnapshotSource.forSession(
            projectWorkspace, () -> live
        );

        assertSame(live, source.selection());
    }

    @Test
    void selectionReflectsLiveReaderChangesAcrossQueries() {
        final HostSelection first = new HostSelection(
            List.of("WarpA"), Optional.empty(), Optional.empty(), Optional.empty()
        );
        final HostSelection second = new HostSelection(
            List.of("MeshA"), Optional.empty(), Optional.empty(), Optional.empty()
        );
        final HostSelection[] current = { first };
        final Supplier<HostSelection> reader = () -> current[0];
        final HostSnapshotSource source = HostSessionSnapshotSource.forSession(projectWorkspace, reader);

        assertSame(first, source.selection());
        current[0] = second;
        assertSame(second, source.selection());
    }

    @Test
    void liveReaderFailurePropagatesInsteadOfMaskingAsEmpty() {
        final HostSnapshotSource source = HostSessionSnapshotSource.forSession(
            projectWorkspace,
            () -> { throw new IllegalStateException("live selection read failed"); }
        );

        assertThrows(IllegalStateException.class, source::selection);
    }
}
