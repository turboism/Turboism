# Turboism MCP exact-host validation bundle

This task-local bundle validates the production MCP plugin through its published
loopback Streamable HTTP connection file, carrying the owner-only `mcp.token`
bearer for mutating calls. The external client writes redacted evidence under the
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

Substitute returned IDs. All 20 exact installed versions (`5000`–`5006`, `5100`–`5103`,
`5200`–`5203`, `5300`–`5304`) share the same single host slot. Wrapper admission is
not evidence that the client assertions have passed on each version.
The queue freezes the canonical stdlib client and pins the preparing Python executable;
Runner executes the copied client with `-I`, without inherited proxies or redirects.
Arbitrary custom client scripts and extra hooks remain rejected. Preparation and wrapper
`--dry-run` do not launch Cubism and are not a readiness verdict.

## Public protocol assertions

The raw stdlib HTTP client requires the exact current public catalog (80 tools,
15 resources, 2 templates and 8 prompts), including the MCP read/write and
authoring-transaction surfaces:

- `turboism.glues.read` and `turboism.glues.write`
- `turboism.textures.read` and `turboism.textures.write`
- `turboism.history.read`, `turboism.history.undo`, and `turboism.history.redo`
- `turboism.transaction.execute` and `turboism.capabilities.read`
- the three explicitly recorded temporary `*.apply` compatibility exceptions
- no public `turboism.history.move`

It also verifies that every tool declares an output schema, that the capability ledger exposes
exact per-operation versions and Undo/transaction metadata, and that the bundled SDK coverage
ledger and temporary-exception set are available through `turboism.capabilities.read`.

## Native authoring and rendering checks

The client requires a readable ArtMesh, Warp and Rotation stored snapshot. It discovers
objects from the real hierarchy and checks at most 32 candidates per kind; rejected
candidates remain in the successful evidence, and no readable candidate is a failure.
Every page must retain one snapshot ID, source GUID, document/model/history binding,
contiguous offsets and total row count. Rereading the first page must return identical
content. Reads must preserve history and parameter pose. This does not establish
stored-form write admission, Undo/Redo or persistence of geometry edits.

The renderer check rejects a stale pose hash, requires JSON plus a typed PNG from
`cubism.native.renderPixels`, verifies actual PNG dimensions, byte length and SHA-256,
and rechecks document/history/pose without moving parameters. The task home receives
`state/mcp-native-render.{png,json}` only after those checks. This verifies the MCP
image result; it does not prove ACP window display, vision-model consumption or art quality.
Workspace model IDs are opaque session IDs; typed Editor model IDs are native source
GUIDs. Their strings need not match. Document/model snapshot identities must still
agree, and typed handles must validate their current native binding. Version/lifecycle
proxies preserve the closed Deformer→Warp/Rotation subtype union so `all`/`find`
results retain their typed stored-authoring interfaces and guards.

## Three-kind ordinary stored-write matrix

After the original-tip Glue matrix, the client re-discovers existing independent
non-default Mesh/Warp/Rotation forms. An unbound Rotation fixture is prepared using
the production typed binding initializer and a real discovered parameter GUID and
range; that preparation has its own guarded Undo and is fully removed at the end.
The client sends one ordinary keyform batch with one small exact-float delta for
each kind, checks one native Undo position and every stored row on all three targets,
then performs guarded Undo/Redo/final Undo and restores the fixture preparation.
Float fields compare native float32 bits; IDs and integer topology fields stay exact.
No receipt means no blind Undo or write replay. Failures and cleanup errors remain
separate in `state/mcp-ordinary-geometry.json`. This is a primitive fixture check,
not posed image quality, geometry save/reopen or head-rig acceptance.

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

The standard variant resolves its fixture in order:
`TURBOISM_HOST_VALIDATION_FIXTURE_MCP`, then `TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK` (the
clipmask fixture is also Glue-bearing), then the shared versioned selection. The free-fixture
variant ignores both overrides and keeps the versioned selection for its FREE-limit preparation.

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
restored metadata is a failure, not a skipped assertion. Metadata equality alone does not establish
pixel or persistence correctness.

Before the first texture mutation, a separate run-correlated test-only probe captures native
layer-input GUID/affine mappings and full raw-layer ARGB hashes. When the pristine fixture has
pixels but no input connections, it creates one recorded connection between an existing layer and
model image using the native LayerInput Undo factory on the task copy. It retains a separate pristine
fingerprint and requires the seeded connection to survive deletion/Undo/Redo restoration. It then
performs raw-image deletion and single-root Undo/Redo/final Undo, and compares the full native state.
It then exercises all five operation kinds, saves to a new fixed file under the same task home,
closes the owned document, reopens the saved file, compares persisted state, and reopens the original
unchanged task fixture. The client requires both `nativeLayerPixelUndoRedo=PASS` and `saveReopen=PASS`,
matching fingerprints and unchanged-fixture evidence before it can publish overall PASS. The final
report records `texturePersistence=FIVE_OPERATION_KINDS_SAVED_AND_REOPENED` only after those checks.

The helper is compiled only into the validation probe. SDK methods perform texture writes/history;
additional native inspection and document save/close/open are restricted to the exact canonical
verification-record JAR digest and current task files. No production MCP file endpoint, unrestricted
reflection hook, or new host-class dependency is exposed. Unknown ownership, symlinks, a preexisting
output, incomplete evidence, or a native timeout fail closed. Saved outputs remain task evidence.
This checks raw-layer pixels and input reconstruction, not final rendered canvas appearance.

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
uses focused Alt+F4; other exact installed versions use a task-bound synthetic close event.
Each version still requires supervisor evidence of a normal exit. MCP reserves
`display-input`. The in-process helper verifies that the selected task window is active before
sending the native gesture; failure to acquire focus stops the close. No external desktop-focus
poller is admitted by the MCP client's dependency inventory.

The helper selects exactly one window matching the copied fixture, never an arbitrary visible
window. It handles only an unambiguous save-confirmation dialog owned by that window and naming
that fixture. Unknown/foreign dialogs fail closed. There is no `System.exit` or process-kill
fallback in the probe. It is packaged only in the validation bundle, not in production plugins.

With `-Dturboism.validation.startFree=true`, the task JVM also handles the license
startup dialog — the user-authorized "Start PRO Version Free Trial" entry is
preferred, "Start as FREE version" is the fallback — plus the "Would you like
to start the Trial?" Yes confirmation, the activation-success notice, the
FREE startup/welcome dialogs, the newer-model warning
(Load or Yes), and the update download prompt (Cancel). Actions and observed
prompt text are logged. Unrelated confirmations are left untouched; purchase,
activation-key and cancel entries are never clicked and no license keys are
entered.
The exact FREE parameter-limit information is acknowledged with OK and logged; its cancelled
operation is not relabeled as successful. The helper has a separate lifecycle-bound thread so
texture save/reopen waits do not prevent it from handling these known modal prompts on the EDT.

## Optional production SDK owned-MOC checks

Build the probe with `TURBOISM_VALIDATION_MOC_FIXTURE` pointing to the exact official Haru
fixture pinned by `validation/mcp-host-probe/moc-fixture-source.json`. Only the reviewed SHA256
is accepted. The binary is packaged solely into the local validation artifact, with its source
notice, and is not committed or included in production plugins.

Set `-Dturboism.validation.ownedMoc=true` and
`-Dturboism.validation.expectedCoreVersion=<observed-native-tuple>` for the exact target.
`state/core-owned-moc-result.properties` separately records production SDK version/capabilities,
MOC diagnostics, two independent model instances, all structural getters, copied-list immutability,
evaluation, explicit child-then-parent release, parent cascade release and use-after-close rejection. This verifies
plugin-owned MOC models, not an Editor-to-Core bridge. Empty collections remain explicit limitations.
Overall MCP PASS does not substitute for this
separate result, and this result does not substitute for the complete MCP or framework audit.

A client `status=PASS` is still insufficient: the normal-exit and outside-supervisor containment
proofs must also pass. `wrapper.cleanup` records whether native exit evidence was observed or the
launcher timed out; a timeout is not relabeled as a successful native exit.

## Host requirements and evidence meaning

The runner must use an exact reviewed Cubism installation launched through the official
`CubismEditor5.bat`, a task-scoped copy-on-write Proton prefix, and a task-local copy of the fixture.
Evidence archives explicitly exclude MCP connection files. A passing synthetic JVM test is not
exact-host readiness; readiness is recorded only after this transport
matrix passes on the named reviewed Editor artifact and the copied fixture is proven restored.

## Authorized CMO3 OPEN lane

Only exact 5.3.03 runs the production files.request_grant → documents.open → file_result chain on the already-open task fixture. The validation-only responder approves one exact READ purpose and cmo3-only chooser after canonical task-path ownership checks. It neither injects grants nor gives MCP a raw path. The client checks one-use consumption, retained result, replay, and unchanged full document inventory/authoring state. Other exact versions record NOT_ADMITTED_EXACT_VERSION.

Evidence `state/mcp-authorized-open.json` explicitly records testChooserApproval=true, ALREADY_OPEN_TASK_COPY, firstOpenVerified=false and persistence/reopenVerified=false. Success can mean focus of an existing tab; it is not first-load, file-identity or production SAVE_AS persistence acceptance.

## Production SAVE_AS persistence lane

On reviewed exact 5.3.03, the client records the original full three-kind geometry and SDK-visible bindings, initializes the previously unbound fixture Rotation through production MCP, writes an actual ordinary form for each of Mesh/Warp/Rotation, and compares before/modified native pose pixels. A task-only chooser responder grants one WRITE to a new CMO3 under the owned state/authorized-save directory; existing files and symlinked targets/parents fail closed.

The save itself uses production documents.save_as and a fresh six-field expectedState. The test probe then only reads/owns/closes the task document; production READ grant/documents.open loads the saved output. Full stored rows, native SDK records, all SDK-visible bindings, saved bytes hash and modified-versus-reloaded pose PNG hashes must match. The probe closes that exact saved document, production OPEN restores the original fixture, and original geometry/bindings/file hashes must match the baseline. No old Undo receipt is valid across reopening. Uncertain responses query file_result without redispatch; unknown outcomes stop further file/cleanup writes.

Evidence is state/mcp-production-save-open.json plus mcp-production-save-{baseline-result,closed,result,restore-closed,restore-result}.json and the three sets of pose PNGs. This tests three actual primitives and a binding change on the fixed fixture; it is not Hiyori art acceptance or proof of all-host persistence.

## Read-only authoring inspection

`mcp:5303@inspect` reuses the standard managed queue and fixed stdlib client, with
`-Dturboism.validation.readOnlyInspection=true`. The runner forwards the closed
`--read-only-inspection` flag directly to that pinned client. Missing profile files
and run-label names cannot switch the mode. The client rejects all tool calls except
capabilities/history/state/capture and three authoring readers before transport;
file grants, editor commands, parameter/geometry/history writes are not allowed.
The probe disables its file chooser responder and all optional texture/geometry/
file persistence write hooks in this lane. Standard preparation still opens only
the task's copied fixture, and terminal handling uses the normal task-bound close.

Inspection discovers the catalog, actual document, bounded readable Mesh/Warp/
Rotation candidates and their complete immutable snapshot pages, and native PNG.
History and render pose/state must remain unchanged. Full snapshots and images stay
in private task evidence; `mcp-read-only-inspection.json` contains IDs, counts and
hashes, with no geometry or credentials. This is not an editing, head-semantic or
art-quality verdict. Supply the exact fixture and hash through the standard local
configuration overrides; do not use the Glue mutation lane on a new official model.


## Head-edit response loss reconciliation

The exact 5.3.03 `mcp:5303@head-edit` lane now discards the first keyform-write HTTP
response after the complete body is read but before JSON-RPC decoding. The workflow
receives no write status/body. It queries `authoring.keyforms.result` twice with the
same operation ID and requires a stable APPLIED receipt before continuing with full
three-kind stored-form readback, a single Undo/Redo, native renders and final restore.
It never resends the write. Missing/unknown results, a different operation identity,
readback warnings or unstable receipts stop this case without guessing an Undo handle.

`state/mcp-ordinary-geometry.json` records the injection boundary and result-query
counts. This is a client receive-boundary fault with the same live MCP session;
it does not test a server crash, connection loss before the server commits, fresh-session
recovery or host cancellation. The head-save/head-qa and standard fixture lanes retain
their previous transport behavior. No external agent/model service is involved.
