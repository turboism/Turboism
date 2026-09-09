# 025 read-only texture relation smoke

Test-only plugin, not a production feature or the complete 025 acceptance matrix.
It calls the public SDK on the UI thread, checks the exact runner-generated fixture
basename against `DocumentSnapshot.relativePath`, and checks a nonempty relation
graph, unique identities, current raw references and resolved model-image inputs.
It never exports/imports/saves a model, opens an external editor, or creates Undo.

`filePath` is intentionally absent in the SDK; it is not a fixture identity check.
Only the sanitized basename of a runner-generated ASCII filename is compared.
The outer runner verifies the actual source/copy hashes and host identity.

## Build and offline check

```sh
./gradlew previewBundle --offline
bash validation/texture-relations-host-probe/test.sh
```

The script compiles against the current worktree SDK, runs assertion checks, and
ensures the probe is absent from production preview artifacts. Output is
`build/texture-relations-host-probe.jar`. No Gradle production packaging registers it.

## Exact-host execution

Read the current host-validation runbook and scheduling README first. Configure
the ignored `.env` (or `TURBOISM_ENV_FILE`) with the approved 5.3.02 fixture and
host dependency paths. Do not source arbitrary shell configuration.

```sh
bash scripts/preview/run-texture-relations-host-validation.sh --dry-run
bash scripts/preview/run-texture-relations-host-validation.sh
```

The thin wrapper uses the shared FIFO queue, official BAT, task CoW prefix, private
home and named fixture copy. It does not start a competing worker or bypass the
queue. The test plugin requires a task run ID and exits only its own test process
after publishing results; do not install it into a normal user profile.

`state/dev.turboism.validation.textures/relations-result.properties` contains
escaped structured results including run/document/model identities, binding,
counts, assertion and full exception trace on failure. PASS applies only to the
smoke assertion. Shared/multiple-input edge cases, right-click semantics,
export/import, Undo, performance and persistence remain NOT_TESTED. The read-only
probe marks authoring write/Undo/persistence NOT_APPLICABLE with a reason, not PASS.
Final host acceptance additionally requires the supervisor lifecycle's verified
identity, normal exit, unchanged fixture and safe cleanup; the result file alone
is insufficient.
