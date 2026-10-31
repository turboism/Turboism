# Issues #54 / #55 combined Editor acceptance

Completed within the user’s reduced real-host scope. Full 20-version product-UI
acceptance is **not** claimed. No performance improvement is claimed.

## Build identity

- Base: `1141bd8072c187f8ff8a171f542dc2e912f47e80`.
- Requested cherry-picks: `6d8f9f5ed` (#54), `7e5c71497` (#55).
- Product correction: `3e1f37f8c`; actual bundle source:
  `3ad2db8f08b43c92f8b2ae8b2821cdf2e0c1a702`.
- Agent SHA-256: `91fecfba964920550e448cad6832c0e990b01b38ab5e51f79d159354a9e2f5a8`.
- Final reusable tools: `acc76b5135a9549016f62f35321d3693b5f79a16`.

Later tool/document commits did not rebuild or relabel the product. Independent
plugin versions/API floors remain intact; the two generated target mappings add
no compatibility declaration. Exact plugin/probe/helper hashes and source
archives are retained externally.

## Actual execution

Arch Linux + existing Proton runner, official Windows BAT/embedded JRE, isolated
prefixes and fixture copies. All launches used `host_validation.py`
prepare/submit/serve and the shared Runner/containment/resource locks. All 20
installations had matching official JARs, BATs, embedded Java and native DLLs;
reference JAR presence alone was not treated as a launchable installation.
There was no native Windows or remote run.

| Version | Four product UIs | Model skip OFF/probe/ON | Incremental OFF/probe/ON | Undo/Redo geometry, pixels, aggregate reuse |
| --- | --- | --- | --- | --- |
| 5.0.00 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.0.01 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.0.02 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.0.03 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.0.04 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.0.05 | — | PASS OFF/probe | — | PASS |
| 5.0.06 | — | PASS OFF | — | PASS |
| 5.1.00 | — | PASS OFF | — | PASS |
| 5.1.01 | — | PASS OFF | — | PASS |
| 5.1.02 | — | — | — | PASS |
| 5.1.03 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.2.00 | — | — | — | PASS |
| 5.2.01 | — | — | — | PASS |
| 5.2.02 | — | — | — | PASS |
| 5.2.03 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.3.00 | — | — | — | PASS |
| 5.3.01 | — | — | — | PASS |
| 5.3.02 | — | — | — | PASS |
| 5.3.03 | — | PASS 3/3 | PASS 3/3 | PASS |
| 5.3.04 | PASS | PASS 3/3 | PASS 3/3 | PASS |

“—” means not executed after the user requested no repeated/excessive real-host
work. Already submitted jobs finished normally; new batch-submission clients were
stopped after identity checks. No host/Runner/supervisor/worker was signalled during
that reduction. Nine versions completed both triplets; partial modes are retained.
All 20 versions actually launched for Undo; none was classified as unlaunchable.

The final UI job `e5a0024b-1ff4-45c3-993b-b413ecbe981f` passed the four actual
ACP/MCP/command-palette/perf-stats product plugins through production actions and
management: open, disable/re-enable, shutdown, stale opens and delayed cleanup.
The bounded EDT watchdog released in 2500–2503 ms; replacements survived retained
old callbacks. Sixteen showing-window native Swing paints were captured and the
four opened-window images visually reviewed. Robot desktop images were black and
remain diagnostic: this proves real Editor GUI execution/native painting on
Arch/Proton, not physical desktop presentation. ACP opened Settings only, without
AI/ACP sessions or messages; MCP used loopback. Hooks are task-only.

#55’s existing probes checked installation markers and real Bridge workload.
Strict Undo/Redo OFF/ON geometry and canvas equality, aggregate reuse and zero
additional Bridge failures passed on all 20 versions. Enabled/probe mode failure
counters are zero; OFF correctly records an inactive Bridge (`-1`). The newly
compiled Undo probe’s **per-phase** recovery assertions were not executed after
scope reduction, so aggregate evidence is not presented as per-phase evidence.

## Failures and corrections

- **Product:** command-palette unload reported `PLUGIN_DISABLE_FAILED` and left a
  peer alive. Command palette/perf-stats now retain their scheduler at init instead
  of reacquiring it after generation fencing. Strengthened tests reproduced red,
  then passed with the fix; the corrected complete gate passed.
- **Probe/environment:** initial desktop captures were black; excessive injected
  stalls, a low-color screenshot predicate and expected OFF `NOT_ADMITTED`
  classification were corrected in task tools. A contended ACP unload timeout is
  retained; the final exclusive UI run passed without lifecycle failure markers.
- **Inputs/startup:** two Undo attempts were refused before launch by the
  golden-prefix-in-use check; exclusive retries passed. The first 5.0.03 OFF
  startup timed out before the probe loaded; one focused fresh OFF attempt passed.
- **Probe/layout:** 5.3.02’s unequal OFF/ON canvas widths produced a one-ULP Warp
  difference. An exclusive equal-layout diagnostic passed strict geometry/pixels,
  including a third native leg. New probe guards wait for startup/stable layout.

First failures remain preserved; fresh labels/snapshots identify corrected runs.
No failed attempt was relabelled PASS or propagated to other versions.

## Offline evidence and retention

The corrected product’s `checkCompletedCommit` + combined bundle build passed:
417 tasks, 64 executed/353 up-to-date. CI source coverage passed. Admission
preflight executed four tests without skips/failures in the runtime suite.
Real-JAR artifact suites passed nine model-update and 20 incremental tests without
skips; these are distinct from GUI evidence. Visible-AWT gates used Xvfb/X11 after
a Wayland portal capture hang. No full gate was repeated for later tool/docs.
Both corrected agents compiled; Python compilation, shell syntax and diff checks
passed.

External evidence includes 80 accepted distinct specifications: 20 Undo, 32
model-skip modes, 27 incremental modes and one UI task. `accepted-job-index.json`
and `audited-matrix.json` bind preparedId/jobId/runId, source/artifact hashes,
assertions, screenshots, verified identity, normal exit, safe cleanup, unchanged
fixtures and golden inputs. `detailed-acceptance-report.md` retains further detail;
product/probes and tracked source archives survive workspace removal. Raw paths,
configuration, fixtures, licenses and host files are not committed.

Final hashes of all 20 official JARs/BATs matched the initial inventory
(`official-files-final.json`). After all jobs became terminal, the session-owned
worker’s boot/PID/start identity, UID and command were checked and SIGTERM drained
it gracefully. Final status: worker offline, zero active slots, accepted scopes
safely cleaned (`worker-drain.json`, `status-final.json`). No push, merge, issue
closure or workspace/agent archive was performed.
