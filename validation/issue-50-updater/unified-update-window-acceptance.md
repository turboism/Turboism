# Unified Update List and Right-Hand Log Acceptance (2026-11-01)

As the user requested, the framework and the plugins now share one pending-update list: a component that is already current or has no available update is not listed; clicking an entry only switches the changelog on the right, and the checkbox decides which components join the update. The framework can be unchecked, and plugins can still be updated on their own when the framework is current. The progress shows the combined MB of the framework and the selected plugins and keeps the download-and-verify timing.

## Implementation and Checks

- PR [#64](https://github.com/turboism/Turboism/pull/64) merged into main `96ff898324f1f0f6cb6af1968e59ba903dd0fce2`. The implementation commits are `da2473eca946e32282efc0cda34a61f4c8f67df4` and `b649bd7f9807467bfc27bca8d6f234c718ec4df1`.
- The plugin changelog is read from the localized README at the fixed source commit named by the verified catalog, and it extracts the changes between the current version and the target version. A missing version note shows a placeholder, and a failed request can be retried. Asynchronous loading never blocks the window, and a stale request never overwrites a newer selection.
- The Java bundled with the host Cubism timed out on a raw GitHub connection, so an official GitHub Contents API fallback was added, still limited to the same source commit, size, and total deadline. That regression reproduced the failure first and passed after the fix.
- Windows plugin and window cases: 169 passed, 0 failures, 1 skipped; the later window, log, and full language checks passed all 53 cases. The Linux code quality, Javadoc, format, and four-language changelog checks passed.
- The PR main CI [41313330744](https://github.com/turboism/Turboism/actions/runs/41313330744), the three-channel packaging [41313330785](https://github.com/turboism/Turboism/actions/runs/41313330785), the release notes, and both history checks all passed.
- This change needs no server modification or redeploy.

## Real Plugin Upgrade on a Local Build

Separate directory: `build/native/issue50-host/unified-updates-local-20261101/home`. The framework identity is explicitly `0.68.1-beta.local-SNAPSHOT (beta, local, dirty)`, not an official CI identity.

1. Cubism 5.3.02 started normally with 30 enabled plugins and 0 failures; MCP and ACP were explicitly disabled in the experiment only.
2. The real native window listed only the two `0.68.0 → 0.68.1` updates of the clip-mask viewer and the command palette, and the right-hand log followed the selected row.
3. A real download click showed `0.15 / 0.15 MB` and a duration of `00:22`; verification succeeded, and an explicit click on install-at-next-launch left exactly two INSTALL entries pending.
4. After a normal exit and a managed restart, both plugin versions and SHA-256 values matched the verified download packages, pending was empty, and 30 plugins were enabled with 0 failures. A further check showed an empty pending list.
5. An official old plugin was restored offline as a changelog fixture, and after a restart the real Chinese `0.68.1` changelog of the command palette was read and displayed. That run used the Slate theme and the body text was readable; the global dark palette of the host has a text and background mismatch, so it is not counted as a full dark-theme acceptance.
6. After the changelog check the experiment shut down normally, and the upgraded command palette and the original theme configuration were restored offline.

Local screenshots and logs are in `build/native/issue50-validation/unified-updates-local-20261101/`; the key files are `native-download-completed-58002012.png`, `queued-plugins.json`, `apply-empty-list-106501386.png`, `local-apply-launcher-out.log`, and `notes-dark-loaded-120523770.png`.

This part proves the native window and a real plugin upgrade and does not pass as a signed framework upgrade.

## Production Release and Combined Upgrade

The production [Beta.10 / Build 84](https://github.com/turboism/Turboism/releases/tag/v0.68.1-beta.10) was published and admitted into the self-update service at `2026-11-01T14:33:33Z`. The candidate [41314639033](https://github.com/turboism/Turboism/actions/runs/41314639033), the protected release [41316246339](https://github.com/turboism/Turboism/actions/runs/41316246339), and the sync [41316801762](https://github.com/turboism/Turboism/actions/runs/41316801762) all succeeded; the main CI, the three-channel packaging, and the history check on main also all passed.

Final ZIP SHA-256: `a479afa6d5657383beb5138f11b187c160ae2e7d001fbaa84f2a2038bc20b516`; production manifest SHA-256: `c496963c4e085896813caf9b603ba65e8b734b1b5d5db42dd8f47a2bbf324fcb`. The run verified the signature and all 36 framework files locally. The live discovery endpoint returned the correct source identity, the Chinese, English, Japanese, and Korean notes were non-empty, the signature verification and the HEAD size and ETag of the 36 objects plus the single-byte Range 206 check passed, and the Stable and Nightly identity and asset hashes were unchanged.

### New Window and Independent Plugin Update on Production Beta.10

Separate directory `build/native/issue50-host/unified-updates-ui-official-20261101/home`, taken directly from the signed production package above. Before the first launch only the two plugins were replaced with official older fixtures, and the framework files stayed as delivered.

- The window really listed the two pending plugins, and the already current framework and the other plugins were absent. Selecting the clip-mask viewer showed that this version provides no changelog; clicking the command palette switched the right-hand title and body to its Chinese `0.68.1` changelog.
- The first catalog request hit `Connection reset` and succeeded after a click on check for updates. The real download and verification showed a combined `0.15 / 0.15 MB` and a duration of `00:02`; install-at-next-launch queued exactly two INSTALL entries.
- After a normal exit and a managed restart both plugins were `0.68.1` with hashes matching the verified catalog and the queued packages; the other 30 plugin JARs, the configuration, the update preference, and the official BAT were unchanged. The 36 signed framework files and the original installation receipt stayed unchanged, pending and the journal were empty, and 30 plugins were enabled with 0 failures.
- A further check showed an empty list with "no updates available"; the experiment shut down normally.

Evidence directory `build/native/issue50-validation/unified-updates-ui-official-20261101/`: `E-ui-command-notes-12521772.png`, `F-plugin-result-12521772.png`, `G-queued-plugins.json`, `I-plugin-acceptance.json`, `J-no-updates-28840142.png`.

### Combined Framework and Plugin Upgrade from an Older Production Version

Separate production starting point: `build/native/issue50-host/unified-updates-official-20261101/context.json`, taken from the verified Beta.9 / Build 83 package. Before the first launch only the two plugins under test were replaced with the `0.68.0` packages from the verified official catalog, and the production framework files stayed as delivered.

Two combined downloads of the old instance timed out in the GitHub response phase and produced no pending install task. After saving the failure screenshots, the blank experiment was exited normally and restarted; the combined download and verification then succeeded, and the window showed framework `102.60 / 102.60 MB` with a duration of `00:11`. That was still the old Beta.9 window; the combined MB and the new layout of Beta.10 are accepted by the separate new-window run above.

A real click on install at next launch wrote the framework pending entry and exactly two plugin INSTALL entries, and the currently loaded plugin files still matched their pre-upgrade state. After another normal exit and a start through the managed launcher:

- The framework was `0.68.1-beta.10 / Build 84` with a source identity and signature receipt matching the production update source, and all 36 framework files matched in size and SHA-256.
- The clip-mask viewer and the command palette both went from `0.68.0` to `0.68.1` with install hashes matching the queued packages; the other 30 plugin JARs, the configuration, the update preference, and the official BAT were unchanged.
- The runtime recorded `UPDATE_STARTUP_CONFIRMED` and the startup result was `host=ACTIVE, plugins=30, failures=0`.
- Framework and plugin pending entries and the journal were all empty. The experiment shut down normally.

Evidence directory `build/native/issue50-validation/unified-updates-official-20261101/`: `B-before-update.json`, `F-reconnected-result-6034616.png`, `G-coordinated-queued.json`, `H-coordinated-apply-launcher-out.log`, `I-coordinated-acceptance.json`. See [unified-update-window-evidence.json](unified-update-window-evidence.json) for the condensed machine-readable result.

The two production acceptances prove the independent plugin update of the new window and the combined framework and plugin upgrade from an older production version. No pending framework entry was manufactured with a forged version or CI identity, and no loaded framework, SDK, Graal, or plugin file was hot-swapped.
