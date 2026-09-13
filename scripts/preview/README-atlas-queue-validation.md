# Fixed Atlas queue capability (5303)

User authorization covers implementing this capability and using the unified manager for final Atlas validation. This is **not** a new Runner. `run-cubism-host-validation.sh` already supports the reviewed 5303 installation/JAR. After all six Geometry100/500/1000 jobs passed and were recorded, the user explicitly authorized proceeding to final Geometry2500, native once/new once.

## Reviewed dependency closure

| Dependency | Capture / boundary |
| --- | --- |
| Production Agent | Fresh `previewBundle` from this worktree, captured with `--agent`. Supplies ASM and Jackson to the auxiliary Agent. |
| Atlas plugin | Fresh `:plugins:atlas-maxrects-bssf:jar`, the single explicitly named plugin; captured with `--plugin`. No MCP, FX, or discovered plugins. |
| Fixed auxiliary Agent | `atlas-queue-probe.jar`, captured with `--aux-agent`. Manifest entry is `AtlasQueueProbe`; packaged sources are exactly `AtlasQueueProbe.java`, `HostUiProbe.java`, `HostTimingProbe.java` and their compiler-generated inner classes. The JAR embeds source/production-Agent SHA-256 inventory. |
| Probe runtime | Java 17 JDK APIs (Swing/AWT, file IO, instrumentation, reflection, collections); ASM/Jackson from captured production Agent. No network, subprocess, Python hook/client, external executables or dynamic dependency loading. |
| Fixture | Exact source/hash mapping in the fixed wrapper for Geometry 100/500/1000/2500. `--fixture-local` snapshots actual bytes; manager creates run-prefixed isolated project copy. Source must remain unchanged. |
| Generic queue tools | Existing `prepare` snapshots Runner/helpers, normalized arguments and source/dirty identity. Worker owns admission, prefix/home, official BAT, process containment, normal exit and cleanup. |
| Host dependencies | Existing configured golden prefix, reviewed 5303 official JAR/BAT and configured Proton runner are independently revalidated by manager. Not copied into the probe or modified. |

No custom-hook allowlist change is needed or made: this uses the existing fixed auxiliary-Agent input, not a hook disguised as FPS. Blocked MCP/FX/history/dynamic hooks remain blocked. The task wrapper rejects arbitrary Runner/JVM/fixture/Agent override arguments. Only `--prepare-dir` and `--dry-run` are accepted after its fixed case arguments. It does not build during prepare/run.

## Operation and limitations

- `atlas:5303@geometry-{100,500,1000,2500}-{native,new}`.
- One fresh host/task, **one actual layout invocation**, no Undo loop, warmup, retry or second implementation in the same host. Initial purpose is queue/driver/geometry acceptance; N=1 timings are not the previous Circle repeated-hot benchmark.
- Current page count must equal the selected fixture size. Probe checks actual native/handled branch, serial planner flag for new, automatic scale, rotation enabled, mesh mode, complete finite/bounded/non-overlapping output and matching LayerRef transforms.
- Snapshots and reflection are outside the native-method timer. Final scale and placement quality must be compared before reporting a performance ratio. Timing is not UI end-to-end latency.
- Native atlas cancellation and native Exit are the only shutdown actions. Probe never calls `System.exit`, kills a process or forces a window disposal. A `status=PASS` line means algorithm assertions only; queue success still requires exact identity, unchanged source/artifacts, normal exit and bound-cgroup cleanup.
- Final Geometry2500 is now explicitly authorized. Each fresh task creates an exclusive `layout-started.txt` marker before its sole layout trigger; duplicate marker creation fails. No warmups, loops or automatic retries. Freeze both prepared IDs before execution, use fixed unique submission keys, and do not resubmit a failed/unknown attempt under a new key. Driver layout ceiling is 21600 seconds (6 hours), Runner result budget 21900 and queue timeout 22500; this is a safety ceiling, not a duration prediction. The longer budget avoids aborting the only native measurement prematurely after the 1000-image native case already took 27 minutes.
- This driver uses Chinese native labels from the reviewed installation. Missing/ambiguous UI controls fail closed rather than clicking an approximate match. Full overflow/Undo, dense parallel and save/reopen matrices remain separate pending work.

## Offline build and validation

```sh
./gradlew previewBundle :plugins:atlas-maxrects-bssf:jar
bash scripts/preview/package-atlas-queue-probe.sh
bash scripts/test/check_host_validation_scheduler.sh
python3 scripts/test/test_host_validation_queue.py
```

`AtlasQueueProbeTest.java` provides offline evidence/one-invocation contract assertions; compile/run it against the packaged probe and captured production Agent, without invoking `premain` or launching Cubism.

## Authorized queue submission

Use local `.env` path configuration (or explicit exported supported `TURBOISM_*` values). Build in this worktree; never update the persistent worker checkout during a task.

```sh
python3 scripts/preview/host_validation.py plan atlas:5303
python3 scripts/preview/host_validation.py prepare atlas:5303 --run-label atlas-queue-smoke
# Only after offline/dependency review, using the actual returned prepared ID:
python3 scripts/preview/host_validation.py submit --prepared ACTUAL_ID --request-id UNIQUE_KEY --timeout-seconds 8100 --json
```

A different configured Proton/runtime from historical Circle measurements must be reported as a separate environment; do not combine its times into the old benchmark table.

## Current authorization: UI 500 only

Geometry2500 was stopped at the user's request after **more than two hours** without a native result. It is closed/cancelled, not authorized for another run. Historical preparation instructions above do not renew that authorization.

The user now authorizes `atlas:5303@ui-{circle,geometry}-500-{native,new}`. These four fixed cases use the same production artifacts and auxiliary dependency closure, with Circle500 SHA-256 `54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50` and the existing Geometry500 hash. No worker/Runner/hook changes.

The timer starts on the settings OK ActionEvent (first listener, excluding automation command polling and the simulated button press delay), and stops on `SHOWING_CHANGED=false` of the exact, previously observed `jp.noids.framework.e.a.f` progress window. Read-only 5303 bytecode establishes `impl.v.a(settings)` → `util.ak` → `impl.y` → layout/list refresh → `util.an` → progress `h()`/dispose. Window events are observed, never fabricated or forced. Missing or misordered lifecycle events fail acceptance.

Raw `uiActionToProgressClosedMs` is **instrumented wall time**, including separately measured `uiInputProbeMs` (input snapshot and observer setup); do not label it zero-probe latency. Output snapshot, O(N²) geometry checks, hashing and JSON writes are deferred until after the observed close. Report raw UI time, input probe cost, method time and UI ratio separately. Subtracting input probe cost can only be an estimate, not an uninstrumented measurement. This boundary does not promise completion of every GPU frame.

### Subsequent authorization: add 100/1000/2500, native maximum 30 minutes

After the four500 UI cases passed, the user explicitly requested Circle/Geometry100/1000/2500 UI runs as well. This is a new authorization for Geometry2500, not a retry of the old entry-only job. Keep the >2h cancelled historical measurement intact and record a separate UI job.

The fixed UI catalogue now admits only counts100/500/1000/2500, datasetscircle/geometry and implementationsnative/new. Exact fixture hashes remain hardcoded. UI driver/result budgets are1800seconds; **submit each newly authorized job with `--timeout-seconds 1800`**, so the independent manager enforces the task hard limit (includes startup/preparation after admission, excludes queue waiting). Native timeout is not a completed layout duration or PASS and has no speedup ratio. Manager may need additional time to prove cleanup after stopping the task. No automatic retries or timeout extension. Existing500 rows are retained, not rerun. Auxiliary dependency closure and official-file boundaries are unchanged.
