package dev.turboism.sdk.ui.workspace.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceLayoutContractTest {

    @Test
    void snapshotsAreImmutableAndValidateText() {
        PaletteTab tab = new PaletteTab("turboism:com.example.plugin:myPanel");
        PaletteDock dock = new PaletteDock(List.of(tab));
        WorkspaceLayoutSnapshot snapshot = new WorkspaceLayoutSnapshot(
            WorkspaceLayoutSnapshot.Availability.AVAILABLE,
            Optional.of(dock),
            Optional.empty()
        );

        assertEquals(List.of(tab), dock.tabs());
        assertEquals(dock, snapshot.root().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> dock.tabs().add(new PaletteTab("x")));
        assertThrows(UnsupportedOperationException.class, () -> new SplitDock(new ArrayList<>()).children().add(dock));
        assertThrows(NullPointerException.class, () -> new PaletteTab(null));
        assertThrows(IllegalArgumentException.class, () -> new PaletteTab(" "));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceLayoutSnapshot(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            Optional.empty(),
            Optional.of("  ")
        ));
    }

    @Test
    void unavailableServiceFailsClosedWithTypedSnapshot() throws Exception {
        WorkspaceLayoutService service = WorkspaceLayoutService.unavailable();

        WorkspaceLayoutSnapshot snapshot = service.current().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(WorkspaceLayoutSnapshot.Availability.UNAVAILABLE, snapshot.availability());
        assertEquals(Optional.empty(), snapshot.root());
        assertEquals(Optional.of("workspace.layout.unavailable"), snapshot.diagnosticCode());
    }
}
