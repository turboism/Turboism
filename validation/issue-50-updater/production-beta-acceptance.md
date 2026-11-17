# Production Beta Self-Update Host Acceptance: Two Consecutive Upgrades Passed (2026-11-01)

> The later production Beta.9 / Build 83 fixed the client plugin catalog limit recorded below and added the changelog, MB, and timing.
> This document keeps the host history of Beta.2 → Beta.5 → Beta.7; see the [follow-up record](update-window-followup.md) for the newer release and for the wiring acceptance that the user's game session postponed.

The user authorized production deployment, production signing configuration, self-update, merging into main after acceptance, and publishing; the user then explicitly finished the game and allowed the Cubism acceptance to resume. This record uses official GitHub Actions candidates, production signing, and the public production API in a separate Windows Cubism 5.3.02 installation directory.

**Conclusion: Beta framework self-update is usable.** Native download, signature verification, explicit install confirmation, normal exit, managed-startup apply, and health confirmation passed twice in a row: Beta.2 → Beta.5 → Beta.7. The production service is deployed and enabled, and no server configuration is pending this round. The catalog problem of the optional plugin wiring and the first migration requirement of an old installation are at the end.

## Confirmed Production Chain

- PR [#59](https://github.com/turboism/Turboism/pull/59) and [#60](https://github.com/turboism/Turboism/pull/60) merged, and production Beta.5 / Build 79 was published.
- PR [#61](https://github.com/turboism/Turboism/pull/61) fixed stale discovery data on an anonymous GitHub 403 and a falsely green notification workflow; it merged into main `9a450f4b361cbb73f6c81610efb2baaa29da1ec4`.
- PR [#62](https://github.com/turboism/Turboism/pull/62) corrected the format and length assumptions of the short-lived credential. The read-only job credential travels with the real OIDC notification, serves only this pinned GitHub API read, is not persisted, and enters neither the client nor the mirror download. All 86 service regressions passed.
- After every check of PR #62 passed it merged normally into main `e4d9cc932ea104aa7ee51542f0c59039581e6d03`; the main CI [41296740873](https://github.com/turboism/Turboism/actions/runs/41296740873), the three-channel check [41296740883](https://github.com/turboism/Turboism/actions/runs/41296740883), and the history check [41296740870](https://github.com/turboism/Turboism/actions/runs/41296740870) all passed afterwards. Beta.7 keeps its real candidate source `9a450f4b...`, and no release identity was rewritten.
- The fix deployment [41295436877](https://github.com/turboism/turboism-learn/actions/runs/41295436877) succeeded with pinned source `3b977212a35bbd5714bb8aef489bc460b68292eb`. A real manual notification [41295679392](https://github.com/turboism/Turboism/actions/runs/41295679392) and an automatic candidate-completion notification [41296045901](https://github.com/turboism/Turboism/actions/runs/41296045901) both succeeded and returned a fresh ready snapshot.
- The Stable and Nightly release identity and asset hashes were unchanged. The public signature of Beta.5, the HEAD size and ETag of the 36 objects, and the single-byte Range 206 check passed; the release task separately read back and verified the content hash of every object.

## First Real Upgrade: Beta.2 → Beta.5

The native menu checked for updates, the native window downloaded and verified `102586270` bytes, install-at-next-launch was clicked explicitly, the blank experiment editor exited normally, and the original managed launcher started it again.

| Check | Actual result |
| --- | --- |
| Previous version | `0.68.1-beta.2` / Build 76 |
| New version | `0.68.1-beta.5` / Build 79 |
| New source | `206b8ed33f38eb9fc5f12e0bf961e29edb4d961f` |
| Installed signed manifest | `b42c198a657420a86fd18eb6bc4735eaded95e41e8ba66449f65cbe3e8134e55` |
| Framework files | 36 files matched the production manifest in size and SHA-256 |
| Plugins and settings | 32 plugin JARs, the config, the update preference, and the official BAT hashes were unchanged |
| Actual startup | 30 enabled plugins, 0 failures; MCP and ACP were explicitly disabled in the experiment directory only |
| Health confirmation | `UPDATE_STARTUP_CONFIRMED`, `2026-11-01T09:59:41.930807600Z` |
| Transaction state | The new receipt was written, pending and the journal were cleared, and no failed plugin was pending |

Local evidence: `build/agent-tasks/beta2-to-beta5-native-acceptance.json` and `beta5-public-acceptance-final/acceptance.json`; the native screenshots and the before and after snapshots are in `build/native/issue50-validation/framework-beta-user-20261101/`. No editor was force-killed, no loaded framework, SDK, or Graal was hot-swapped, no official BAT was modified, and no CI identity was forged.

## Second Real Upgrade: Beta.5 → Beta.7

The production Beta.7 / Build 81 candidate [41295116909](https://github.com/turboism/Turboism/actions/runs/41295116909) and the follow-up release [41296126926](https://github.com/turboism/Turboism/actions/runs/41296126926) both succeeded, and [v0.68.1-beta.7](https://github.com/turboism/Turboism/releases/tag/v0.68.1-beta.7) was published. The automatic notifications [41296682864](https://github.com/turboism/Turboism/actions/runs/41296682864) and [41296733865](https://github.com/turboism/Turboism/actions/runs/41296733865) both succeeded.

In the Beta.5 instance produced by the first upgrade, the native update window found Beta.7. A real mouse click started the download, the window showed `102586270 / 102586270 B` and completed verification, and an explicit install-at-next-launch click showed the framework and 0 plugins queued. The blank experiment JVM 17732 closed normally, and the same managed launcher started JVM 33276.

| Check | Actual result |
| --- | --- |
| Previous version | `0.68.1-beta.5` / Build 79 |
| New version | `0.68.1-beta.7` / Build 81 |
| New source | `9a450f4b361cbb73f6c81610efb2baaa29da1ec4` |
| Installed signed manifest | `15d93fc1403bb9df78159c4064975a83575881726f84d23fccc5b2a2365e4d1a` |
| Framework files | 36 files matched the production manifest in size and SHA-256 |
| Plugins and settings | 32 plugin JARs, the config, the update preference, and the official BAT hashes were unchanged |
| Actual startup | 30 enabled plugins, 0 failures; MCP and ACP were still disabled in the experiment configuration only |
| Health confirmation | `UPDATE_STARTUP_CONFIRMED`, `2026-11-01T10:22:31.106627300Z` |
| Transaction state | The new receipt was written, framework and plugin pending entries and the journal were cleared, and no failed plugin was pending |

The second upgrade re-verified with independent before and after snapshots and manifests and reused no result of the first pass. Evidence: `build/agent-tasks/beta5-to-beta7-native-acceptance.json`, `beta7-public-acceptance/acceptance.json`, and `B-before-download-snapshot.json`, `B-beta7-queued-4199624.png`, and `C-after-beta7-startup-snapshot.json` in the experiment screenshot directory. After acceptance only this round's blank experiment instance was closed normally, and the installation and evidence files were kept.

The four old SDK and Graal host-owned JARs of Beta.2 and Beta.5 are all retired. After the launcher exits, the USING lease stays for the next helper check by design; the wrap-up run of the production helper `--recover` returned `10 / NOTHING: no journal` and reclaimed the lease of the exited JVM. The final lease count was zero, the installation file hashes matched the successful startup snapshot exactly, and no lease was deleted by hand and no successful installation was rolled back. Evidence: `C-final-stale-lease-reap.log` and `C-final-reaped-snapshot.json`.

The final public HTTP check at `2026-11-01T10:22:43Z`: `/health` and all three channels reported ready, and Beta returned version `0.68.1-beta.7`, Build 81, and the production framework update description. The condensed results of both local acceptances and the public service check are saved as [production-beta-evidence.json](production-beta-evidence.json); the full screenshots, logs, and snapshots are in the Git-ignored local evidence directories above.

## Usage Scope and Known Limits

This round verified framework self-update; "also update the selected compatible plugins" was explicitly unchecked in the native window. In the current public plugin catalog the permission descriptions of the MCP previews `0.68.0` and `0.68.2` are 508 and 1021 characters, above the client limit of 500, so plugin wiring reported `reason length is out of bounds`. That optional wiring has not passed and must stay unchecked during a framework update; no catalog data was deleted and no signature check was bypassed.

An old installation that lacks the production updater and its signature receipt must first upgrade once through a new complete installation package. The current release and local acceptance belong to the Beta channel, so Stable users cannot be claimed to migrate automatically.

This record does not widen the earlier failure recovery and platform evidence: a real bad-plugin rollback, native cancellation, and README component recovery are in the [Windows recovery acceptance](windows-launch-lease-host-validation.md). A complete production framework failure rollback, a physical power loss, the NSIS EXE installation process, and Linux or Proton are outside the scope of these two consecutive successful upgrades.
