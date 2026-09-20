package dev.turboism.sdk.cubism.edit;

/**
 * One entry of the parameter-structure tree returned by {@code GetParameterStructure}: either a
 * parameter leaf or a parameter group with children.
 */
public sealed interface EditParameterStructureEntry
        permits EditParameterNode, EditParameterGroupNode {}
