# 025 read-only texture relation smoke

Test-only plugin, not a production feature or the complete 025 acceptance matrix.
It calls the public SDK on the UI thread, checks the exact runner-generated fixture
basename against `DocumentSnapshot.relativePath`, and checks a nonempty relation
graph, unique identities, current raw references and resolved model-image inputs.
The default mode never exports/imports/saves a model, opens an external editor, or creates Undo.

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
export/import, Undo, performance and persistence remain NOT_TESTED in the default mode.
Authoring write/Undo/persistence are not exercised by either mode.
Final host acceptance additionally requires the supervisor lifecycle's verified
identity, normal exit, unchanged fixture and safe cleanup; the result file alone
is insufficient.


## Native PSD export observation (separate opt-in)

`build.sh --export-observation` produces `build/psd-export-observation-host-probe.jar`
with file read/write permissions in its temporary manifest only. The default
relation probe retains model-read-only permissions. Both builds are SDK-only and
excluded from production packaging.

```sh
bash validation/texture-relations-host-probe/build.sh --export-observation
bash scripts/preview/run-psd-export-observation-host-validation.sh --dry-run
bash scripts/preview/run-psd-export-observation-host-validation.sh
```

The wrapper enables `turboism.validation.textures.exportObservation` in the isolated
queued host. The probe rechecks the fixture/document/model before calling the public
SDK export entry for the fixture's single raw image. It awaits completion off the EDT.
Native export writes only a newly allocated OS-temporary PSD; no source model save,
replacement, external editor launch or file deletion is requested.

PASS requires readable native export and matching observed structure, reported by
this intermediate implementation as `FAILED` without a file/revision capability.
The exact safe diagnostic is asserted; generic failure or UNAVAILABLE is not PASS.
This deliberately does **not** certify full PSD fidelity, successful public EXPORTED,
automatic replacement, Undo, reopen without the temporary PSD, or complete US2.
The authoritative lifecycle checks above remain mandatory.
