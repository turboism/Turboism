package dev.turboism.plugin.turboismwithfx;

/**
 * Security posture used when launching a managed or explicitly selected fx ACP process.
 *
 * <p>Stock fx currently exposes its native file, terminal, search, and fetch tools and does not
 * offer a supported ACP CLI switch that disables them. {@link #MCP_ONLY} therefore refuses to
 * start stock fx until a compatible launcher can prove the native tool set is disabled.
 * {@link #FX_NATIVE_TOOLS} is an explicit compatibility escape hatch, never the default.</p>
 */
enum FxSecurityMode {
    MCP_ONLY,
    FX_NATIVE_TOOLS
}
