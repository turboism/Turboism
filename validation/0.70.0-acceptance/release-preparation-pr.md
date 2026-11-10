# Prepare Turboism 0.70.0 acceptance and release notes

Draft PR body only. No GitHub PR exists yet.
Keep the eventual PR in draft until the remaining acceptance gates pass.
Do not publish a release from this preparation state.

## Remote review checkpoint

The user requested remote branch handoff for independent acceptance.
Push the integrated branch and the separate repair branches. Do not merge unreviewed repairs or publish a release.

| Branch | Checkpoint | Review state |
| --- | --- | --- |
| `continue-acceptance-validation` | Integrated fixes and acceptance records | Release acceptance remains incomplete |
| `acceptance/070-theme-deadlock` | `5748a7c674a8172c2ddacc17b011dc9ecad0d4b4` | Pending review; single-click asynchronous Reload remains incomplete |
| `acceptance/070-config-test-portability` | `1fce0a7b7265e1598b9ed5f8a06ba74536119824` | Pending main review and independent verification |
| `acceptance/070-gif-strip-layout` | `0645364bfab3653422f252af6cbf603c5d17bf60` | Pending main review and native acceptance |
| `acceptance/070-windows-matrix-audit` | `cb022e9cf0e47b53f82cb832ac7098153094ed62` | Documentation audit only; not an acceptance verdict |

The repair branches start from preparation commit `9994a3aa7460485d452e8065e442a95703fddad9`.
Review complete commit chains before integration. Do not overwrite another workspace's preparation branch.
The theme branch does not contain the later approved SDK asynchronous-refresher extension.
Reported child test results do not constitute main-agent acceptance.

## Summary

- Compare cumulative release content against stable 0.67.4 / Build 56, including beta-only changes.
- Repair parameter-sync persistence, geometric-dialog ownership, and mirror reset-release handling.
- Restore the preview permission contract and unoptimized GIF export without requiring Gifsicle.
- Keep host EDT delays out of UI/lightweight timeout and circuit-breaker accounting.
- Retain bounded worker pools, admission checks, shutdown checks, and heavy-work policy.
- Repair piped installer verification and Windows Thin answer alignment.
- Normalize native compiler inputs and correct amd64 plugin-name pointer alignment.
- Refresh the Graal strategy header instead of retaining plugin-selection instructions.
- Preserve canonical line separators in filtered Swing log documents.

## Verified in this preparation branch

- Java installer acceptance, including four locales, selection, migration, fail-closed inputs, and uninstall retention.
- Windows installer-script, production packaging, and localization gates.
- Production Windows assembly with compiler warnings treated as errors.
- Native amd64 Windows plugin-selection/README interaction in Simplified Chinese: 22 checks passed.
- All 34 localized plugin labels, default selections, selected-count updates, and select-all/none/all states.
- Five representative README identities and final sections, plus Back-navigation selection retention.
- Normal wizard cancellation with exit code 1; no installation directory and no remaining installer process.
- Focused Swing log filtering: 28 tests passed without weakening the original exact assertions.

Earlier focused scheduler, plugin, and permission checks remain documented in the acceptance findings.
Those checks do not replace final completed-tree verification.
The Java installer run reports platform-specific symbolic-link backup and FIFO fixture skips.
The native GUI pass canceled before payload installation; it does not claim a complete native installation or uninstall.

## Current scope decisions

The user reactivated GIF acceptance. The earlier GIF deferral no longer applies.
GIF output, timing, cancellation, optimized export, and large-model recovery still need acceptance.
The old machine's persistent-F7 observation does not establish a current autonomous plugin loop.
Do not relax timeouts, queue limits, signature checks, or lifecycle fences to conceal a failure.

## Outstanding acceptance

- [ ] Batch deformer rename: native preview, selection, cancellation, stale-preview refusal, and Undo/Redo.
- [ ] Parameter transfer and in-place self-inversion on the real host.
- [ ] Framework self-update through the supported signed managed-launch path.
- [x] Representative signed-catalog plugin upgrade, enable/disable, uninstall, reinstall, and restart persistence; see the native lifecycle record.
- [ ] Runtime configuration recovery and migration.
- [ ] Marketplace UI with an actually truncated explanation and localized README link.
- [ ] Recent-project-board layouts and a genuine empty state.
- [ ] Representative lowest/highest patch-version host matrix.
- [x] Canonical version identity: `0.70.0` is the declared framework version and the SDK API-since table attributes the unreleased members to it.
- [ ] Final completed-tree gates on that identity.
- [ ] Signed candidate/source binding.
- [ ] Final cumulative release-note and locale-binding approval.

## Current blockers and evidence limits

The old seven-process blocker does not describe the current machine.
Windows session r10 timed out and remains failed: `cleanup=unknown`, exit code 1, and `timedOut=true`.
Its bound kernel proof reports zero Job processes and no kernel errors. The recorded processes no longer exist.
The fixture and all captured host binary hashes remained unchanged. These facts do not convert the run into a PASS.
The run proved a 125-row rename preview, cancellation, and exactly two selected name changes.
Redo and native stale-preview refusal remain unaccepted. The driver also needs bounded asynchronous-dialog waits.

The prior native matrix exists. Retained Linux/Proton outcomes verify owned-MOC behavior for all eight representative endpoints.
Their prepared descriptors retain source revisions, dirty digests, and artifact bindings.
Do not label those runs absent. Do not equate them with complete current-source 0.70.0 acceptance.
Compare the affected implementation before selecting the minimum necessary reruns.
The user selected the existing Linux/Proton route, not a new Windows queue backend.

Theme management still needs single-click asynchronous Reload without dropping existing actions.
The GIF repair branch still needs real output and pixel-bound confirmation.
A genuine signed framework upgrade target and the real marketplace truncation branch remain unresolved.
Final completed-tree gates, candidate signing, and release approval remain outstanding.
GitHub API authentication was not rechecked. This handoff creates no PR and publishes no release.

## Evidence and review

- [Installer and Windows findings](installer-and-windows-findings.md)
- [GIF findings and historical deferral](gif-recording-findings.md)
- [Plugin lifecycle native evidence](plugin-lifecycle-native.md)
- [Board and centered update-window evidence](board-and-update-window-native.md)
- [Verified official matrix inputs](host-matrix-official-acquisition.md)
- [Representative matrix specification](representative-host-matrix.md)
- [Cumulative release-note inventory](release-note-review.md)

The ignored native evidence directory contains artifact, source, compiler-input, driver, and toolchain hashes.
It also retains screenshots, native README text, the successful result, and failed attempts.
The local EXE is unsigned and is not the final signed release candidate.

## Progress log

- Canonical version identity adopted: `gradle/common-java.gradle.kts` declares `0.70.0`; the SDK
  API-since table and ten plugin API floors moved to match.
- Configuration portability branch integrated: the plugin-config publication escape is closed and
  the fault tests run on Windows. Five POSIX-only assertions still skip where no POSIX view exists.
- Parameter transfer: the native dialog opens from the real deformer context menu and lists real
  parameters with a target selector and an invert checkbox. Value-level transfer and self-inversion
  remain unaccepted; the repository's `parameter-batch-transfer` host probe already asserts
  transfer, invert, undo, redo and the source-as-default-target case, so the remaining work is a
  queue run on the Linux host rather than more UI driving.

## Operator manual acceptance (2026-11-09, session `host-5304-manual`)

The operator drove the native host directly and reported these results.
Agent `2d74efe07d1f3eebbba37dd27f1830a09a06b2126fbddef6fcac7d1bc116a223`,
Cubism 5.3.04, isolated installation, fixture `physics-model.cmo3`.

| Item | Operator result |
| --- | --- |
| Parameter sync | Normal |
| Batch transfer parameter self-inversion | Normal |
| Batch deformer rename undo/redo | Normal |

These observations are unattended UI evidence, not instrumented assertions.
They confirm the workflows complete and undo once; they do not measure values.
The stale-preview refusal remains unobserved.

## Operator findings from session `host-5304-manual2` (2026-11-09)

The operator drove the native host and reported ten results. Two whole-session failures
came from the runtime, and both are now fixed with regression tests:

- **Theme manager never opened.** An action handler runs on the plugin's single worker,
  joins a runtime future, and that future settles on the same pool. The settle body
  queued behind the thread it had to release, so the join never returned and the
  scheduler logged `sidecar.complete` timeouts every five seconds. Settlements now run
  on a dedicated serial lane.
- **Status-bar timer never appeared.** The first refresh calls the host status region,
  which took longer than the five-second work budget while plugin load still owned the
  event thread; the fixed-delay task then went terminal on one timed-out iteration. A
  periodic refresh now records the overrun as a missed tick and keeps its schedule.

Other accepted items: the update menu is a final flat entry; the plugin manager footer
splits into an acquire row and a selection row; parameter-sync font size matches; the
rename base-name choice moved into the preview footer with a live rebuild; the board
gained hover and press feedback through its children, and list rows center their text.

Still open after this round:

- Theme-manager opening needs a fresh host session to confirm the deadlock fix.
- The 3D preset import rolled back a second time. The readback check compared a predicted
  snapshot, so every form the batch never wrote was judged as well. It now compares only
  the submitted edits and names the field before the form GUID, because the authoring
  diagnostic is cut at 128 characters.
- Animated-WebP export stays unplanned: it needs a VP8 encoder that the JDK does not ship.

### Instrumented evidence from the live session `host-5304-manual3`

Observed through the attached UI observer, not by the operator:

| Item | Observation |
| --- | --- |
| Status bar | Compact metrics read `累计 01:42:57` and `会话 00:00:08`; the timer mounts and keeps updating |
| Scheduler | Zero `sidecar.complete` rejections and zero settlement timeouts; the previous session logged one every five seconds |
| Theme manager | `Turboism 主题管理器` opens as a 480×340 dialog with reload, new, edit, open-directory and import buttons |
| Update menu | The last entry is a flat `检查更新`, directly above it `关于` |
| Rename preview | The footer shows `命名基准` with a `deformer.rename.base` combo at the left of the `取消` / `确认` row |

One timeout remains in the log: the first `project-work-time` refresh still overran the work
budget while plugin load owned the host event thread. The fix keeps the schedule, so the next
tick published the metrics, as the status-bar values above show.

## Operator acceptance of the third round (2026-11-10, session `host-5304-manual6`)

The operator re-tested the round after agent `0c11b368e908` and reported that the
remaining findings are resolved. Recorded from the operator's report, not instrumented:

| Item | Result |
| --- | --- |
| Rename footer combo | Shows the localized base-name label instead of the record text |
| Board list rows | No background box behind the file name |
| Status-bar timer | Zero and paused on an empty workspace; counts only the open project |
| Board left seam | The native rail beside the canvas is covered; no untinted strip |
| Plugin manager / market | Footer no longer ends with a redundant close button |
| Theme coverage | Canvas scrollbar keys are part of the managed palette |

The runtime evidence behind the earlier two whole-session failures (settlement lane and
periodic-refresh survival) was captured in `host-5304-manual3` and is listed above.
