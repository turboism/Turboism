# FPS counting and lifecycle host acceptance

This directory contains validation-only plugins. They are not production plugins
and are not part of release or preview packaging. Build with:

```sh
./gradlew --no-daemon --max-workers=2 previewBundle buildFpsHostProbe
```

The default FPS probe behavior remains unchanged. Opt-in lifecycle acceptance
uses two separately loaded plugin IDs and the public performance service. Configure
the local paths through the ignored `.env` or `TURBOISM_ENV_FILE`, then preflight:

```sh
bash scripts/preview/run-fps-host-validation.sh 5303 lifecycle-acceptance-r1 \
  --jvm-option -Dturboism.validation.fps.lifecycle=true \
  --jvm-option -Dturboism.validation.fps.settleSeconds=0 \
  --plugin "$(pwd)/build/fps-host-validation-observer.jar:fps-host-validation-observer.jar" \
  --focus-editor-window --dry-run
```

After checking admission and inputs, remove `--dry-run` to run through the shared
single-session queue. Use a new run label for each attempt. Repeat serially for
`5302` and `5203`; do not build during active sampling. The wrapper fixes official
JAR identity, a task-scoped CoW Proton prefix, isolated home, and copied fixture.

## Acceptance assertions

The actor records a structured `result.txt` in its plugin state directory. It
requires a real active model and a nonzero native rendering counter, independent
fast and slow delivery, survival of a new subscription after repeated closure of
an old handle, scope-owned subscription cancellation, rejection of retained
service calls, and continued sampling by the other plugin. The observer separately
verifies its own scope closure and retained-service rejection, using task-local
state files for coordination. No model writes, Undo/Redo or persistence changes
are performed. Callbacks already admitted before cancellation may finish.

Normal close reuses the existing test-only, task-fixture-guarded native UI helper.
Lifecycle mode has no System.exit/halt fallback, including readiness failure.
A result-file PASS is only the assertion verdict: acceptance additionally requires
the final supervisor verdict, matching official and fixture hashes, normal exit,
and task-owned cleanup evidence. A preliminary runner result, vanished process,
exit code alone, or an unavailable supervisor log is insufficient. Quarantined
jobs must remain blocked until recovery inspection proves safe cleanup.

Fake-hook failure injection remains in Runtime unit tests. This real-host probe
does not corrupt a native hook to force cleanup failure and does not establish
native Windows compatibility or an FPS performance improvement.
