package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/**
 * One entry of the parameter-structure tree returned by {@code GetParameterStructure}: either a
 * parameter leaf or a parameter group with children.
 */
@Incubating
@CubismEditor(from = "5.2.03", to = "5.3.99")
public sealed interface EditParameterStructureEntry permits EditParameterNode, EditParameterGroupNode {}
