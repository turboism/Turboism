# Prepare Turboism 0.70.0 acceptance and release notes

Draft PR body only. No GitHub PR exists yet.
Keep the eventual PR in draft until the remaining acceptance gates pass.
Do not publish a release from this preparation state.

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

## Deferred by explicit user decision

- GIF recording-strip, shortcut, optimized/unoptimized export, and large-model recovery acceptance.
- Investigation of persistent F7 input.

Record these items as deferred and unverified, not fixed or passed.
The source still includes the GIF feature.
No further timeout or queue-limit exemption conceals the unresolved input source.

## Outstanding acceptance

- [ ] Batch deformer rename: native preview, selection, cancellation, stale-preview refusal, and Undo/Redo.
- [ ] Parameter transfer and in-place self-inversion on the real host.
- [ ] Framework/plugin self-update through the supported signed managed-launch path.
- [ ] Runtime configuration recovery and migration.
- [ ] Marketplace UI with an actually truncated explanation and localized README link.
- [ ] Recent-project-board layouts and a genuine empty state.
- [ ] Representative lowest/highest patch-version host matrix.
- [ ] Final completed-tree gates and canonical version identity.
- [ ] Signed candidate/source binding.
- [ ] Final cumulative release-note and locale-binding approval.

## Current blockers

Seven old Windows Cubism process records remain after authorized cleanup attempts.
Their non-active exit codes and missing windows do not prove complete kernel cleanup.
The preparation does not bypass real-host admission or treat the earlier forced exit as successful lifecycle evidence.

GitHub CLI currently has no authenticated API identity.
SSH Git access does not establish permission to create a PR through the API.

## Evidence and review

- [Installer and Windows findings](installer-and-windows-findings.md)
- [GIF findings and user-directed deferral](gif-recording-findings.md)
- [Cumulative release-note inventory](release-note-review.md)

The ignored native evidence directory contains artifact, source, compiler-input, driver, and toolchain hashes.
It also retains screenshots, native README text, the successful result, and failed attempts.
The local EXE is unsigned and is not the final signed release candidate.
