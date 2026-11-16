# mcp-host-probe

Host-side probe for the MCP interface.

`./build.sh` only compiles offline. A real run must use the `scripts/preview/host_validation.py prepare/submit/status` queue, and the Runner creates the isolated asset copy and task directory.

The validation bundle carries the test-only `McpGeometryRollbackProbe`; production MCP does not. The client supplies the three object/source/form identities it actually discovered through a bounded JSON request in the task, and the probe calls the typed ordinary setters on the EDT and throws on purpose after the first, second, and third confirmed write. Every attempt must receive the SDK `ROLLED_BACK` verdict, restore the complete authoring record exactly, and restore both history observers to their full state before the probe publishes the sanitized hashes and receipts.

The transaction host uses the native model binding generation, while the public history service uses the Editor UI lifecycle generation. The revision and binding ID also belong to their own observer. Never compare these counters or opaque IDs across domains. Document and model identity are verified separately, each observer must show equal before and after snapshots, and position and entry counts are compared across observers.

The rollback probe saves no file and exposes no production API for arbitrary paths, code, or fault control. On a timeout or an unproven restore, the client stops further MCP writes and uncertain cleanup, and never replays a request. It proves SDK transaction recovery from exceptions in the three real setters; it does not prove that an MCP disconnect, a host crash, or a cancellation has passed.

Geometry persistence validation reuses the exact-hash-gated native file flow that `McpTexturePersistenceProbe` already provides, with a separate `geometry-persistence` directory and its own request and result. After the three real MCP writes and the Undo/Redo validation, the probe saves the current modified state to a new task-local CMO3, closes and deserializes that copy again, and verifies the three complete SDK authoring records and every model binding exactly. It then reopens the original task copy, and the external MCP verifies the original rows. It never reuses history receipts from before the reopen and adds no production path or file-handle entry point. This does not prove that production `documents.save_as` or the not-yet-released `documents.open` has passed host acceptance.
