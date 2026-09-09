# Cubism history manager exact-host probes

Manual-test-only probes for exact Cubism 5.2.03 and 5.3.02. The bundle is not a
readiness claim by itself; the current host-validation manager, exact identity,
fixture, CoW-prefix, official-BAT, normal-exit and cleanup gates remain required.

## Capabilities

- `history-validation-probe.jar` is the standalone, read-only manager probe. It
  samples the native Undo managers and the Runtime SDK projection on the same
  EDT, using the existing allowlisted classes/selectors and bounds. It owns
  `history-probe.jsonl` in its own plugin data directory.
- `history-seed-validation-probe.jar` is the primary semantic result. It keeps
  every existing SDK write, navigation, grouping, semantic-detail and
  restoration assertion, and writes its own `history-seed.jsonl`. It directly
  reuses the manager probe's bounded sampler at the named checkpoints
  `baseline`, `write-1`, `write-2`, `third-write`, `group`, `undo`, `redo` and
  `restored`, plus scoped Artmesh checkpoints. The sampler is packaged into the
  seed jar; no second manager data directory or shared evidence writer is used.
- `run-history-primary-validation.sh` is the new thin capability wrapper. It
  delegates to the generic current Runner, supplies only the history panel and
  seed plugins, observes only the seed primary result file, and contains no
  custom hooks or client scripts.

The existing `run-history-baseline-validation.sh` remains unchanged and blocked
because it depends on the rejected cleanup collector. Do not call it and remove
flags from it; use the new primary capability after the parent reviews and
schedules it through the current manager.

## Evidence

The seed's primary JSONL is:

```text
data/dev.turboism.validation.history-seed/history-seed.jsonl
```

Each paired line contains native manager observations and the SDK captured
metadata from one same-EDT sample, with explicit provenance fields such as
`nativeEvidence`, `sdkEvidence` and `nativeUiCoverage`. SDK operation-time
metadata is not native UI coverage: `nativeUiCoverage=not-proven-by-seed` must
not be promoted to a UI-readiness claim. The standalone manager probe remains
available when separate native UI interaction evidence is needed.

A seed PASS requires the terminal exact line
`{"type":"summary","status":"PASS"}`, all required paired phases, valid
native/SDK identities and entry sequences, no failed or truncated required
sample, and every existing semantic, grouping, navigation and restoration
check. The writer bounds JSON strings and the complete JSONL artifact at 2 MiB;
missing, failed, over-bound or mismatched evidence cannot produce PASS. The
terminal summary is always the final seed line when the artifact can be written.

Native observation is read-only: no native writes, hooks, field enumeration,
unknown-object stringification, or automatic native Undo/Redo discovery was
added. Existing native class selectors, scalar/list allowlist and depth/node/
string/entry bounds remain unchanged. Native unsupported/degraded facts remain
explicitly reported rather than being presented as SDK capture.

## Build and manager scheduling

Build/package and run only focused non-host checks in this worktree:

```bash
./gradlew previewBundle :plugins:history-panel:jar :testing:integration-tests:testClasses
bash scripts/preview/package-windows-history-panel-validation.sh
bash scripts/test/test_history_validation_probe_packaging.sh
```

`host_validation.py` is the sole scheduling/notification entry point. The parent
must review the wrapper's generic Runner arguments, then use the current
manager's `list`, `plan`, `prepare`, `submit`, `status`, `events` and `wait`
workflow as documented by its `--help` and
`README-host-validation-scheduling.md`. The wrapper's `--dry-run` is inspection
only; it does not launch Cubism. Do not use superseded direct transport or
collector commands.

Exact-host acceptance still requires the parent-owned managed run, official
`CubismEditor5.bat` launch, fresh isolated fixture, normal exit and authoritative
cleanup evidence. This implementation does not claim those results or native
UI coverage.
