package dev.turboism.sdk.ui.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceContractTest {

    @Test
    void valuesAreImmutableAndValidateText() {
        WorkspaceInfo info = new WorkspaceInfo(new WorkspaceId("modeling"), "Modeling");
        WorkspaceStatus status = new WorkspaceStatus(
            WorkspaceStatus.Availability.AVAILABLE,
            Optional.of(info),
            List.of(info),
            Optional.empty()
        );

        assertEquals(info, status.current().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> status.available().add(info));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceId(" "));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceInfo(info.id(), ""));
    }

    @Test
    void unavailableServiceFailsClosedWithTypedResults() throws Exception {
        WorkspaceService service = WorkspaceService.unavailable();

        WorkspaceStatus status = service.current().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(WorkspaceStatus.Availability.UNAVAILABLE, status.availability());
        assertEquals(List.of(), status.available());
        assertEquals(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            service.switchTo(new WorkspaceId("modeling"))
                .toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            service.updateDefault().toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
        assertEquals(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            service.resetToDefault().toCompletableFuture().get(1, TimeUnit.SECONDS).outcome()
        );
        assertThrows(NullPointerException.class, () -> service.switchTo(null));
    }
}
