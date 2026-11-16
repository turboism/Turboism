# Plugin Wiring Fix and Update Window Follow-Up (2026-11-01)

The user asked for a fix of the plugin wiring fault found in the official Beta acceptance, together with the changelog, the MB download size, and the timing display.

## Implementation and Reproduction

- `lib/catalog-v2/catalog.mjs` of the official Plugin Directory v2 allows 2000 Unicode code points in a permission or dependency reason; the client limited it to 500. The production MCP catalog carries descriptions of 508 and 1021 code points, so the client rejected the whole signed catalog.
- Both client reason fields now allow 2000, and the empty-string, over-limit, signature, hash, and whole-catalog size checks stay. The signature regression covers 508, 1021, and 2000 characters plus multi-byte Unicode, and checks the empty value and the 2001-character rejection for permissions and dependencies separately.
- Replaying the unmodified live catalog with its production signature: the old Beta.7 reported `reason length is out of bounds`; the new client verified all 33 plugins with the longest permission description at 1021 characters. Neither the catalog data nor the client trust key changed.
- The changelog comes directly from the `notes` and `notesByLanguage` of the existing release API, is cached in full with the candidate, follows the UI language, and falls back to English. Missing notes in old data show a placeholder, and a malformed optional note never affects update eligibility. The notes also show when the current installation is already the newest version.
- The notes render through the existing Markdown subset renderer, which executes no HTML and loads no remote image from a note. The plugin selection table, the changelog, and the operation status occupy separate regions.
- The progress bar now shows decimal MB (1 MB = 1,000,000 bytes), explicitly labelled as the framework file size. The download-and-verify duration uses a monotonic clock and covers the whole combined download task. The clock stops on success, cancel, failure, and close, restarts on retry, and shows hours, minutes, and seconds past one hour.

## Completed Verification

- The original signed-catalog regression reproduced the failure first and passed after the fix; replaying the same live production catalog through the old and the new validator produced the expected difference.
- The Windows Java 17 focused runtime tests first passed all 77 cases; the 33 internal contract cases all passed. A current-version notes regression was then added and the full update service test re-run; the final window passed 14 cases covering cancel, success, failure, retry, and notes display.
- The format check and the four-language changelog consistency passed; the Linux code quality gate passed with a Javadoc backlog of 0.
- The 11 release note tool cases passed on Linux; two existing CRLF byte mismatches failed on the original Windows test and the assertions were not changed.
- The screenshot check of the real Swing components passed. That screenshot uses explicitly simulated progress data and proves only the layout, so it does not replace acceptance of a real Cubism download and plugin upgrade.

Local evidence is in `build/agent-tasks/update-window/`: `public-catalog-beta7-red.log`, `public-catalog-fixed.log`, `focused-tests-final.log`, `current-notes-final.log`, `window-final-checks.log`, and `update-window-preview.png`.

## Production Release and Live Acceptance

- After every check of PR [#63](https://github.com/turboism/Turboism/pull/63) passed, it merged into main `2dcc4248173221c5a4e81941116c057ffbf0b069`; the implementation commit is `88f26fe17fb367b7aea03b96fb0fe6887a1fc244`.
- After the merge, the main CI [41301741316](https://github.com/turboism/Turboism/actions/runs/41301741316), the three-channel packaging [41301741318](https://github.com/turboism/Turboism/actions/runs/41301741318), and the history check [41301741333](https://github.com/turboism/Turboism/actions/runs/41301741333) all passed.
- The real signed candidate Beta.8 / Build 82 [41301760558](https://github.com/turboism/Turboism/actions/runs/41301760558) served only as the local acceptance starting point; the Beta.9 / Build 83 candidate [41301836205](https://github.com/turboism/Turboism/actions/runs/41301836205) passed the full release verification, the production signing, and the final package consistency check. Both came from that main commit, and no CI identity was rewritten.
- The protected release [41303542375](https://github.com/turboism/Turboism/actions/runs/41303542375) succeeded, published [0.68.1-beta.9](https://github.com/turboism/Turboism/releases/tag/v0.68.1-beta.9), and admitted it into the production self-update service. The automatic sync [41303962299](https://github.com/turboism/Turboism/actions/runs/41303962299) succeeded.
- Both candidate complete ZIPs were signature-verified locally with 36 framework files checked. The Beta.9 ZIP SHA-256 is `b8227c3122b35f68c8f416a1acf615acac9cac6ea635303f92fa4f1928a30392` and the production manifest SHA-256 is `4dc3ba642eaec9db0a0d2d8843269847eca2c8959327f92333e9a8d5f3bd06d5`.
- Live check at `2026-11-01T11:56:31Z`: the service and all three channels reported ready; Beta returned Beta.9 / Build 83 with the correct source identity and a complete self-update description. The production signature, the HEAD size and ETag of the 36 objects, and the single-byte Range 206 check passed; the full content hash was read back and verified by the release task. The Chinese, English, Japanese, and Korean notes were all non-empty and within the client length limit, and the Chinese notes included MB, the duration, and the plugin fix description. The Stable and Nightly identity and asset hashes were unchanged.
- This release touches the client only and needs no server change or redeploy.

## Local Progress and Recovery Entry

The separate directory `build/native/issue50-host/framework-update-ui-20261101/home` uses the unmodified official Beta.8 framework package. To verify a real plugin upgrade, the clip-mask viewer and the command palette were replaced with the `0.68.0` packages from the official catalog before the first launch only, and both old package SHA-256 values match the verified signed catalog; the original packages are backed up in the experiment tools directory.

Real Cubism 5.3.02 started Beta.8 / Build 82 with 30 enabled plugins and 0 failures; MCP and ACP were explicitly disabled in the experiment configuration only. Overwatch then took the foreground, and the user explicitly replied "in a game, continue the background release first". Therefore **the download, the plugin wiring application, and the restart health acceptance of the new native window have not run**. Neither the simulated Swing screenshot nor the source tests nor this successful startup is presented as a complete wiring acceptance.

The blank experiment JVM 38076 of this round shut down normally; the production helper `--recover` returned `10 / NOTHING: no journal` and reclaimed the startup lease of the exited instance. The directory and the evidence are kept, and no editor was force-killed, no loaded file was hot-swapped, and no official BAT was modified.

After the user finishes the game, resume the experiment from `build/native/issue50-host/framework-update-ui-20261101/context.json`: start Beta.8 through normal managed launch, let the native check find the published Beta.9, keep the plugin wiring option, select the two `0.68.0 → 0.68.1` plugins above, check the notes, MB, and timing, download and explicitly queue, then exit normally and start again through managed launch to verify. No rebuild, signing, release, or production service configuration is needed. The two complete framework upgrade records of the earlier Beta.7 keep their original scope.

New local evidence is in `build/agent-tasks/update-window/`: `beta8-package-verification.json`, `beta9-package-verification.json`, `beta9-public-acceptance/`, `beta9-public-notes-and-health.json`, and `rollout-state.json`. The experiment snapshot, logs, and screenshots are in `build/native/issue50-validation/framework-update-ui-20261101/`. Condensed release evidence is in [update-window-release-evidence.json](update-window-release-evidence.json).

A user still on Beta.7 must first clear "also update the selected compatible plugins" when upgrading to Beta.9, because the catalog limit of the old client cannot be fixed while it runs; the new validator and the new window apply only after the upgrade and restart.
