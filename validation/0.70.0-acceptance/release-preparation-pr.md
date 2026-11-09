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
- [ ] Final completed-tree gates and canonical version identity.
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
