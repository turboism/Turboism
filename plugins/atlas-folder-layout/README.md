---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.texture-atlas-folders
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: texture-atlas, packing, auto-layout
turboismApi: "[0.67.1,1.0.0)"
requiresCubism: true
interface: swing
---

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

<!-- compatibility:begin -->
**Cubism Editor version support (generated):** all reviewed versions (5.0.00–5.0.06, 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04; 20 exact versions).
Full feature set (reviewed versions): 5.1.00–5.1.03, 5.2.00–5.2.03, 5.3.00–5.3.04.
- Top menu integration not on 5.0.00–5.0.06（earlier Cubism versions lack the feature entirely）
Full matrix: [compatibility matrix](../../compatibility/cubism/plugins.md)
<!-- compatibility:end -->
