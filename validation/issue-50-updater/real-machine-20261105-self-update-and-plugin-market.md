# Host Verification: Framework Self-Update, Plugin Market Download/Install/Update, Plugin Hot Update (2026-11-05)

This machine (Windows, 2560×1440 @150%, Cubism 5.3.04) completed one full self-update and plugin market run with the **official published Beta package**. Apart from downloading the official artifacts, no new version was built, signed, or published; the existing `%LOCALAPPDATA%\Turboism` (0.68.0-beta.1) installation and the Cubism BAT integration were left untouched.

## Conclusion

| Capability | Result | Evidence |
| --- | --- | --- |
| Framework self-update (check, download, verify, queue, apply after restart) | **Pass** | Beta.10 / Build 84 → Beta.11 / Build 85, 36/36 framework file hashes match the signed manifest |
| Plugin market download and install | **Pass** | Parameter Lock plugin 0.68.0 downloaded, verified, and **hot-loaded immediately**, local installations 32 → 33 |
| Plugin update (market and automatic) | **Pass (needs a restart or a hot replacement)** | Clip Mask Viewer and Command Palette 0.68.0 → 0.68.1 applied after a restart; Perf Stats and ArtMesh Mirror hot-replaced to 0.68.1 inside the session |
| Plugin hot update (unload, load, and replace while running) | **Pass** | `hot unload succeeded` → `hot load succeeded version=0.68.1`, `pending.json` empty, no restart |
| Plugin artifact mirror (`/plugins/...` route of `api` and `dl`) | **Fail (server side)** | Every sampled artifact answered 404 and the client could only fall back to GitHub Releases |

## Environment and Experiment Root

- Experiment root: `build/native/real-machine-20261105/`
  - `pkg-beta10/`: the unpacked official ZIP (SHA-256 `a479afa6d5657383beb5138f11b187c160ae2e7d001fbaa84f2a2038bc20b516`, matching the `v0.68.1-beta.10` asset)
  - `home/`: a separate Turboism installation root copied from `pkg-beta10`, with managed GraalVM 25.2.4 taken from a **copy** of the existing local installation
  - `backup/original-plugins/`: the original JARs of the replaced plugins
  - `launcher*.stdout.log`, `screen-*.png`, `upd-*.png`, `mkt-*.png`: startup and UI evidence
  - `home/logs/runtime/2026-11-05/`: the runtime logs of four sessions
- Experiment configuration: `disabledPlugins=[mcp, acp]` with the other 31 official plugins enabled; `skipUpdateCheck=false`; the update cache is in `~/.cache/turboism/self-update` (the product requires the cache outside the installation directory).

## 1. Framework Self-Update

1. The background check completed with `UPDATE_HINT_SENT id=turboism-update-available identity=0.68.1-beta.11 (Build 85)`, and the update window title read `0.68.1-beta.10 (beta, Build 84) → 0.68.1-beta.11` (`upd-03.png`).
2. Clicking "download update" showed `downloading and verifying`, `total 102.75 / 102.75 MB`, and `download and verification 00:29` (`upd-08.png`, `upd-09.png`). This machine was not admitted for incremental update (`TURBOISM_INCREMENTAL_UPDATE installation=NOT_ADMITTED`), so it downloaded the complete package.
3. After verification the state was `verified, waiting to install`; clicking install at next launch changed it to `will install at next launch` with the footer `the framework and 2 selected plugins are queued and will update at the next launch.` (`screen-22.png`), and it wrote `state/runtime/update/helper/pending.json` plus the prepared `helper-runner.jar`.
4. After a normal exit (File, then Exit, with no force kill) the managed launcher started it: `[turboism-helper] AWAITING_HEALTH: 0.68.1-beta.11` (`launcher2.stdout.log`).
5. The runtime identity after the restart: `Version : 0.68.1-beta.11 (beta, Build 85)`, `plugins=31, failures=0`; `home/installed-build.json` read `{"version":"0.68.1-beta.11","channel":"beta","buildNumber":85,"sourceRevision":"ff068c4dee67bd93b464f88ccf905c7bddafdc70"}`.
6. Installation tree integrity: checking every entry against `state/runtime/update/receipts/<manifestSha>/manifest.json` (the signed manifest) found **36/36 matching file hashes** with nothing missing; after the health confirmation the helper directory held only the lease file and the old backup was discarded as the protocol requires.

## 2. Plugin Market Download and Install

- Market header: `market plugins: 33 | currently installed locally: 32`.
- Installing the not-yet-installed Parameter Lock plugin 0.68.0 changed the state to `installed (0.68.0)` with 33 local installations and the footer `took effect immediately. (Plugin installed and applied immediately.)` (`mkt-08.png`); the runtime log read `ParameterLockPlugin enabled` plus `Plugin lifecycle: hot load succeeded version=0.68.0`, and the file landed as `plugins/dev.turboism.plugin.parameter-lock.jar`. **No restart was needed.**
- Download path: the client first tried `api.turboism.dev/v1/plugins/artifacts/...` and then `dl.turboism.dev/plugins/...`; both answered 404, after which it fell back to the GitHub Release URL of the signed catalog and succeeded (see problem 1 below).

## 3. Plugin Update

- The automatic update (15 seconds after startup) found two outdated plugins, downloaded 0.68.1, and wrote `state/runtime/plugin-management/pending.json` (`rawSha256` and `descriptorSha256` matching the signed catalog) while showing the canvas hint `PLUGIN_UPDATE_HINT_SENT count=2`.
- After the restart the queued entries applied: `clipmask-viewer.jar` and `command-palette.jar` were replaced with 0.68.1 and renamed to `dev.turboism.plugin.clipmask-viewer.jar` and `dev.turboism.plugin.command-palette.jar`; the market showed `installed (0.68.1)`.
- Clicking "update" on an installed plugin in the market writes `pending.json` and asks for a restart (measured on ArtMesh Mirror 0.68.0 → 0.68.1: after the click an INSTALL entry appeared in `pending.json` and the JAR was unchanged; the next launch produced 0.68.1).

## 4. Plugin Hot Update (While Running)

All three independent observations happened inside the same Cubism session with no restart:

1. `perf-stats` 0.68.0 → 0.68.1: `Plugin lifecycle: hot unload succeeded`, then 5 seconds later `load succeeded name=Performance Statistics version=0.68.1` plus `hot load succeeded version=0.68.1`, and afterwards the `operations` of `pending.json` were empty (a fully hot apply with no restart item left).
2. `artmesh-mirror` 0.68.0 → 0.68.1: `hot unload succeeded` → `hot load succeeded version=0.68.1`.
3. Market "disable" and "enable": disable triggered `disable → shutdown → unload succeeded` (`hot unload succeeded`), enable triggered `hot load succeeded`, and the footer showed `took effect immediately`.

## Problems Found

1. **The plugin artifact mirror is unavailable (server side, high priority).** Sampling `command-palette 0.68.1`, `clipmask-viewer 0.68.0`, `parameter-lock 0.68.0`, and others:
   - `https://api.turboism.dev/v1/plugins/artifacts/<sha256>/<file>` → `{"error":{"code":"NOT_FOUND"}}`
   - `https://dl.turboism.dev/plugins/<sha256>/<file>` → `404 Not found`
   The code in `distribution/plugin-jars.mjs` requires `PLUGIN_JAR_MIRROR_ENABLED=true` plus `plugins/index-v1.json` and `plugins/objects/<sha>/<file>` in R2; the manifest is currently missing or the mirror is off. The live client can only rely on the GitHub Release fallback, which makes plugin download unavailable for users who need the mirror because GitHub is unreliable.
2. **The market "update" does not hot-replace a bundled plugin (medium priority).** Those JARs are named by slug (such as `clipmask-viewer.jar`) while the hot-replace target path is `plugins/<plugin-id>.jar`, so the plugin is judged not installed and goes through a queued install that needs the next launch; the UI gives no immediate feedback, which looks like no response. (Perf Stats and ArtMesh Mirror really were hot-replaced because their file names are `<plugin-id>.jar`.)
3. **The market local version snapshot is scanned only at startup.** After changing `plugins/*.jar` directly inside a session the market "current state" still shows the old version, and "refresh" only re-fetches the catalog without rescanning local files; only a restart updates the display.
4. **At 150% scaling and maximized, the update window bottom buttons are hidden by the taskbar** (window 2582×1462 on a 2560×1440 screen), so the window must be moved by hand to click check for updates, download update, or install at next launch. This is a DPI and layout problem.
5. Side observation: the local proxy resolves `api.turboism.dev` and similar domains to a fake IP (198.18.0.x); the direct-resolution behaviour was not reproduced on this machine.

## Reproduction Entry

```powershell
# 1) Unpack the official Beta.10 ZIP into the experiment root and copy it as the installation root
# 2) Configure home/config.json: skipUpdateCheck=false; disabledPlugins=[mcp,acp]
# 3) Initialize the signed installation receipt
powershell -NoProfile -ExecutionPolicy Bypass -File <lab>\home\bootstrap-receipt.ps1 -HomePath <lab>\home
# 4) Managed launch (the same command for the wrap-up and the restart)
powershell -NoProfile -ExecutionPolicy Bypass -File <lab>\home\launch-cubism-turboism.ps1 `
  -HomePath <lab>\home -CubismRoot "C:\Program Files\Live2D Cubism 5.3.04" -Variant normal
```

## Not Covered

- The installer (NSIS) path, signing key rotation, rollback and power-loss recovery, and Linux or Proton were not verified this round.
- No new Beta or Nightly build and release ran; this round used only artifacts already published online.

## Follow-Up Fixes (Same Day, 2026-11-05)

This round changed code for problems 2 and 4 and re-checked it on the same machine.

### Fix 1: The Update and Market Windows Are No Longer Hidden by the Taskbar on a Scaled Display

- `CoreDialogs.create` now clamps the requested size to `GraphicsEnvironment.getMaximumWindowBounds()` (the screen minus the taskbar) and centres the window inside the available area; `CoreDialogs.show` moves the window back into the available area again after `setLocationRelativeTo(owner)`.
- The pane minimum sizes of `FrameworkUpdatesWindow` dropped from 400×160 and 280×160 to 240×96 and 200×96, and the window minimum size is clamped to the available area as well, so the middle region shrinks first and the bottom check for updates, cancel update, and install update buttons stay visible.
- Host re-check: at 150% (2560×1440 → 1707×960 logical pixels) both the update window and the plugin market window fit fully inside the available area with the bottom buttons and status bar visible (`screen-50.png`, `screen-51.png`), with no manual window move.

### Fix 2: The Hot-Replace Failure Reason Enters the Log (the Diagnostic Gap of Problem 2)

- The failure log of `PluginHotReloadCoordinator` now carries the exception cause: `Plugin replacement failed; restoring installed generation: <cause>`.

### Conclusion on "the Market Update Does Not Hot-Replace" (Behaviour Unchanged, With Evidence)

A relaxation to "hot-replace any single installed JAR in place" was attempted, failed on the host, and was reverted:

- Symptom: right after `hot unload succeeded` came `Plugin replacement failed; restoring installed generation` → `REPLACEMENT_FAILED: Previous installed generation restored.`, the plugin reloaded at the old version, the market fell back to a queued install, and both attempts reproduced it.
- Cause: on Windows the Cubism JVM still holds the old JAR (`[IO.File]::Open(...,'None')` returns "the file is in use by another process"), so `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` cannot overwrite it and `copyAtomic` throws `IOException`. That is exactly why `PendingPluginOperations.canHotApply` allows only the canonical `<id>.jar`: a rename or merge must go through the startup transaction.
- The original design therefore stays: an update of a bundled plugin named by slug goes through queue and next-launch apply, and the market status bar states `restart Cubism to apply. (Plugin installation is pending; restart Cubism to apply it.)` (`mkt-33.png`).
- Hot replacement still works, and was measured, for a plugin whose JAR is named `<plugin-id>.jar` (Perf Stats and ArtMesh Mirror).

### Change List of This Round

- `runtime/src/main/java/dev/turboism/shell/CoreDialogs.java`
- `runtime/src/main/java/dev/turboism/shell/FrameworkUpdatesWindow.java`
- `runtime/src/main/java/dev/turboism/kernel/PluginHotReloadCoordinator.java` (log only)
- `gradle/test-skip-allowlist.txt` (registers the existing skip when Windows lacks symlink permission)
- Focused tests: `FrameworkUpdatesWindowTest`, the plugin market, auto-update, and pending-transaction tests, and `PluginHotReloadIntegrationTest` all passed; `:runtime:spotlessCheck` passed.

### Another Field Observation

When the market download fell back to GitHub after the mirror 404, this machine once reported `PLUGIN_DOWNLOAD_CONNECTION_FAILED: Cannot connect to release-assets.githubusercontent.com` (`mkt-32.png`) and succeeded on retry. That shows from the other side how problem 1 (the unavailable plugin mirror) affects a real user: once GitHub is unreachable, the plugin download fails.

## Re-Check After the Plugin Artifact Mirror Went Live (2026-11-05 13:0x)

Before the mirror went live every `/plugins/...` route of `api` and `dl` answered 404 (problem 1). After it went live the check passed: **fixed, 191/191 artifacts available**.

### HTTP Contract

| Check | Result |
| --- | --- |
| `GET https://api.turboism.dev/v1/plugins/artifacts/<sha>/<file>` | `302` with `Location: https://dl.turboism.dev/plugins/<sha>/<file>`, `Cache-Control: private, no-store`, `Referrer-Policy: no-referrer` |
| Content after following the 302 | `200`, byte count matching the catalog, SHA-256 matching the catalog |
| `https://dl.turboism.dev/plugins/<sha>/<file>` | `200`, `Content-Type: application/java-archive`, exact `Content-Length`, `Content-Disposition: attachment; filename="<file>"`, `ETag: "<sha256>"`, `Accept-Ranges: bytes` |
| `Range: bytes=0-1023` | `206` with `Content-Range: bytes 0-1023/72797` |
| `If-None-Match: "<sha256>"` | `304` with the same ETag |
| Unknown sha | `api` → `404 {"error":{"code":"NOT_FOUND"}}`, `dl` → `404 Not found` |
| `HEAD` | `dl` supports it; `api` answers `405` (the client uses GET only, so no impact) |

### Coverage (All 191 Catalog Artifacts)

- `HEAD https://dl.turboism.dev/plugins/<sha>/<file>` for each signed catalog entry: `191/191` answered `200` with a `Content-Length` exactly equal to the catalog `size`, with nothing missing and no size deviation.
- 9 entries failed on the first probe because of local TLS and schannel jitter and all answered `200` on a separate retry (a local proxy problem, not a mirror gap).
- Content verification: 5 artifacts fully downloaded with a SHA-256 matching the catalog; `mcp-0.68.2.jar` (1,139,247 B) produced the same hash on two consecutive downloads.

### Client Measurement (Same Machine, Cubism 5.3.04 Experiment Installation)

Updating the History Panel plugin 0.67.5 → 0.68.0 in the market:

- The download succeeded and staged `dev.turboism.plugin.historypanel-32ec4d23…jar` (52,635 B with a hash matching the catalog), and the status bar showed `restart Cubism to apply. (Plugin installation is pending; restart Cubism to apply it.)`.
- Sampling the Cubism JVM connections during the download showed only `198.18.0.105` (api) and `198.18.0.107` (dl) and no GitHub endpoint (`release-assets` = `198.18.0.98`, `objects` = `198.18.0.134`, `github.com` = `198.18.0.39`). The client therefore already used the "api 302 → dl" mirror chain and no longer depended on the GitHub fallback.

### Speed Comparison (`mcp-0.68.2.jar`, 1.14 MB, Local Proxy Environment)

| Source | Result |
| --- | --- |
| Mirror `dl.turboism.dev` | 0.51 s / 0.48 s (about 2.2–2.4 MB/s), identical hash both times |
| GitHub Release | 1.14 s (about 1.0 MB/s); the second retry first spent 19.5 s to get the 302 and then failed the TLS handshake |

The mirror chain gave about twice the throughput of GitHub on the current network and showed none of the GitHub connection jitter.

### Conclusion

Problem 1 (the unavailable plugin artifact mirror) is resolved after the mirror went live: the routes, the signature-bound ETag, the size, the Range, the conditional request, and the client fallback order all behave as expected, and the real client download already uses the mirror. The earlier record that "only the GitHub fallback works" no longer holds.
