package dev.turboism.adapter.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.UiHostStateSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiSurfaceAdapterContractTest {

    @Test
    void safeModeReturnsCapabilitySpecificDiagnostic() {
        UiSurfaceAdapter adapter = UiSurfaceAdapterImpl.safeMode();

        UiSurfaceAdapter.AdapterResult<Registration> result = adapter.contributeOverlay(
            new OverlayContribution("overlay", "viewport", 1)
        );

        assertFalse(result.isAvailable());
        assertEquals("ui.overlay.contribute", result.diagnostic().orElseThrow().capability());
    }

    @Test
    void runtimeServiceRoutesUiSurfacesThroughConnectedAdapterWithoutLocalResidue() throws Exception {
        RecordingHost host = new RecordingHost();
        DisposableScope scope = new DisposableScope();
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.test",
            UiHostStateSource.DEFAULT,
            scope,
            StatusToolbarAdapterImpl.safeMode(),
            MainToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.connected(host)
        );

        service.contributeOverlay(new OverlayContribution("overlay", "viewport", 1));
        service.openDialog(new DialogRequest("dialog", "Dialog", "Body"));
        service.contributeEmbeddedPanel(new EmbeddedPanelContribution("panel", "Panel", "side", 1));
        assertTrue(service.confirmDialog(new DialogRequest("confirm", "Confirm", "Proceed?")));
        assertEquals(Optional.of("imports/file.csv"), service.requestFile(
            new FileChooserRequest("file", "File", List.of("csv"))
        ));

        assertEquals(1, host.overlays.size());
        assertEquals(1, host.dialogs.size());
        assertEquals(1, host.panels.size());
        assertTrue(service.overlays().isEmpty());
        assertTrue(service.dialogs().isEmpty());
        assertTrue(service.panels().isEmpty());

        scope.close();
        assertTrue(host.overlays.isEmpty());
        assertTrue(host.dialogs.isEmpty());
        assertTrue(host.panels.isEmpty());
    }

    @Test
    void unsupportedVersionAndHostFailuresFailClosed() {
        UiSurfaceAdapter unsupported = UiSurfaceAdapterImpl.connected(new RecordingHost("5.4.0"));
        assertEquals(
            "ui.dialog.contribute",
            unsupported.openDialog(new DialogRequest("d", "D", "B"))
                .diagnostic().orElseThrow().capability()
        );

        UiSurfaceAdapter timeout = UiSurfaceAdapterImpl.connected(new FailingHost(
            new AdapterHostException(
                SafeModeDiagnostic.Code.TIMEOUT,
                "ui.file-chooser.request",
                "timeout"
            )
        ));
        assertEquals(
            SafeModeDiagnostic.Code.TIMEOUT,
            timeout.requestFile(new FileChooserRequest("file", "File", List.of("csv")))
                .diagnostic().orElseThrow().code()
        );

        UiSurfaceAdapter unexpected = UiSurfaceAdapterImpl.connected(new FailingHost(
            new IllegalStateException("private host details")
        ));
        SafeModeDiagnostic diagnostic = unexpected.contributeOverlay(
            new OverlayContribution("overlay", "viewport", 1)
        ).diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.VALIDATION_FAILURE, diagnostic.code());
        assertFalse(diagnostic.message().contains("private host details"));
    }

    private static class RecordingHost implements UiSurfaceAdapter.HostOperations {
        private final String version;
        private final List<OverlayContribution> overlays = new ArrayList<>();
        private final List<DialogRequest> dialogs = new ArrayList<>();
        private final List<EmbeddedPanelContribution> panels = new ArrayList<>();

        private RecordingHost() {
            this("5.3.2");
        }

        private RecordingHost(final String version) {
            this.version = version;
        }

        @Override public String hostVersion() { return version; }
        @Override public boolean supports(UiSurfaceAdapter.Capability capability) { return true; }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            overlays.add(contribution);
            return () -> overlays.remove(contribution);
        }

        @Override
        public Registration openDialog(DialogRequest request) {
            dialogs.add(request);
            return () -> dialogs.remove(request);
        }

        @Override public boolean confirmDialog(DialogRequest request) { return true; }

        @Override
        public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) {
            panels.add(contribution);
            return () -> panels.remove(contribution);
        }

        @Override public Optional<String> requestFile(FileChooserRequest request) {
            return Optional.of("imports/file.csv");
        }
    }

    private static final class FailingHost extends RecordingHost {
        private final RuntimeException failure;

        private FailingHost(final RuntimeException failure) {
            this.failure = failure;
        }

        @Override public String hostVersion() { throw failure; }
    }
}
