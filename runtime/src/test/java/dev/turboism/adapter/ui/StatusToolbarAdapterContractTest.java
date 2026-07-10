package dev.turboism.adapter.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusToolbarAdapterContractTest {

    @Test
    void paletteToolbarCapabilityUsesRuntimePermissionOperationString() {
        assertEquals("ui.palette-toolbar.contribute", StatusToolbarAdapter.Capability.PALETTE_TOOLBAR_CONTRIBUTE.id());
        assertEquals("ui.status.notify", StatusToolbarAdapter.Capability.STATUS_NOTIFY.id());
    }

    @Test
    void notifyStatusDelegatesWhenHostVersionAndCapabilityAreAvailable() {
        RecordingHost host = new RecordingHost("5.3.2", StatusToolbarAdapter.Capability.STATUS_NOTIFY);
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
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.safeMode();

        StatusToolbarAdapter.AdapterResult<Registration> result = adapter.notifyStatus(notification("status.offline"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE, result.diagnostic().orElseThrow().code());
        assertEquals(Optional.empty(), result.value());
    }

    @Test
    void paletteToolbarContributionReturnsUnsupportedVersionBeforeDelegating() {
        RecordingHost host = new RecordingHost("5.4.0", StatusToolbarAdapter.Capability.PALETTE_TOOLBAR_CONTRIBUTE);
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(host);

        StatusToolbarAdapter.AdapterResult<Registration> result = adapter.contributePaletteToolbar(contribution("palette.probe"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED, result.diagnostic().orElseThrow().code());
        assertEquals(0, host.paletteDelegations);
    }

    @Test
    void malformedHostVersionReturnsUnsupportedVersionDiagnostic() {
        RecordingHost host = new RecordingHost("5.3.02-beta", StatusToolbarAdapter.Capability.PALETTE_TOOLBAR_CONTRIBUTE);
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(host);

        StatusToolbarAdapter.AdapterResult<Registration> result = adapter.contributePaletteToolbar(contribution("palette.malformed-version"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED, result.diagnostic().orElseThrow().code());
        assertEquals(0, host.paletteDelegations);
    }

    @Test
    void paletteToolbarReturnsCapabilityUnavailableWhenHostOmitsCapability() {
        RecordingHost host = new RecordingHost("5.3.1", StatusToolbarAdapter.Capability.STATUS_NOTIFY);
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(host);

        StatusToolbarAdapter.AdapterResult<Registration> result = adapter.contributePaletteToolbar(contribution("palette.missing-capability"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.CAPABILITY_UNAVAILABLE, result.diagnostic().orElseThrow().code());
        assertEquals(0, host.paletteDelegations);
    }

    @Test
    void notifyStatusReturnsTimeoutDiagnosticWhenHostTimesOut() {
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(new FailingHost(
            StatusToolbarAdapter.Capability.STATUS_NOTIFY,
            new AdapterHostException(SafeModeDiagnostic.Code.TIMEOUT, StatusToolbarAdapter.Capability.STATUS_NOTIFY.id(), "status timeout private-host-detail")
        ));

        StatusToolbarAdapter.AdapterResult<Registration> result = adapter.notifyStatus(notification("status.timeout"));

        assertFalse(result.isAvailable());
        SafeModeDiagnostic diagnostic = result.diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.TIMEOUT, diagnostic.code());
        assertEquals(StatusToolbarAdapter.Capability.STATUS_NOTIFY.id(), diagnostic.capability());
        assertFalse(diagnostic.message().contains("private-host-detail"));
        assertEquals("Host adapter call timed out.", diagnostic.message());
    }

    @Test
    void paletteToolbarReturnsValidationFailureDiagnosticWhenHostRejectsContribution() {
        StatusToolbarAdapter adapter = StatusToolbarAdapterImpl.connected(new FailingHost(
            StatusToolbarAdapter.Capability.PALETTE_TOOLBAR_CONTRIBUTE,
            new AdapterHostException(
                SafeModeDiagnostic.Code.VALIDATION_FAILURE,
                StatusToolbarAdapter.Capability.PALETTE_TOOLBAR_CONTRIBUTE.id(),
                "invalid palette contribution private-host-detail"
            )
        ));

        StatusToolbarAdapter.AdapterResult<Registration> result = adapter.contributePaletteToolbar(contribution("palette.invalid"));

        assertFalse(result.isAvailable());
        SafeModeDiagnostic diagnostic = result.diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.VALIDATION_FAILURE, diagnostic.code());
        assertEquals(StatusToolbarAdapter.Capability.PALETTE_TOOLBAR_CONTRIBUTE.id(), diagnostic.capability());
        assertFalse(diagnostic.message().contains("private-host-detail"));
        assertEquals("Host adapter rejected the request.", diagnostic.message());
    }

    @Test
    void runtimeUiHostServiceUsesAdapterForStatusNotifications() {
        RecordingHost host = new RecordingHost("5.3.2", StatusToolbarAdapter.Capability.STATUS_NOTIFY);
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.demo",
            dev.turboism.ui.UiHostStateSource.DEFAULT,
            new dev.turboism.sdk.plugin.DisposableScope(),
            StatusToolbarAdapterImpl.connected(host)
        );
        StatusNotification notification = notification("status.runtime");

        service.notifyStatus(notification);

        assertEquals(notification, host.notification);
        assertTrue(service.notifications().isEmpty());
        assertTrue(service.uiDiagnostics().isEmpty());
    }

    @Test
    void runtimeUiHostServiceFallsBackWhenAdapterIsUnavailable() {
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.demo"
        );
        PaletteToolbarRegistry.PaletteToolbarContribution contribution = contribution("palette.safe-mode");

        service.contributePaletteToolbar(contribution);

        assertEquals(List.of(contribution), service.paletteToolbars());
        assertEquals(
            SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE,
            service.uiDiagnostics().get(0).code()
        );
    }

    @Test
    void adapterBackedRegistrationsSurvivePluginAndScopeDualClose() throws Exception {
        CountingHost host = new CountingHost("5.3.2");
        DisposableScope scope = new DisposableScope();
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.demo",
            dev.turboism.ui.UiHostStateSource.DEFAULT,
            scope,
            StatusToolbarAdapterImpl.connected(host)
        );

        Registration status = service.notifyStatus(notification("status.dual-close"));
        Registration palette = service.contributePaletteToolbar(contribution("palette.dual-close"));

        // Official plugins may re-enroll the returned handle for stub hosts that do not auto-scope.
        scope.register(status);
        scope.register(palette);

        scope.close();

        assertEquals(1, host.statusCloseCount);
        assertEquals(1, host.paletteCloseCount);
    }

    private static StatusNotification notification(final String id) {
        return new StatusNotification(id, "INFO", "Ready");
    }

    private static PaletteToolbarRegistry.PaletteToolbarContribution contribution(final String id) {
        return new PaletteToolbarRegistry.PaletteToolbarContribution(
            id,
            "probe.action",
            "probe.label",
            "icons/probe.svg",
            "parameters",
            "end",
            10
        );
    }

    private static final class RecordingHost implements StatusToolbarAdapter.HostOperations {
        private final String hostVersion;
        private final EnumSet<StatusToolbarAdapter.Capability> capabilities;
        private StatusNotification notification;
        private boolean statusClosed;
        private int paletteDelegations;

        private RecordingHost(final String hostVersion, final StatusToolbarAdapter.Capability firstCapability) {
            this.hostVersion = hostVersion;
            this.capabilities = EnumSet.of(firstCapability);
        }

        @Override
        public String hostVersion() {
            return hostVersion;
        }

        @Override
        public boolean supports(final StatusToolbarAdapter.Capability capability) {
            return capabilities.contains(capability);
        }

        @Override
        public Registration notifyStatus(final StatusNotification notification) {
            this.notification = notification;
            return () -> statusClosed = true;
        }

        @Override
        public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            paletteDelegations++;
            return () -> {
            };
        }
    }

    private static final class CountingHost implements StatusToolbarAdapter.HostOperations {
        private final String hostVersion;
        private int statusCloseCount;
        private int paletteCloseCount;

        private CountingHost(final String hostVersion) {
            this.hostVersion = hostVersion;
        }

        @Override
        public String hostVersion() {
            return hostVersion;
        }

        @Override
        public boolean supports(final StatusToolbarAdapter.Capability capability) {
            return true;
        }

        @Override
        public Registration notifyStatus(final StatusNotification notification) {
            return () -> statusCloseCount++;
        }

        @Override
        public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            return () -> paletteCloseCount++;
        }
    }

    private static final class FailingHost implements StatusToolbarAdapter.HostOperations {
        private final StatusToolbarAdapter.Capability capability;
        private final AdapterHostException failure;

        private FailingHost(
            final StatusToolbarAdapter.Capability capability,
            final AdapterHostException failure
        ) {
            this.capability = capability;
            this.failure = failure;
        }

        @Override
        public String hostVersion() {
            return "5.3.2";
        }

        @Override
        public boolean supports(final StatusToolbarAdapter.Capability capability) {
            return this.capability == capability;
        }

        @Override
        public Registration notifyStatus(final StatusNotification notification) {
            throw failure;
        }

        @Override
        public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw failure;
        }
    }
}
