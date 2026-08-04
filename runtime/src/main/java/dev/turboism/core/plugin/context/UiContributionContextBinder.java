package dev.turboism.core.plugin.context;

import dev.turboism.core.menu.RuntimeMenuRegistry;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.context.RuntimeContextMenuRegistry;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.filter.RuntimePaletteFilterRegistry;
import dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry;
import dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry;

final class UiContributionContextBinder {

    private UiContributionContextBinder() {
    }

    static void bind(
        final MenuRegistry menus,
        final MainToolbarRegistry mainToolbar,
        final PaletteToolbarRegistry paletteToolbar,
        final PaletteFilterRegistry paletteFilter,
        final ContextMenuRegistry contextMenu,
        final EditorUiContributionAuthority authority
    ) {
        if (menus instanceof RuntimeMenuRegistry runtimeMenus) {
            runtimeMenus.bindContributionAuthority(authority);
        }
        if (mainToolbar instanceof RuntimeMainToolbarRegistry runtimeMainToolbar) {
            runtimeMainToolbar.bindContributionAuthority(authority);
        }
        if (paletteToolbar instanceof RuntimePaletteToolbarRegistry runtimePaletteToolbar) {
            runtimePaletteToolbar.bindContributionAuthority(authority);
        }
        if (paletteFilter instanceof RuntimePaletteFilterRegistry runtimePaletteFilter) {
            runtimePaletteFilter.bindContributionAuthority(authority);
        }
        if (contextMenu instanceof RuntimeContextMenuRegistry runtimeContextMenu) {
            runtimeContextMenu.bindContributionAuthority(authority);
        }
    }
}
