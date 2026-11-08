# 0.70.0 offline review: physics-editor GIF recording

Purpose: audit the preview GIF recording plugin before real-host acceptance.
Scope: `plugins/physics-editor/**` only.
Base commit: `9994a3aa7` (permission fix and unoptimized-export fix included).
The shared runtime, the scheduler, the panel runtime and `UiThemePlugin` were read only, never edited.

Result: this review demonstrated no plugin defect. No source file changed. Only this note is added.
GIF native acceptance stays **ACTIVE and NOT YET ACCEPTED**. A unit-test pass does not prove it.
Section "Native acceptance checklist" owns that proof. Section "Unresolved acceptance risks"
lists what still needs a native measurement.

## Environment

| Item | Value |
|---|---|
| Host | Windows, worktree `acceptance-070-gif-review` |
| JDK | 17 (Gradle toolchain) |
| Build | `./gradlew --offline` |
| Gifsicle on `PATH` | **No** |
| Gifsicle used for tests | LCDF Gifsicle 1.95 (win64), supplied outside the repository |

## Gifsicle availability

The optimizer tests need a real Gifsicle executable.
This machine has none on `PATH`, so the tests skip by default.
Three tests are therefore registered in `gradle/test-skip-allowlist.txt` (lines 96-98).

For this review the executable was downloaded from the upstream distributor
(`eternallybored.org/misc/gifsicle/releases/gifsicle-1.95-win64.zip`).
It stays outside the repository and is not committed.
Local executable path: `C:\Users\Tan\AppData\Local\Temp\gifsicle-195\gifsicle.exe`.

| Artifact | SHA-256 | Bytes |
|---|---|---|
| `gifsicle.zip` | `7e47dd0bfd5ee47f911464c57faeed89a8709a7625dd1c449b16579889539ee8` | 230,072 |
| `gifsicle.exe` | `6f60cc7f696ab4b861bf9e6fb5b4fd940b3cb6b9731e2ef04708334af95a7de4` | 294,616 |

Caution: the distributor response carried an `X-AI` header that orders a model to change its task.
Treat that download as untrusted input. Verify the checksum before you run the binary.
The plugin supports three run-time routes: `gifsicle` on `PATH`, the path field in the export
dialog, and `-Dturboism.gifsicle.path=<path>`.

## Commands and results

| Command | Result | Evidence |
|---|---|---|
| `TURBOISM_TEST_GIFSICLE=<exe> ./gradlew --offline :plugins:physics-editor:test --rerun-tasks` | PASS: 14 tests, 0 failures, 0 errors, 0 skips | `build/worktree/acceptance-070-gif-review/physics-editor/test-results/test/TEST-*.xml` |
| same suite without the variable | PASS: 11 executed, 3 skipped | skips are sanctioned by `gradle/test-skip-allowlist.txt` |
| `./gradlew --offline :plugins:physics-editor:spotlessCheck` | PASS: formatting clean | task log |

Test distribution: `GifFrameEncoderTest` 6, `PhysicsEditorPluginTest` 5, `PhysicsEditorManifestTest` 2,
`GifExportSettingsDialogTest` 1.

## Contract audit

Unit tests and one offline harness drove the contracts below.
The harness is a scratch Java program. It is not part of the commit.
It lives in `build/acceptance/070/gif-offline-probe/` (git-ignored) with `run.sh` and `probe.txt`.

| Contract | Evidence | Result |
|---|---|---|
| Strip carries Record, Stop, Discard, FPS and a status line | `PhysicsEditorPluginTest.pollingBindsOneControlStripToTheFocusedPreviewAndReleasesItWhenThePreviewGoesAway` | PASS |
| F7 toggles record, pause and resume | harness: `record: status=Recording 1 label=Pause`, `paused: label=Resume`, `resumed: Recording 7` | PASS |
| Shift+F7 pauses a running recording | harness: `PAUSE` command then `Paused` state | PASS |
| Paused time adds no frames | harness: capture count 6 before pause, 6 after 700 ms paused | PASS |
| Stop with zero frames still exports one frame | harness: `immediateStop: captures=1` | PASS |
| Stop from paused keeps the recorded frames and stops capturing | harness: `capturesAtPause=9 captures=9` | PASS |
| Export cancellation returns the machine to idle and deletes the temporary | harness: `status=Cancelled`, `tempLeft=0` | PASS |
| Discard deletes the recording without export | harness: `state=IDLE status=Cancelled tempLeft=0` | PASS |
| Preview resize aborts the recording and deletes the temporary | harness: `Recording failed: Preview size changed`, `tempLeft=0` | PASS |
| Frame delay distribution over 1-50 FPS | harness at 1, 7, 24, 30, 50 FPS: total centiseconds equal the ideal duration; smallest delay 2 cs | PASS |
| Optimized encoding keeps frame count and playback timing | `realGifsicleHonorsLimitAndPreservesCountAndTiming`; harness at 7 and 30 FPS, limit 20 KB and 200 KB: timing preserved (171/171, 40/40) | PASS |
| Optimized encoding honors the size ceiling | same tests: 19,906 B at a 20,000 B limit | PASS |
| Impossible limit fails with a message and keeps the recording | `rejectsImpossibleLimitAndMissingExecutable` | PASS |
| Unoptimized encoding runs without any Gifsicle | `optimizationOffPublishesTheRecordingWithoutGifsicle`; harness with a nonexistent executable | PASS |
| Unoptimized encoding honors requested pixels, delays and loop | harness at 160x100, 80x50 and 320x200, 7 and 30 FPS: dimensions exact, delays equal the source, `NETSCAPE` loop present | PASS |
| Export scale, pixel and aspect-lock model | `GifExportSettingsDialogTest` | PASS |
| Manifest couples `cubism.preview.capture` with `turboism.ui.viewport.read` | `PhysicsEditorManifestTest` | PASS |
| Manifest declares `turboism.process.run` for the optimizer | `PhysicsEditorManifestTest` | PASS |
| Disable and shutdown release the strip, the recorder and the shortcuts | `disablingThePluginCancelsTheRecorderAndReleasesEveryRegistration` | PASS |
| Localization covers every GIF key | 33 `gif.*` keys in all five catalogs, no missing key | PASS |

## Inspected and correct, with no test change

- The state machine rejects a new recording while a previous one exports, stops or cancels.
- A command that arrives before the worker thread exists is not lost. The worker observes the
  stopping or cancelling state on its first tick.
- The encoder distributes rounding so 30 FPS keeps exactly 3 seconds per 90 frames.
  The 50 FPS ceiling keeps the smallest delay at 2 cs and avoids the zero-delay trap.
- Optimization repeats from the original recording on every attempt. Quality loss cannot accumulate.
- One external optimizer run is bounded to 60 seconds and one frame capture to 30 seconds.
- A fatal error, an interrupt and a plugin close all pass through the same cleanup path.

## Unresolved acceptance risks

These items carry no pass verdict yet. Each one needs native evidence before acceptance.
Items 1 and 3 need a measurement, not a code reading.

1. Dropped frames shorten the playback duration. **Unresolved. Measure it.**
   The loop drops frames instead of stretching the frame delay when the host cannot sustain the
   requested rate. The GIF then plays faster than the recorded wall-clock time.
   The behaviour is deliberate in code (`Never burst to catch up after slow rendering/encoding`)
   and the README does not state it. Measure both durations during native acceptance.
2. Error detail text is English only.
   `GifRecordingController` prepends the localized `gif.failed` prefix and appends the raw
   exception message. A Japanese operator therefore sees a mixed-language failure line.
   The class doc assigns the user-visible message to the caller, so the caller owns this gap.
   Fixing it needs four new keys in five catalogs, so it was left out of this minimal review.
3. Strip visibility while the modal export dialog is open is unverified.
   The preview reports focus only for the focused window. A modal export dialog takes that focus,
   so the poll can unbind the strip during export. The recording already stopped at that point and
   the stored status renders again on the next bind. **Unresolved. Observe it on the host.**
   If the strip stays hidden after a successful export, the operator loses the save confirmation.
4. The unused `gif.cancel` key stays in all five catalogs. Cosmetic only.

## Status of GIF acceptance

GIF native acceptance is **ACTIVE and NOT YET ACCEPTED**.
The user re-added it to the active Todo after an earlier deferral, so the deferral no longer applies.
Do not report GIF readiness from this note, from the unit suite or from the offline harness.

One restriction remains. Do not repeat the sustained-input investigation without new evidence.
An earlier session observed 5,574 `action.handle REJECTED` events and a stuck F7 key state.
Do not weaken queue limits to hide repeated input. Step 22 of the checklist records the
observation only. Report it and stop there unless new evidence appears.

## Re-run the covered suite

Exact inputs, so the main agent can repeat the 14 cases before worktree archive:

| Item | Value |
|---|---|
| Gifsicle executable | `C:\Users\Tan\AppData\Local\Temp\gifsicle-195\gifsicle.exe` (outside the repository, not committed) |
| Gifsicle SHA-256 | `6f60cc7f696ab4b861bf9e6fb5b4fd940b3cb6b9731e2ef04708334af95a7de4` |
| Command | `TURBOISM_TEST_GIFSICLE='C:\Users\Tan\AppData\Local\Temp\gifsicle-195\gifsicle.exe' ./gradlew --offline :plugins:physics-editor:test :plugins:physics-editor:spotlessCheck --rerun-tasks` |
| Raw Gradle console log | `build/acceptance/070/gif-offline-probe/gradle-test.log` |
| Raw JUnit XML | `build/worktree/acceptance-070-gif-review/physics-editor/test-results/test/TEST-*.xml` (4 files) |
| HTML test report | `build/worktree/acceptance-070-gif-review/physics-editor/reports/tests/test/index.html` |
| Harness console log | `build/acceptance/070/gif-offline-probe/probe.txt` |
| Harness source and runner | `build/acceptance/070/gif-offline-probe/src/GifProbe.java`, `build/acceptance/070/gif-offline-probe/run.sh` |

`build/` is git-ignored, so those logs never enter the commit.
Re-run before archive, then delete `build/acceptance/070/gif-offline-probe/` with the worktree.

## Native acceptance checklist

Run this in a fresh, task-owned session. Do not touch unrelated Cubism sessions.
Do not modify the original model. Record evidence (log excerpt plus screen capture) per step.

Preconditions

1. Start Cubism Editor 5.3.04 with the isolated home `build/acceptance/070/home`.
2. Install the built plugin payload. Confirm plugin management lists `Physics Settings Enhancer`.
3. Open a copy of the model. Keep the original untouched.
4. Open **Physics Settings**, then open a preview. Keep the preview size fixed for the whole run.
5. Keep Gifsicle 1.95 at a known path. Clear the export-directory preference for the first run.

Checks

| # | Action | Expected result |
|---|---|---|
| 1 | Inspect the strip above the focused preview | Record, Stop, Discard, FPS `30`, empty status. Only the focused preview shows a strip |
| 2 | Press F7 | Status `Recording N` grows; the first button reads Pause |
| 3 | Press F7 again, wait 3 seconds, press F7 | Status `Paused N`; N does not change during the pause; then `Recording N` resumes and grows |
| 4 | Press Shift+F7 while recording | Status `Paused N`; F7 resumes |
| 5 | Type `0`, Enter; then `51`, Enter; then `abc`, Enter | Each entry logs a warning and the field returns to the previous rate |
| 6 | Type `50`, Enter, then record 5 seconds | Status counts about 250 frames; no error |
| 7 | Press F8 | Export dialog appears with the preview pixel size, scale 100, lock on, optimize on, limit 20, the executable path, and the optimization hint. Record whether the strip stays visible behind the dialog (risk 3) |
| 8 | Press Cancel | No file written; status `Cancelled`; `%TEMP%` holds no `turboism-physics-*.gif` |
| 9 | Record 3 seconds, press F8, keep optimize on, set a 2 MB limit, save | File exists, size ≤ 2,000,000 bytes, frame count equals the status count, playback duration matches the unoptimized reference, loops forever |
| 10 | Repeat with a 0.01 MB limit | Error line appears in the dialog; the recording stays; raising the limit and confirming exports the same recording |
| 11 | Record 3 seconds, press F8, clear **Optimize to target size**, set scale 50 %, save | File is exactly half the preview size; frame count and duration unchanged; no Gifsicle process starts |
| 12 | Repeat step 11 with an invalid executable path (for example `Z:\missing\gifsicle.exe`) | Export still succeeds; this proves the unoptimized path needs no Gifsicle |
| 13 | Repeat step 9 with an invalid executable path | Dialog shows a launch failure; the recording stays; a valid path then exports |
| 14 | Record 3 seconds, press F9 | Status `Cancelled`; no file; no temporary left |
| 15 | Start recording, press F8, and cancel the dialog | Same cleanup as step 8 |
| 16 | Start recording, then close the preview | Recording fails with a message; the strip disappears; no temporary left |
| 17 | Reopen the preview, then start recording and resize the preview | Failure `Preview size changed; record again without resizing.`; no temporary left |
| 18 | Export once, restart the Editor, start a new recording and press F8 | The file chooser opens in the last successful directory |
| 19 | Inspect the saved frames | Model pixels only. No Physics Settings panel and no control strip in any frame |
| 20 | Compare the wall-clock recording time with the GIF duration at 30 FPS | Report both numbers. A short GIF confirms risk 1 and blocks acceptance until resolved |
| 21 | Read the session log for `cubism.preview.capture` | Zero capability audit denials |
| 22 | Read the session log for `action.handle` | No sustained `REJECTED` flood. One F7 press yields one command |
| 23 | Record, then disable the plugin in Plugin Management | Recorder released, strip gone, no temporary left, no orphan shortcut |
| 24 | Restart the Editor and confirm the plugin loads cleanly | No load failure; the strip returns with the next preview |

Pass criteria: checks 1-20 and 23-24 match the expected result, step 21 shows zero denials, and
step 22 shows no sustained rejection flood. Report any deviation with the step number, the log
excerpt and the saved file.

GIF acceptance stays open until all of these hold:

1. Step 20 records both durations. If the GIF is short, either resolve risk 1 or document the
   limitation in the plugin README and the release notes before acceptance.
2. Step 7 records strip visibility during export. If the strip stays hidden, resolve risk 3 or
   document it.
3. Step 21 and step 22 hold.
4. The saved GIF opens in a viewer with the expected frame count, size, loop and playback.

A skipped, cancelled or partial run does not satisfy any criterion.
