# Folder Atlas Layout

Independent one-click texture-atlas authoring plugin. Depends only on the Turboism SDK;
packing algorithms are discovered from the live registry and can be installed or removed separately.

Open **Turboism → Arrange Atlas by Part Folders**. Choose maximum edge
1024 / 2048 / 4096 / 8192, algorithm, margin, spacing and automatic page sizing.
Group by top-level Part folders, recursively divide overflowing folders, preserve source pixel sizes,
and apply the complete layout as one undoable operation. Page order follows the Parts tree.

Enable at least one complete-atlas algorithm plugin (for example MaxRects-BSSF or Dalsoo).
Current-page-only algorithms remain visible but disabled for this workflow.
The menu belongs to this plugin and remains present without MaxRects installed.
