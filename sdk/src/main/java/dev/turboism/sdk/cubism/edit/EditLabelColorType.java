package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/** Palette-entry label colors understood by the editor, matching the official enumeration. */
@Incubating
@CubismEditor(from = "5.2.03", to = "5.3.99")
public enum EditLabelColorType {
    UNDEFINED,
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    BLUE,
    PURPLE,
    GRAY,
    CUSTOM
}
