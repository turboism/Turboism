# Compact Unified Update Window and Plugin CDN Client Acceptance (2026-11-02)

This round reduced the update window to three buttons: check for updates, cancel the update, and install the update. One install action performs the download, the verification, and the preparation for the next launch. A framework update sits first in the list, is checked by default, and cannot be unchecked; clicking a framework or plugin row switches the log on the right, and an entry with no update stays hidden. Check for updates covers the framework and the plugins automatically.

The current version sits at the top left, the arrow is centred, and the framework target version sits on the right; with no framework target the right side shows a dash. No separate "ready" line appears when the state is ready. The log title on the right aligns with the component name at both ends, the middle dot separator is gone, and the content inset is smaller. The bottom status hint no longer inserts a blank line. The progress bar shows MB in the middle and a download-and-verify duration such as `12m31s` at the far right, and it keeps the value after completion.

## Automated Checks

- `FrameworkUpdatesWindowTest` plus the plugin management tests total 174 cases: 173 passed, 1 skipped on a platform condition, 0 failed or errored, already including the final #67 contract correction.
- The 29 official language completeness checks passed; MB and the duration show only a value and one unit.
- The bootstrap licence, the four-language changelog with its English binding hash, and the Git whitespace check passed.
- The Linux code quality check passed with a Javadoc backlog of 0.
- The download tests cover the exact API-to-CDN redirect, API 404 and 5xx, CDN TLS failure and timeout, fallback from zero after a partial download, the original GitHub CDN redirect, the size and hash checks, cancellation, and rejection of a CDN hash, file name, host, port, or query parameter that does not conform. After the #67 contract addition they also cover reading the signed file name, fallback after a CDN bad hash, truncation, wrong length, or empty stream cleanup, and continued rejection of bad GitHub bytes.

## Local Cubism Acceptance

Isolated experiment: `build/native/issue50-host/unified-updates-local-20261101/home`, real Cubism 5.3.02. The framework was the explicit local build `0.68.1-beta.local-SNAPSHOT (beta, local, dirty)`; it has no official CI identity and is not recorded as a signed framework upgrade.

1. An earlier failed attempt that timed out on a direct GitHub connection stays in the T/V evidence; it created no install task and replaced no plugin.
2. The final W experiment really displayed two plugins awaiting upgrade, and the screenshots verified the three buttons, the right-hand log title, the compact inset, and the timing inside the progress bar.
3. A single click on "install updates" downloaded, verified, and prepared both plugins, showing `0.15 / 0.15 MB`, `0m15s`, and "the update is ready and takes effect after restart".
4. The prepared pending set held exactly two INSTALL entries, and every current plugin file still matched its state before the click.
5. After a normal exit and a relaunch through the Turboism launcher, both plugins moved from `0.68.0` to `0.68.1`: the clip-mask viewer SHA-256 was `8f50e0df26dbdbcbd8c847bb9454771a7b04ae4d4a050d403672f06c6f72b133` and the command palette was `77a287f9d29e0c3a776059046c6ef27548964d89e8505a5dfb101614622f26d6`, both matching the verified packages.
6. The other 30 plugin JARs, the configuration, the update preference, and the official Cubism BAT were unchanged. The pending install set was empty, and the startup log reported `host=ACTIVE, plugins=30, failures=0`. The experiment then shut down normally.

Evidence directory: `build/native/issue50-validation/unified-updates-local-20261101/`. The key files are `W-before.json`, `W-plan-ready-12392218.png`, `W-install-result-12392218.png`, `W-ready.json`, `W-after.json`, `W-local-acceptance.json`, and `W-apply-local-launcher-out.log`.

Behaviour tests cover the fixed check state of the framework entry and the framework/plugin log switching, and a separate window explicitly labelled "layout preview" verified the layout. The local build had no official framework update available, so no CI identity was forged to manufacture a host framework entry.

## Acceleration Path Boundary

This change touches the client only. The server side progresses separately; see the [plugin download contract](plugin-artifact-download-contract.md). On this machine a read-only GET probe returned 404 for the command palette API artifact path and 403 for a CDN HEAD. This round therefore proves that the client completes a real plugin update while the acceleration entry point is not ready, and it does not claim that a production CDN download passed acceptance.

The re-probe after the #67 handover first verified the production catalog Ed25519 signature with the current Java client and then built the acceleration address from the verified entry. The API still answered 404, the client switched automatically to the original GitHub URL with a bounded retry, but the local GitHub connection timed out and the download did not succeed. That result stays in `build/agent-tasks/update-window/issue67-live-probe.log` and does not count as evidence of a CDN success or of a new real download. The automated fallback tests and the W host success above are recorded separately.

The catalog still carries the original GitHub Release URLs, and no signature, public key, or catalog content changed. The install window, the plugin marketplace, and background auto-update share the same download client. A prepared update neither force-closes the editor nor hot-swaps a loaded framework, SDK, Graal, or plugin.
