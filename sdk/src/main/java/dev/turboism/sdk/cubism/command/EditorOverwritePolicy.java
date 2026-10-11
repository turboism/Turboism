package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.CubismEditor;

/** Explicit destination collision policy for Editor file writes. */
@CubismEditor
public enum EditorOverwritePolicy {
    REJECT_EXISTING,
    REPLACE_EXISTING
}
