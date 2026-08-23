package dev.turboism.adapter.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;
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
        UiSurfaceAdapter.AdapterResult<Registration> result = UiSurfaceAdapterImpl.safeMode()
            .openDialog(new DialogRequest("dialog", "Dialog", "Body"));

        assertFalse(result.isAvailable());
        assertEquals("ui.dialog.contribute", result.diagnostic().orElseThrow().capability());
    }

    @Test
    void runtimeServiceRoutesCommandStyleUiThroughConnectedAdapter() throws Exception {
        RecordingHost host = new RecordingHost();
        DisposableScope scope = new DisposableScope();
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.test",
            UiHostStateSource.DEFAULT,
            scope,
            StatusToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.connected(host)
        );

        service.openDialog(new DialogRequest("dialog", "Dialog", "Body"));
        assertTrue(service.confirmDialog(new DialogRequest("confirm", "Confirm", "Proceed?")));
        assertEquals(Optional.of("imports/file.csv"), service.requestFile(
            new FileChooserRequest("file", "File", List.of("csv"))
        ));

        assertEquals(1, host.dialogs.size());
        assertTrue(service.dialogs().isEmpty());
        scope.close();
        assertTrue(host.dialogs.isEmpty());
    }

    @Test
    void unsupportedVersionAndHostFailuresFailClosed() {
        UiSurfaceAdapter unsupported = UiSurfaceAdapterImpl.connected(new RecordingHost("5.4.0"));
        assertEquals(
            SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED,
            unsupported.openDialog(new DialogRequest("d", "D", "B")).diagnostic().orElseThrow().code()
        );

        UiSurfaceAdapter timeout = UiSurfaceAdapterImpl.connected(new FailingHost(new AdapterHostException(
            SafeModeDiagnostic.Code.TIMEOUT,
            "ui.file-chooser.request",
            "timeout"
        )));
        assertEquals(
            SafeModeDiagnostic.Code.TIMEOUT,
            timeout.requestFile(new FileChooserRequest("file", "File", List.of("csv")))
                .diagnostic().orElseThrow().code()
        );

        SafeModeDiagnostic diagnostic = UiSurfaceAdapterImpl.connected(
            new FailingHost(new IllegalStateException("private host details"))
        ).openDialog(new DialogRequest("d", "D", "B")).diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.VALIDATION_FAILURE, diagnostic.code());
        assertFalse(diagnostic.message().contains("private host details"));
    }

    private static class RecordingHost implements UiSurfaceAdapter.HostOperations {
        private final String version;
        private final List<DialogRequest> dialogs = new ArrayList<>();

        private RecordingHost() {
            this("5.3.02");
        }

        private RecordingHost(final String version) {
            this.version = version;
        }

        @Override public String hostVersion() { return version; }
        @Override public boolean supports(final UiSurfaceAdapter.Capability capability) { return true; }
        @Override public Registration openDialog(final DialogRequest request) {
            dialogs.add(request);
            return () -> dialogs.remove(request);
        }
        @Override public boolean confirmDialog(final DialogRequest request) { return true; }
        @Override public Optional<String> requestFile(final FileChooserRequest request) {
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
