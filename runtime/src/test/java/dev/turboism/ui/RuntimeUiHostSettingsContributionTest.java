package dev.turboism.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import dev.turboism.ui.settings.SettingsContributionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeUiHostSettingsContributionTest {

    @Test
    void uiHostPublishesToSharedStoreAndScopeCloseRevokesIt() throws Exception {
        final SettingsContributionStore store = new SettingsContributionStore();
        final DisposableScope scope = new DisposableScope();
        final RuntimeUiHostCapabilityService ui = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.one",
            UiHostStateSource.DEFAULT,
            scope,
            dev.turboism.adapter.ui.StatusToolbarAdapterImpl.safeMode(),
            dev.turboism.adapter.ui.UiSurfaceAdapterImpl.safeMode(),
            null,
            store
        );

        ui.contributeSettings(contribution());
        assertEquals("plugin.one", store.snapshot().get(0).contributions().get(0).pluginId());

        scope.close();
        assertTrue(store.snapshot().isEmpty());
    }

    @Test
    void uiHostRequiresTheSettingsContributionPermission() {
        final RuntimeUiHostCapabilityService ui = new RuntimeUiHostCapabilityService(
            (permission, operation) -> { throw new CubismPermissionException("denied"); },
            "plugin.one"
        );

        assertThrows(CubismPermissionException.class, () -> ui.contributeSettings(contribution()));
    }

    private static SettingsContribution contribution() {
        return new SettingsContribution(
            "sample",
            new SettingsTab("sample", "Sample"),
            new SettingsControl.Toggle(
                "enabled",
                "Enabled",
                SettingsBinding.of(() -> false, ignored -> { })
            )
        );
    }
}
