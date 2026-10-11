package dev.turboism.sdk.cubism.modeling;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/** Activation-bound services for ordinary modeling; no native handles or geometry writes. */
@Incubating
@CubismEditor(from = "5.2.00")
public interface ModelingToolContext {
    /** Returns this activation's unique runtime-owned point selection brush. */
    ModelingBrush selectionBrush();
}
