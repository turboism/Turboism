package dev.turboism.sdk.cubism.modeling;

import dev.turboism.sdk.Incubating;

/** Activation-bound services for ordinary modeling; no native handles or geometry writes. */
@Incubating
public interface ModelingToolContext {
    /** Returns this activation's unique runtime-owned point selection brush. */
    ModelingBrush selectionBrush();
}
