# Turboism MCP exact-host validation bundle

This task-local bundle validates the production MCP plugin through its published credential-free
loopback Streamable HTTP connection file. The external client writes redacted evidence under the
task-scoped Turboism home, deletes its MCP session, and never runs against the golden Proton prefix
directly.

## Managed local execution

Build `previewBundle` and `:plugins:mcp:jar`, then run `validation/mcp-host-probe/build.sh`
and `scripts/preview/package-windows-mcp-validation.sh`. Use the existing ignored `.env`
fixture and exact-host settings, or set `TURBOISM_ENV_FILE` explicitly for an isolated worktree.

```bash
python3 scripts/preview/host_validation.py plan mcp:5302
python3 scripts/preview/host_validation.py prepare mcp:5302 --run-label mcp-audit
python3 scripts/preview/host_validation.py submit --prepared PREPARED_ID --request-id UNIQUE_REQUEST --json
python3 scripts/preview/host_validation.py wait JOB_ID
```

Substitute returned IDs. Versions `5203`, `5302`, and `5303` share the same single host slot.
The queue freezes the canonical stdlib client and pins the preparing Python executable;
Runner executes the copied client with `-I`, without inherited proxies or redirects.
Arbitrary custom client scripts and extra hooks remain rejected. Preparation and wrapper
`--dry-run` do not launch Cubism and are not a readiness verdict.

## Public protocol assertions

The raw stdlib HTTP client requires the exact public catalog introduced by the MCP read/write and
authoring-transaction cutover:

- `turboism.glues.read` and `turboism.glues.write`
- `turboism.textures.read` and `turboism.textures.write`
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

## Audited adapter regressions

The client additionally rejects eleven malformed batch / legacy inversion / JSON-RPC ID
requests through the real HTTP transport, then verifies unchanged native history, hierarchy
and parameter state. It executes the explicitly scoped `invert_all_bindings` operation on a
normal keyform-bound target, checks its complete affected-parameter receipt, and performs
native Undo / Redo / final Undo with metadata and history-position restoration. These checks
verify the MCP scope/receipt and native history contracts, not visual keyform-pose geometry.
The parameter value matrix also checks confirmed non-retryable receipts and Undo/Redo/restore.
Injected readback failures remain deterministic unit-test evidence, not fabricated host faults.

## Reversible texture-library authoring matrix

The copied fixture must contain at least one raw image and one model image. The texture matrix
runs after the original Glue/history-tip checks and existing binding/parameter matrices. It uses
`turboism.textures.read`'s `state` object as `expectedState`; the separate `stateToken` string is
only a read correlation identifier.

The matrix rejects stale state, missing or false deletion confirmation, zero atlas dimensions,
and unknown targets without changing the library or history. It then exercises each of the five
texture-library operations, requires a confirmed non-retryable write receipt and exactly one native
Undo position, and checks full metadata equality after Undo, Redo, and final restoration. Generated
atlas IDs must be present in the write receipt. If the fixture has no atlas, a task-local atlas is
created for the remove-atlas cycle and restored afterward. Raw-image removal must preserve the
other raw images, model images, ArtMesh identities, and atlas metadata; the selected raw-image
identity alone disappears and returns on Undo.

Every cycle attempts guarded cleanup at only the expected native history position. It never jumps
to an arbitrary history cursor or changes an original model. An unexpected position or mismatched
restored metadata is a failure, not a skipped assertion. The result records
`texturePersistence=NOT_TESTED_IN_REVERSIBLE_HTTP_MATRIX`: this matrix does not save/reopen a model
and must not be reported as texture persistence acceptance. Native pixel/pose equivalence and
internal layer-input reconstruction need their own observations beyond metadata equality.

## Other validation

The client still performs the established resource, prompt, diagnostic-sanitization, parameter
read/write/readback, Editor command, session deletion, and unknown-resource checks. The temporary
parameter `*.apply` write is restored with the new guarded `turboism.history.undo` endpoint rather
than arbitrary cursor movement.

A separate `mcp-standard-client-validation.js` probe performs catalog, resource, capability-ledger,
and guarded-history interoperability checks with the official `@modelcontextprotocol/sdk`
Streamable HTTP client. Its dependencies are intentionally not bundled into the production plugin
or this validation package.

## Task-owned normal close

The lifecycle probe consumes only a terminal result whose `runId` matches the current task.
After the client finishes, it reuses the tested native UI close helper: the exact 5302 route
uses focused Alt+F4; 5203 and 5303 use their verified synthetic close event. MCP reserves
`display-input`. The in-process helper verifies that the selected task window is active before
sending the native gesture; failure to acquire focus stops the close. No external desktop-focus
poller is admitted by the MCP client's dependency inventory.

The helper selects exactly one window matching the copied fixture, never an arbitrary visible
window. It handles only an unambiguous save-confirmation dialog owned by that window and naming
that fixture. Unknown/foreign dialogs fail closed. There is no `System.exit` or process-kill
fallback in the probe. It is packaged only in the validation bundle, not in production plugins.

A client `status=PASS` is still insufficient: the normal-exit and outside-supervisor containment
proofs must also pass. `wrapper.cleanup` records whether native exit evidence was observed or the
launcher timed out; a timeout is not relabeled as a successful native exit.

## Host requirements and evidence meaning

The runner must use an exact reviewed Cubism installation launched through the official
`CubismEditor5.bat`, a task-scoped copy-on-write Proton prefix, and a task-local copy of the fixture.
Evidence archives explicitly exclude MCP connection files. A passing synthetic JVM test is not
exact-host readiness; readiness is recorded only after this transport
matrix passes on the named reviewed Editor artifact and the copied fixture is proven restored.
