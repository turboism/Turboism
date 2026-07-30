package dev.turboism.adapter.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusToolbarAdapterContractTest {

    @Test
    void notifyStatusDelegatesWhenHostVersionAndCapabilityAreAvailable() {
        RecordingHost host = new RecordingHost("5.3.2");
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(host);
        StatusNotification notification = notification("status.ready");

        StatusToolbarAdapter.AdapterResult<Registration> result = adapter.notifyStatus(notification);

        assertTrue(result.isAvailable());
        assertEquals(Optional.empty(), result.diagnostic());
        assertEquals(notification, host.notification);
        result.value().orElseThrow().close();
        assertTrue(host.statusClosed);
    }

    @Test
    void notifyStatusReturnsAdapterUnavailableDiagnosticWhenSafeModeIsDisconnected() {
        StatusToolbarAdapter.AdapterResult<Registration> result = StatusToolbarAdapterImpl.safeMode()
            .notifyStatus(notification("status.offline"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE, result.diagnostic().orElseThrow().code());
    }

    @Test
    void notifyStatusReturnsTimeoutDiagnosticWhenHostTimesOut() {
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(new FailingHost(
            new AdapterHostException(
                SafeModeDiagnostic.Code.TIMEOUT,
                StatusToolbarAdapter.Capability.STATUS_NOTIFY.id(),
                "status timeout private-host-detail"
            )
        ));

        SafeModeDiagnostic diagnostic = adapter.notifyStatus(notification("status.timeout"))
            .diagnostic().orElseThrow();

        assertEquals(SafeModeDiagnostic.Code.TIMEOUT, diagnostic.code());
        assertFalse(diagnostic.message().contains("private-host-detail"));
    }

    @Test
    void runtimeUiHostServiceUsesAdapterForStatusNotificationsAndClosesOnce() throws Exception {
        CountingHost host = new CountingHost();
        DisposableScope scope = new DisposableScope();
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.demo",
            dev.turboism.ui.UiHostStateSource.DEFAULT,
            scope,
            StatusToolbarAdapterImpl.connected(host)
        );

        Registration status = service.notifyStatus(notification("status.runtime"));
        scope.register(status);
        scope.close();

        assertEquals(1, host.closeCount);
        assertTrue(service.notifications().isEmpty());
        assertTrue(service.uiDiagnostics().isEmpty());
    }

    private static StatusNotification notification(final String id) {
        return new StatusNotification(id, "INFO", "Ready");
    }

    private static class RecordingHost implements StatusToolbarAdapter.HostOperations {
        private final String hostVersion;
        private StatusNotification notification;
        private boolean statusClosed;

        private RecordingHost(final String hostVersion) {
            this.hostVersion = hostVersion;
        }

        @Override public String hostVersion() { return hostVersion; }
        @Override public boolean supports(final StatusToolbarAdapter.Capability capability) { return true; }
        @Override public Registration notifyStatus(final StatusNotification notification) {
            this.notification = notification;
            return () -> statusClosed = true;
        }
    }

    private static final class CountingHost extends RecordingHost {
        private int closeCount;

        private CountingHost() {
            super("5.3.2");
        }

        @Override public Registration notifyStatus(final StatusNotification notification) {
            return () -> closeCount++;
        }
    }

    private static final class FailingHost extends RecordingHost {
        private final AdapterHostException failure;

        private FailingHost(final AdapterHostException failure) {
            super("5.3.2");
            this.failure = failure;
        }

        @Override public Registration notifyStatus(final StatusNotification notification) {
            throw failure;
        }
    }
}
