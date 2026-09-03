package dev.turboism.plugin.mcp;

/** Execution boundary applied once for a standalone MCP invocation. */
enum McpExecutionAffinity {
    DIRECT,
    UI_THREAD,
    COMPLETION_STAGE
}
