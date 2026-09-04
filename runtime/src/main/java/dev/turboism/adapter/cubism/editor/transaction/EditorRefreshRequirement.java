package dev.turboism.adapter.cubism.editor.transaction;

/** Coalesced Editor refresh or persistence work requested by changed authoring contributions. */
public enum EditorRefreshRequirement {
    /** Re-evaluate model instances once after all changed writes. */
    MODEL_INSTANCES,
    /** Refresh the native parameter palette. */
    PARAMETER_PALETTE,
    /** Refresh the native part palette. */
    PART_PALETTE,
    /** Refresh the native deformer palette. */
    DEFORMER_PALETTE,
    /** Refresh the active Inspector projection. */
    INSPECTOR,
    /** Repaint the model canvas. */
    CANVAS,
    /** Mark the active modeling document dirty once. */
    MARK_DIRTY
}
