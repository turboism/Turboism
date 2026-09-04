package dev.turboism.plugin.mcp;

/** Observable side-effect class used for MCP transaction admission. */
enum McpOperationEffect {
    READ,
    UNDOABLE_WRITE,
    HISTORY_CONTROL,
    EXTERNAL_SIDE_EFFECT,
    TRANSACTION_CONTROL
}
