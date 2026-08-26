# Turboism MCP exact-host validation bundle

This task-local bundle validates the production MCP plugin through its published loopback
Streamable HTTP connection file. The external client keeps the bearer secret in process memory,
records only redacted assertions, deletes its session, and writes the terminal result under the
task-scoped Turboism home. It performs one parameter write, verifies resource readback, and uses
native guarded history movement to restore the original value. The validation probe then requests
a normal Cubism window close. Evidence archives explicitly exclude MCP connection and temporary
connection files.

A separate `mcp-standard-client-validation.js` probe is available for interoperability checks with
the official `@modelcontextprotocol/sdk` Streamable HTTP client. Its dependencies are intentionally
not bundled into the production plugin or the exact-host validation package.

The runner must use an exact reviewed Cubism installation through the official `CubismEditor5.bat`
and a task-scoped CoW Proton prefix. Never run this bundle against the golden prefix directly.
