package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
/**
 * One entry of the parameter-structure tree returned by {@code GetParameterStructure}: either a
 * parameter leaf or a parameter group with children.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public sealed interface EditParameterStructureEntry
        permits EditParameterNode, EditParameterGroupNode {}
