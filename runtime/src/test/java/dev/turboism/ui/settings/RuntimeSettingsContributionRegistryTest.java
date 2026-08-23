package dev.turboism.ui.settings;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import dev.turboism.sdk.ui.settings.SettingsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSettingsContributionRegistryTest {

    @Test
    void explicitIndexesWinAndMetadataMakesUnindexedOrderStable() {
        final SettingsContributionStore store = new SettingsContributionStore();
        final DisposableScope scopeB = new DisposableScope();
        final DisposableScope scopeA = new DisposableScope();
        final RuntimeSettingsRegistry pluginB = registry(store, "plugin.b", scopeB);
        final RuntimeSettingsRegistry pluginA = registry(store, "plugin.a", scopeA);

        pluginB.contribute(choice("zeta", "custom", "Custom", OptionalInt.empty()));
        pluginA.contribute(choice("alpha", "performance", "Performance", OptionalInt.empty()));
        pluginB.contribute(choice("first", "performance", "Performance", OptionalInt.of(-10)));
        pluginA.contribute(choice("beta", "custom", "Custom", OptionalInt.empty()));

        assertEquals(
            List.of("performance", "custom"),
            store.snapshot().stream().map(SettingsSnapshot.Tab::id).toList()
        );
        assertEquals(
            List.of("plugin.b:first", "plugin.a:alpha"),
            store.snapshot().get(0).contributions().stream()
                .map(value -> value.pluginId() + ":" + value.contribution().id())
                .toList()
        );
        assertEquals(
            List.of("plugin.a:beta", "plugin.b:zeta"),
            store.snapshot().get(1).contributions().stream()
                .map(value -> value.pluginId() + ":" + value.contribution().id())
                .toList()
        );
    }

    @Test
    void closingPluginScopeRevokesOnlyItsContributions() throws Exception {
        final SettingsContributionStore store = new SettingsContributionStore();
        final DisposableScope scope = new DisposableScope();
        final RuntimeSettingsRegistry registry = registry(store, "plugin.one", scope);

        registry.contribute(choice("value", "new-tab", "New Tab", OptionalInt.empty()));
        assertEquals(1, store.snapshot().size());

        scope.close();

        assertTrue(store.snapshot().isEmpty());
    }

    @Test
    void duplicateContributionIdentityIsRejectedAcrossOnePlugin() {
        final SettingsContributionStore store = new SettingsContributionStore();
        final RuntimeSettingsRegistry registry = registry(store, "plugin.one", new DisposableScope());
        registry.contribute(choice("same", "runtime", "Runtime", OptionalInt.empty()));

        assertThrows(IllegalArgumentException.class, () ->
            registry.contribute(choice("same", "other", "Other", OptionalInt.empty()))
        );
    }

    private static RuntimeSettingsRegistry registry(
        final SettingsContributionStore store,
        final String pluginId,
        final DisposableScope scope
    ) {
        return new RuntimeSettingsRegistry(
            store,
            pluginId,
            PermissionChecker.allowAll(),
            scope
        );
    }

    private static SettingsContribution choice(
        final String id,
        final String tabId,
        final String tabTitle,
        final OptionalInt index
    ) {
        return new SettingsContribution(
            id,
            new SettingsTab(
                tabId,
                tabTitle,
                "performance".equals(tabId) ? OptionalInt.of(200) : OptionalInt.empty()
            ),
            index,
            new SettingsControl.Choice(
                id,
                id,
                List.of(new SettingsControl.Option("value", "Value")),
                SettingsBinding.of(() -> "value", ignored -> { })
            )
        );
    }
}
