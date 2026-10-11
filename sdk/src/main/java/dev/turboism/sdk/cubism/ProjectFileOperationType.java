package dev.turboism.sdk.cubism;

import dev.turboism.sdk.CubismEditor;

/** File-content lifecycle operation exposed by the Cubism editor. */
@CubismEditor
public enum ProjectFileOperationType {
    CREATE,
    OPEN,
    SAVE,
    CLOSE
}
