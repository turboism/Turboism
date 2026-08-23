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
        RecordingHost host = new RecordingHost("5.3.02");
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

    @Test
    void runtimeUiServiceScopesAdapterVisibleStatusIdByPluginId() throws Exception {
        RecordingHost first = new RecordingHost("5.3.02");
        RecordingHost second = new RecordingHost("5.3.02");
        RuntimeUiHostCapabilityService serviceA = service("plugin.a", first);
        RuntimeUiHostCapabilityService serviceB = service("plugin.b", second);

        serviceA.notifyStatus(new StatusNotification("build", "INFO", "a-1"));
        serviceA.notifyStatus(new StatusNotification("build", "WARNING", "a-2"));
        serviceB.notifyStatus(new StatusNotification("build", "INFO", "b"));

        assertEquals("8:plugin.a:build", first.notification.id());
        assertEquals("8:plugin.b:build", second.notification.id());
        assertFalse(first.notification.id().equals(second.notification.id()));
        assertEquals("a-2", first.notification.message(), "message must pass through unchanged");
        assertEquals("WARNING", first.notification.severity(), "severity must pass through unchanged");
    }

    @Test
    void runtimeUiServiceScopedStatusIdsCannotCollideAcrossPlugins() throws Exception {
        RecordingHost first = new RecordingHost("5.3.02");
        RecordingHost second = new RecordingHost("5.3.02");

        service("a", first).notifyStatus(new StatusNotification("b:c", "INFO", "one"));
        service("a:b", second).notifyStatus(new StatusNotification("c", "INFO", "two"));

        assertEquals("1:a:b:c", first.notification.id());
        assertEquals("3:a:b:c", second.notification.id());
        assertFalse(first.notification.id().equals(second.notification.id()));
    }

    @Test
    void runtimeUiServiceFallbackKeepsOriginalNotificationIdentity() throws Exception {
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.fallback",
            dev.turboism.ui.UiHostStateSource.DEFAULT,
            new DisposableScope()
        );

        service.notifyStatus(new StatusNotification("build", "ERROR", "failed"));

        assertEquals(1, service.notifications().size());
        assertEquals("build", service.notifications().get(0).id(),
            "fallback memory state must keep the plugin's original notification");
        assertEquals("ERROR", service.notifications().get(0).severity());
        assertEquals("failed", service.notifications().get(0).message());
    }

    @Test
    void runtimeUiServiceScopingPreservesCompactMetricPresentation() throws Exception {
        RecordingHost host = new RecordingHost("5.3.02");
        RuntimeUiHostCapabilityService service = service("plugin.cpu", host);

        service.notifyStatus(new StatusNotification(
            "perf.cpu",
            "INFO",
            "CPU 12.3%",
            StatusNotification.Presentation.COMPACT_METRIC
        ));

        assertEquals("10:plugin.cpu:perf.cpu", host.notification.id(), "scoped id must be unchanged");
        assertEquals("CPU 12.3%", host.notification.message(), "message must pass through unchanged");
        assertEquals(
            StatusNotification.Presentation.COMPACT_METRIC,
            host.notification.presentation(),
            "presentation must survive plugin-ID scoping and reconstruction"
        );
    }

    private static RuntimeUiHostCapabilityService service(
        final String pluginId,
        final RecordingHost host
    ) {
        return new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            pluginId,
            dev.turboism.ui.UiHostStateSource.DEFAULT,
            new DisposableScope(),
            StatusToolbarAdapterImpl.connected(host)
        );
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
            super("5.3.02");
        }

        @Override public Registration notifyStatus(final StatusNotification notification) {
            return () -> closeCount++;
        }
    }

    private static final class FailingHost extends RecordingHost {
        private final AdapterHostException failure;

        private FailingHost(final AdapterHostException failure) {
            super("5.3.02");
            this.failure = failure;
        }

        @Override public Registration notifyStatus(final StatusNotification notification) {
            throw failure;
        }
    }
}
