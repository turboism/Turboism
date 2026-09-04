# Turboism MCP exact-host validation bundle

This task-local bundle validates the production MCP plugin through its published credential-free
loopback Streamable HTTP connection file. The external client writes redacted evidence under the
task-scoped Turboism home, deletes its MCP session, and never runs against the golden Proton prefix
directly.

## Public protocol assertions

The raw stdlib HTTP client requires the exact public catalog introduced by the MCP read/write and
authoring-transaction cutover:

- `turboism.glues.read` and `turboism.glues.write`
- `turboism.history.read`, `turboism.history.undo`, and `turboism.history.redo`
- `turboism.transaction.execute` and `turboism.capabilities.read`
- the three explicitly recorded temporary `*.apply` compatibility exceptions
- no public `turboism.history.move`

It also verifies that every tool declares an output schema, that the capability ledger exposes
exact per-operation versions and Undo/transaction metadata, and that the bundled SDK coverage
ledger and temporary-exception set are available through `turboism.capabilities.read`.

## Reversible Glue authoring matrix

The copied validation model must contain at least one Glue whose two ArtMesh endpoints differ. The
client then proves, through the public MCP transport only:

1. Glue list/get projection and the exact active provider version.
2. `create` and `delete` are explicit `RUNTIME_UNAVAILABLE` operations with a verified-provider
   gap, and do not modify model state or history.
3. A stale expected-state token is rejected before mutation.
4. A same-value intensity write returns `NO_CHANGE` and creates no Undo entry.
5. A standalone intensity write produces fresh readback plus a stable history-entry identity and
   transaction identity.
6. Guarded Undo restores the original value, guarded Redo reapplies it, and a final guarded Undo
   restores the model again.
7. One `turboism.transaction.execute` request performs `set_name`, `set_intensity`,
   `set_drawable_a`, `set_drawable_b`, and `set_id`, with child values linked by `$ref`; all writes
   commit as exactly one native history position.
8. The transaction receipt's `historyEntryId` and `transactionId` match the identities projected by
   `turboism.history.read`.
9. Undo/Redo apply to the whole transaction as one unit, followed by final restoration.
10. A later transaction whose second write targets a missing ArtMesh reports `ROLLED_BACK`, restores
    its earlier name change, and leaves native history unchanged.

If any stage fails, the client attempts direct field-by-field restoration on the task-local model
copy and reports both the primary and cleanup failure classes without persisting connection data.

## Other validation

The client still performs the established resource, prompt, diagnostic-sanitization, parameter
read/write/readback, Editor command, session deletion, and unknown-resource checks. The temporary
parameter `*.apply` write is restored with the new guarded `turboism.history.undo` endpoint rather
than arbitrary cursor movement.

A separate `mcp-standard-client-validation.js` probe performs catalog, resource, capability-ledger,
and guarded-history interoperability checks with the official `@modelcontextprotocol/sdk`
Streamable HTTP client. Its dependencies are intentionally not bundled into the production plugin
or this validation package.

## Host requirements and evidence meaning

The runner must use an exact reviewed Cubism installation launched through the official
`CubismEditor5.bat`, a task-scoped copy-on-write Proton prefix, and a task-local copy of the fixture.
Evidence archives explicitly exclude MCP connection files. A passing synthetic JVM test is not
exact-host readiness; readiness is recorded only after this transport
matrix passes on the named reviewed Editor artifact and the copied fixture is proven restored.
