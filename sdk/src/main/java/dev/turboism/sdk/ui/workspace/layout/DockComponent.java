package dev.turboism.sdk.ui.workspace.layout;

import dev.turboism.sdk.PreviewApi;

/**
 * A node of the current workspace dock layout tree.
 *
 * <p>The tree is a Turboism-owned immutable snapshot of the Cubism main-workspace dock
 * structure: {@link SplitDock} branches are split containers and {@link PaletteDock} leaves
 * are palette boxes holding ordered tabs. Unknown host component types (for example the
 * canvas contents box) are omitted, so a palette absent from the snapshot only means it is
 * not docked in the main workspace tree; it does not imply the palette is closed.</p>
 *
 * <p>This is a read-only view. No write, selection, or placement information is carried;
 * those belong to later additive capabilities.</p>
 */
@PreviewApi
public sealed interface DockComponent permits SplitDock, PaletteDock {
}
