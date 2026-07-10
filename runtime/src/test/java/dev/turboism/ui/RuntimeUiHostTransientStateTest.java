package dev.turboism.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.StatusNotification;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeUiHostTransientStateTest {

    @Test
    void repeatedStatusAndSafeModeDiagnosticsReplaceByIdentity() {
        RuntimeUiHostCapabilityService service = service();

        for (int i = 0; i < 100; i++) {
            service.notifyStatus(new StatusNotification("same", "INFO", "value-" + i));
        }

        assertEquals(1, service.notifications().size());
        assertEquals("value-99", service.notifications().get(0).message());
        assertEquals(1, service.uiDiagnostics().size());
    }

    @Test
    void transientStatusStoreIsBounded() {
        RuntimeUiHostCapabilityService service = service();

        for (int i = 0; i < 100; i++) {
            service.notifyStatus(new StatusNotification("status-" + i, "INFO", "value"));
        }

        assertEquals(64, service.notifications().size());
    }

    @Test
    void confirmationRequestsDoNotBecomePersistentDialogRegistrations() {
        RuntimeUiHostCapabilityService service = service();

        service.confirmDialog(new DialogRequest("confirm", "Confirm", "Proceed?"));

        assertTrue(service.dialogs().isEmpty());
    }

    @Test
    void diagnosticsDedupeKeysIncludePluginId() {
        RuntimeUiHostCapabilityService first = service("plugin.a");
        RuntimeUiHostCapabilityService second = service("plugin.b");

        first.notifyStatus(new StatusNotification("status.shared", "INFO", "a"));
        second.notifyStatus(new StatusNotification("status.shared", "INFO", "b"));

        // each host instance is plugin-scoped; key identity must not collapse across plugins
        assertEquals(1, first.uiDiagnostics().size());
        assertEquals(1, second.uiDiagnostics().size());
        assertEquals(1, first.notifications().size());
        assertEquals(1, second.notifications().size());
    }

    private static RuntimeUiHostCapabilityService service() {
        return service("plugin.test");
    }

    private static RuntimeUiHostCapabilityService service(final String pluginId) {
        return new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            pluginId,
            UiHostStateSource.DEFAULT,
            new DisposableScope()
        );
    }
}
