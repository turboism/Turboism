package dev.turboism.ui.menu;

import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopMenuContributionProviderTest {

    @Test
    void materializesPluginOwnedTopLevelAndNestedMenusInDeterministicOrderAndRoutesAction() {
        RecordingHost host = new RecordingHost();
        List<String> actions = new ArrayList<>();
        TopMenuContributionProvider provider = new TopMenuContributionProvider(
            admission(7),
            host,
            (pluginId, actionId) -> actions.add(pluginId + ":" + actionId)
        );

        Registration registration = provider.apply(7, List.of(
            contribution("plugin-a", "png", "My Tools/Export/PNG", 20),
            contribution("plugin-b", "run", "Workspace/Run", 0),
            contribution("plugin-a", "psd", "My Tools/Import/PSD", 10)
        ));

        assertEquals(List.of("Workspace", "My Tools"), host.installed.stream()
            .map(TopMenuDescriptor::label)
            .toList());
        TopMenuDescriptor myTools = host.installed.get(1);
        assertTrue(myTools.menuId().contains("plugin-a"));
        assertEquals(
            List.of("Import/PSD", "Export/PNG"),
            myTools.items().stream()
                .map(item -> String.join("/", item.submenuPath()) + "/" + item.label())
                .toList()
        );

        host.actions.get(1).accept(myTools.items().get(1));
        assertEquals(List.of("plugin-a:action.png"), actions);

        registration.close();
        assertEquals(2, host.closeCount);
    }

    @Test
    void rebuildReplacesTheSingleOwnedMenuWithoutDuplicates() {
        RecordingHost host = new RecordingHost();
        TopMenuContributionProvider provider = new TopMenuContributionProvider(
            admission(7),
            host,
            (pluginId, actionId) -> { }
        );

        Registration registration = provider.apply(7, List.of(
            contribution("plugin-a", "settings", "Turboism/Settings", 0)
        ));
        host.rebuild.run();

        assertEquals(2, host.installed.size());
        assertEquals(1, host.closeCount);
        registration.close();
        assertEquals(2, host.closeCount);
    }

    @Test
    void staleAdmissionFailsClosedBeforeHostMutation() {
        RecordingHost host = new RecordingHost();
        TopMenuContributionProvider provider = new TopMenuContributionProvider(
            admission(7),
            host,
            (pluginId, actionId) -> { }
        );

        assertThrows(
            IllegalStateException.class,
            () -> provider.apply(8, List.of(
                contribution("plugin-a", "settings", "Turboism/Settings", 0)
            ))
        );
        assertEquals(List.of(), host.installed);
    }

    @Test
    void pathWithoutTopLevelAndLeafFailsBeforeHostMutation() {
        RecordingHost host = new RecordingHost();
        TopMenuContributionProvider provider = new TopMenuContributionProvider(
            admission(7),
            host,
            (pluginId, actionId) -> { }
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> provider.apply(7, List.of(
                contribution("plugin-a", "broken", "OnlyRoot", 0)
            ))
        );
        assertEquals(List.of(), host.installed);
    }

    @Test
    void reservedTurboismRootMergesAcrossPluginsIntoOneMenuWhileItemsRouteToTheirOwners() {
        final RecordingHost host = new RecordingHost();
        final List<String> actions = new ArrayList<>();
        final TopMenuContributionProvider provider = new TopMenuContributionProvider(
            admission(7),
            host,
            (pluginId, actionId) -> actions.add(pluginId + ":" + actionId)
        );

        final Registration registration = provider.apply(7, List.of(
            contribution("plugin-a", "settings", "Turboism/Settings", 10),
            contribution("plugin-b", "monitor", "Turboism/Performance Monitor", 20)
        ));

        assertEquals(
            List.of("Turboism"),
            host.installed.stream().map(TopMenuDescriptor::label).toList(),
            "the reserved Turboism root must materialize as ONE top-level menu"
        );
        final TopMenuDescriptor turboism = host.installed.get(0);
        assertTrue(turboism.menuId().contains("shared"),
            "the merged root must use the shared menu-id owner");
        assertEquals(
            List.of("Settings", "Performance Monitor"),
            turboism.items().stream().map(TopMenuItemDescriptor::label).toList()
        );

        host.actions.get(0).accept(turboism.items().get(0));
        host.actions.get(0).accept(turboism.items().get(1));
        assertEquals(
            List.of("plugin-a:action.settings", "plugin-b:action.monitor"),
            actions,
            "merged items must route with their originating plugin id"
        );

        registration.close();
        assertEquals(1, host.closeCount);
    }

    @Test
    void sameLabelRootsFromUnrelatedPluginsRemainPluginOwned() {
        final RecordingHost host = new RecordingHost();
        final TopMenuContributionProvider provider = new TopMenuContributionProvider(
            admission(7),
            host,
            (pluginId, actionId) -> { }
        );

        final Registration registration = provider.apply(7, List.of(
            contribution("plugin-a", "export", "File/Export", 0),
            contribution("plugin-b", "import", "File/Import", 0)
        ));

        assertEquals(
            List.of("File", "File"),
            host.installed.stream().map(TopMenuDescriptor::label).toList(),
            "non-reserved roots must stay plugin-owned even when labels collide"
        );
        assertTrue(host.installed.get(0).menuId().contains("plugin-a"));
        assertTrue(host.installed.get(1).menuId().contains("plugin-b"));

        registration.close();
        assertEquals(2, host.closeCount);
    }

    private static EditorUiContribution<MenuRegistry.MenuContribution> contribution(
        final String pluginId,
        final String contributionId,
        final String menuPath,
        final int order
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.MENU, contributionId),
            order,
            new MenuRegistry.MenuContribution() {
                @Override public String menuPath() { return menuPath; }
                @Override public String actionId() { return "action." + contributionId; }
                @Override public int order() { return order; }
            }
        );
    }

    private static EditorUiProviderAdmission admission(final long generation) {
        return EditorUiProviderAdmission.admitted(
            EditorUiFamily.MENU,
            generation,
            new EditorUiProviderAdmission.VerificationEvidence(
                "5.3.02",
                42,
                "a".repeat(64),
                "adapter.editor-ui.top-menu",
                "b".repeat(64)
            )
        );
    }

    private static final class RecordingHost implements TopMenuHostOperations {
        private final List<TopMenuDescriptor> installed = new ArrayList<>();
        private final List<Consumer<TopMenuItemDescriptor>> actions = new ArrayList<>();
        private int closeCount;
        private Runnable rebuild = () -> { };

        @Override
        public Registration addMenu(
            final TopMenuDescriptor menu,
            final Consumer<TopMenuItemDescriptor> action
        ) {
            installed.add(menu);
            actions.add(action);
            return () -> closeCount++;
        }

        @Override
        public Registration onRebuild(final Runnable reconcile) {
            rebuild = reconcile;
            return () -> rebuild = () -> { };
        }
    }
}
