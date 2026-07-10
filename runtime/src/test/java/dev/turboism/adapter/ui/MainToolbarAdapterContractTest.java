package dev.turboism.adapter.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainToolbarAdapterContractTest {

    @Test
    void mainToolbarCapabilityUsesRuntimePermissionOperationString() {
        assertEquals("ui.main-toolbar.contribute", MainToolbarAdapter.Capability.MAIN_TOOLBAR_CONTRIBUTE.id());
    }

    @Test
    void mainToolbarContributionDelegatesWhenHostVersionAndCapabilityAreAvailable() {
        RecordingHost host = new RecordingHost("5.3.2", MainToolbarAdapter.Capability.MAIN_TOOLBAR_CONTRIBUTE);
        MainToolbarAdapter adapter = MainToolbarAdapterImpl.connected(host);
        MainToolbarRegistry.MainToolbarContribution contribution = contribution("main.ready");

        MainToolbarAdapter.AdapterResult<Registration> result = adapter.contributeMainToolbar(contribution);

        assertTrue(result.isAvailable());
        assertEquals(Optional.empty(), result.diagnostic());
        assertEquals(contribution, host.contribution);
        result.value().orElseThrow().close();
        assertTrue(host.closed);
    }

    @Test
    void mainToolbarReturnsAdapterUnavailableDiagnosticWhenSafeModeIsDisconnected() {
        MainToolbarAdapter adapter = MainToolbarAdapterImpl.safeMode();

        MainToolbarAdapter.AdapterResult<Registration> result = adapter.contributeMainToolbar(contribution("main.safe-mode"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE, result.diagnostic().orElseThrow().code());
        assertEquals(Optional.empty(), result.value());
    }

    @Test
    void mainToolbarContributionReturnsUnsupportedVersionBeforeDelegating() {
        RecordingHost host = new RecordingHost("5.4.0", MainToolbarAdapter.Capability.MAIN_TOOLBAR_CONTRIBUTE);
        MainToolbarAdapter adapter = MainToolbarAdapterImpl.connected(host);

        MainToolbarAdapter.AdapterResult<Registration> result = adapter.contributeMainToolbar(contribution("main.unsupported"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED, result.diagnostic().orElseThrow().code());
        assertEquals(0, host.delegations);
    }

    @Test
    void mainToolbarReturnsCapabilityUnavailableWhenHostOmitsCapability() {
        RecordingHost host = new RecordingHost("5.3.1");
        MainToolbarAdapter adapter = MainToolbarAdapterImpl.connected(host);

        MainToolbarAdapter.AdapterResult<Registration> result = adapter.contributeMainToolbar(contribution("main.missing-capability"));

        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.CAPABILITY_UNAVAILABLE, result.diagnostic().orElseThrow().code());
        assertEquals(0, host.delegations);
    }

    @Test
    void mainToolbarReturnsTimeoutDiagnosticWhenHostTimesOut() {
        MainToolbarAdapter adapter = MainToolbarAdapterImpl.connected(new FailingHost(new AdapterHostException(
            SafeModeDiagnostic.Code.TIMEOUT,
            MainToolbarAdapter.Capability.MAIN_TOOLBAR_CONTRIBUTE.id(),
            "main toolbar timeout"
        )));

        MainToolbarAdapter.AdapterResult<Registration> result = adapter.contributeMainToolbar(contribution("main.timeout"));

        assertFalse(result.isAvailable());
        SafeModeDiagnostic timeoutDiagnostic = result.diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.TIMEOUT, timeoutDiagnostic.code());
        assertFalse(timeoutDiagnostic.message().contains("timeout boom"));
        assertEquals("Host adapter call timed out.", timeoutDiagnostic.message());
    }

    @Test
    void mainToolbarReturnsValidationFailureDiagnosticWhenHostRejectsContribution() {
        MainToolbarAdapter adapter = MainToolbarAdapterImpl.connected(new FailingHost(new AdapterHostException(
            SafeModeDiagnostic.Code.VALIDATION_FAILURE,
            MainToolbarAdapter.Capability.MAIN_TOOLBAR_CONTRIBUTE.id(),
            "invalid main toolbar contribution"
        )));

        MainToolbarAdapter.AdapterResult<Registration> result = adapter.contributeMainToolbar(contribution("main.invalid"));

        assertFalse(result.isAvailable());
        SafeModeDiagnostic validationDiagnostic = result.diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.VALIDATION_FAILURE, validationDiagnostic.code());
        assertFalse(validationDiagnostic.message().contains("private-host-detail"));
        assertEquals("Host adapter rejected the request.", validationDiagnostic.message());
    }

    @Test
    void runtimeUiHostServiceUsesMainToolbarAdapter() {
        RecordingHost host = new RecordingHost("5.3.2", MainToolbarAdapter.Capability.MAIN_TOOLBAR_CONTRIBUTE);
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.demo",
            dev.turboism.ui.UiHostStateSource.DEFAULT,
            new DisposableScope(),
            StatusToolbarAdapterImpl.safeMode(),
            MainToolbarAdapterImpl.connected(host)
        );
        MainToolbarRegistry.MainToolbarContribution contribution = contribution("main.runtime");

        service.contributeMainToolbar(contribution);

        assertEquals(contribution, host.contribution);
        assertTrue(service.mainToolbars().isEmpty());
        assertTrue(service.uiDiagnostics().isEmpty());
    }

    @Test
    void runtimeUiHostServiceFallsBackWhenMainToolbarAdapterIsUnavailable() {
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.demo"
        );
        MainToolbarRegistry.MainToolbarContribution contribution = contribution("main.safe-mode");

        service.contributeMainToolbar(contribution);

        assertEquals(List.of(contribution), service.mainToolbars());
        assertEquals(SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE, service.uiDiagnostics().get(0).code());
    }

    @Test
    void adapterBackedMainToolbarRegistrationSurvivesPluginAndScopeDualClose() throws Exception {
        CountingHost host = new CountingHost();
        DisposableScope scope = new DisposableScope();
        RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.demo",
            dev.turboism.ui.UiHostStateSource.DEFAULT,
            scope,
            StatusToolbarAdapterImpl.safeMode(),
            MainToolbarAdapterImpl.connected(host)
        );

        Registration registration = service.contributeMainToolbar(contribution("main.dual-close"));
        scope.register(registration);

        scope.close();

        assertEquals(1, host.closeCount);
    }

    private static MainToolbarRegistry.MainToolbarContribution contribution(final String id) {
        return new MainToolbarRegistry.MainToolbarContribution(
            id,
            "probe.action",
            "probe.label",
            "icons/probe.svg",
            "end",
            10
        );
    }

    private static final class RecordingHost implements MainToolbarAdapter.HostOperations {
        private final String hostVersion;
        private final EnumSet<MainToolbarAdapter.Capability> capabilities;
        private MainToolbarRegistry.MainToolbarContribution contribution;
        private int delegations;
        private boolean closed;

        private RecordingHost(final String hostVersion, final MainToolbarAdapter.Capability firstCapability) {
            this.hostVersion = hostVersion;
            this.capabilities = EnumSet.of(firstCapability);
        }

        private RecordingHost(final String hostVersion) {
            this.hostVersion = hostVersion;
            this.capabilities = EnumSet.noneOf(MainToolbarAdapter.Capability.class);
        }

        @Override
        public String hostVersion() {
            return hostVersion;
        }

        @Override
        public boolean supports(final MainToolbarAdapter.Capability capability) {
            return capabilities.contains(capability);
        }

        @Override
        public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
            this.contribution = contribution;
            delegations++;
            return () -> closed = true;
        }
    }

    private static final class FailingHost implements MainToolbarAdapter.HostOperations {
        private final AdapterHostException failure;

        private FailingHost(final AdapterHostException failure) {
            this.failure = failure;
        }

        @Override
        public String hostVersion() {
            return "5.3.2";
        }

        @Override
        public boolean supports(final MainToolbarAdapter.Capability capability) {
            return true;
        }

        @Override
        public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
            throw failure;
        }
    }

    private static final class CountingHost implements MainToolbarAdapter.HostOperations {
        private int closeCount;

        @Override
        public String hostVersion() {
            return "5.3.2";
        }

        @Override
        public boolean supports(final MainToolbarAdapter.Capability capability) {
            return true;
        }

        @Override
        public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
            return () -> closeCount++;
        }
    }
}
